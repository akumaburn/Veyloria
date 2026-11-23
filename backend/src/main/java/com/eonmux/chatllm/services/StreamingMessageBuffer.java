package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buffers streaming messages to allow recovery if frontend disconnects.
 * Messages are buffered per conversation and can be retrieved on reconnect.
 *
 * This ensures the backend continues receiving from llama-server even if
 * the frontend websocket disconnects, and the complete message is available
 * when the frontend reconnects or the page is refreshed.
 */
@Slf4j
@ApplicationScoped
public class StreamingMessageBuffer {

    // Buffer: conversationId -> streaming state
    private final Map<String, StreamingState> activeStreams = new ConcurrentHashMap<>();

    // TTL for buffered messages (5 minutes)
    private static final long BUFFER_TTL_MS = 5 * 60 * 1000;

    // Maximum buffer size per message (1MB) - prevents OOM from very large messages
    private static final int MAX_BUFFER_SIZE = 1_000_000;

    /**
     * Start buffering for a new streaming message.
     */
    public void startStreaming(String conversationId, String messageId, String createdAt) {
        StreamingState state = new StreamingState(messageId, createdAt);
        activeStreams.put(conversationId, state);
        log.debug("Started buffering stream for conversation {}, messageId {}", conversationId, messageId);
    }

    /**
     * Append content to the streaming buffer.
     */
    public void appendContent(String conversationId, String content, int tokenCount) {
        StreamingState state = activeStreams.get(conversationId);
        if (state != null) {
            state.appendContent(content);
            state.setTokenCount(tokenCount);
            state.setLastUpdate(Instant.now());
        }
    }

    /**
     * Mark streaming as complete.
     */
    public void completeStreaming(String conversationId, String finalContent, int tokenCount) {
        StreamingState state = activeStreams.get(conversationId);
        if (state != null) {
            state.setContent(finalContent);
            state.setTokenCount(tokenCount);
            state.setComplete(true);
            state.setLastUpdate(Instant.now());
            log.debug("Completed buffering stream for conversation {}, {} tokens", conversationId, tokenCount);
        }
    }

    /**
     * Mark streaming as failed with error.
     */
    public void failStreaming(String conversationId, String error) {
        StreamingState state = activeStreams.get(conversationId);
        if (state != null) {
            state.setError(error);
            state.setComplete(true);
            state.setLastUpdate(Instant.now());
            log.debug("Failed streaming for conversation {}: {}", conversationId, error);
        }
    }

    /**
     * Get buffered streaming state for a conversation.
     * Returns null if no active/recent stream exists.
     */
    public StreamingState getStreamingState(String conversationId) {
        StreamingState state = activeStreams.get(conversationId);
        if (state == null) {
            return null;
        }

        // Check if expired
        if (state.isExpired(BUFFER_TTL_MS)) {
            activeStreams.remove(conversationId);
            return null;
        }

        return state;
    }

    /**
     * Check if there's an active or recent stream for a conversation.
     */
    public boolean hasActiveStream(String conversationId) {
        return getStreamingState(conversationId) != null;
    }

    /**
     * Get pending message as JSON for GraphQL response.
     */
    public JsonObject getPendingMessage(String conversationId) {
        StreamingState state = getStreamingState(conversationId);
        if (state == null) {
            return null;
        }

        return new JsonObject()
            .put("id", state.getMessageId())
            .put("content", state.getContent())
            .put("streamingContent", state.getContent())
            .put("role", "ASSISTANT")
            .put("isComplete", state.isComplete())
            .put("tokenCount", state.getTokenCount())
            .put("error", state.getError())
            .put("createdAt", state.getCreatedAt());
    }

    /**
     * Atomically retrieve-and-clear the buffered state once a stream is
     * complete. Concurrent pollers of a completed stream all saw the same
     * message and each cleared the buffer, so a second poller could get a
     * duplicate or clear it under a different participant's read.
     */
    public JsonObject getAndClearPendingMessage(String conversationId) {
        StreamingState state = getStreamingState(conversationId);
        if (state == null) {
            return null;
        }

        JsonObject message = new JsonObject()
            .put("id", state.getMessageId())
            .put("content", state.getContent())
            .put("streamingContent", state.getContent())
            .put("role", "ASSISTANT")
            .put("isComplete", state.isComplete())
            .put("tokenCount", state.getTokenCount())
            .put("error", state.getError())
            .put("createdAt", state.getCreatedAt());

        // Only consume the buffer once the stream is terminal; while a stream
        // is still in flight the poller keeps reading the growing content.
        if (state.isComplete()) {
            activeStreams.remove(conversationId);
        }
        return message;
    }

    /**
     * Clear buffered state for a conversation (e.g., after successful delivery).
     */
    public void clearBuffer(String conversationId) {
        activeStreams.remove(conversationId);
        log.debug("Cleared buffer for conversation {}", conversationId);
    }

    /**
     * Cleanup expired buffers (call periodically).
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        activeStreams.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().isExpired(BUFFER_TTL_MS);
            if (expired) {
                log.debug("Cleaned up expired buffer for conversation {}", entry.getKey());
            }
            return expired;
        });
    }

    /**
     * State class for tracking streaming progress.
     */
    public static class StreamingState {
        private final String messageId;
        private final String createdAt;
        private final StringBuilder contentBuilder = new StringBuilder();
        private String content = "";
        private boolean contentDirty = false; // Track if content needs to be rebuilt from StringBuilder
        private int currentSize = 0; // Track current buffer size to prevent OOM
        private int tokenCount = 0;
        private boolean complete = false;
        private String error = null;
        private Instant lastUpdate = Instant.now();
        private final Instant startTime = Instant.now();

        public StreamingState(String messageId, String createdAt) {
            this.messageId = messageId;
            this.createdAt = createdAt;
        }

        public synchronized void appendContent(String chunk) {
            // Already overflowed: stop accumulating (avoid log-spamming on every
            // subsequent chunk) and surface the truncation via the error flag.
            if (error != null) {
                return;
            }
            // Enforce a buffer size limit to prevent OOM. Previously this threw an
            // IllegalStateException which the SSE drain loop swallowed, silently
            // truncating the response with no error indication. Now we cap the
            // content gracefully and record an error so the completion path can
            // surface that the message was truncated.
            if (currentSize + chunk.length() > MAX_BUFFER_SIZE) {
                int remaining = MAX_BUFFER_SIZE - currentSize;
                if (remaining > 0) {
                    contentBuilder.append(chunk, 0, remaining);
                    currentSize = MAX_BUFFER_SIZE;
                    contentDirty = true;
                }
                error = "Response exceeded maximum length and was truncated";
                complete = true;
                return;
            }

            contentBuilder.append(chunk);
            currentSize += chunk.length();
            // PERFORMANCE: Don't call toString() on every append - defer to getContent()
            // This avoids O(n²) string operations during streaming
            contentDirty = true;
        }

        public synchronized void setContent(String content) {
            this.content = content;
            this.currentSize = content.length();
            contentDirty = false;
        }

        public String getMessageId() { return messageId; }
        public String getCreatedAt() { return createdAt; }
        public synchronized String getContent() {
            // Lazy evaluation - only convert to String when actually needed
            if (contentDirty) {
                content = contentBuilder.toString();
                contentDirty = false;
            }
            return content;
        }
        public synchronized int getTokenCount() { return tokenCount; }
        public synchronized void setTokenCount(int tokenCount) { this.tokenCount = tokenCount; }
        public synchronized boolean isComplete() { return complete; }
        public synchronized void setComplete(boolean complete) { this.complete = complete; }
        public synchronized String getError() { return error; }
        public synchronized void setError(String error) { this.error = error; }
        public synchronized Instant getLastUpdate() { return lastUpdate; }
        public synchronized void setLastUpdate(Instant lastUpdate) { this.lastUpdate = lastUpdate; }

        public boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - startTime.toEpochMilli() > ttlMs;
        }
    }
}
