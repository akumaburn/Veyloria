package com.eonmux.chatllm.services;

import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * High-performance buffer for SSE streaming from llama-server.
 *
 * PROBLEM: If processing each SSE event is slow (JSON parsing, event bus publishing, etc.),
 * it blocks the socket reader, causing TCP backpressure on llama-server. This makes the GPU
 * idle while waiting for us to consume data.
 *
 * SOLUTION: Fast non-blocking buffer that llama-server can dump into at full speed.
 * A separate consumer drains the buffer and does processing asynchronously.
 *
 * This decouples llama-server's generation speed from our processing speed.
 *
 * ARCHITECTURE:
 * ┌─────────────┐    O(1) offer    ┌──────────────────┐    batch drain    ┌─────────────┐
 * │ llama-server│ ──────────────▶  │ ConcurrentQueue  │ ────────────────▶ │ Vert.x Loop │
 * │  (fast GPU) │                  │  (lock-free)     │                   │ (processing)│
 * └─────────────┘                  └──────────────────┘                   └─────────────┘
 *       │                                   │                                    │
 *       │ No TCP backpressure              │ Absorbs bursts                     │ JSON parse
 *       │ GPU stays busy                   │ ~10K events capacity               │ Event bus
 *       ▼                                  ▼                                    ▼
 */
@Slf4j
public class SSEStreamBuffer {

    // Lock-free queue for maximum throughput - can hold thousands of events
    private final ConcurrentLinkedQueue<SSEEvent> buffer = new ConcurrentLinkedQueue<>();

    // State tracking
    private final AtomicBoolean isComplete = new AtomicBoolean(false);
    private final AtomicBoolean hasError = new AtomicBoolean(false);
    private final AtomicBoolean isStarted = new AtomicBoolean(false);
    private final AtomicBoolean completionHandled = new AtomicBoolean(false);
    private final AtomicLong eventsReceived = new AtomicLong(0);
    private final AtomicLong eventsProcessed = new AtomicLong(0);

    // High-water mark for monitoring buffer pressure
    private final AtomicInteger highWaterMark = new AtomicInteger(0);

    private volatile String errorMessage = null;
    private volatile Throwable error = null;

    // Consumer callback and Vert.x context
    private final Vertx vertx;
    private final Consumer<SSEEvent> eventConsumer;
    private final Runnable onComplete;
    private final Consumer<Throwable> onError;
    private final String conversationId;

    // Idle-timeout configuration. 0 disables idle detection.
    private final long idleTimeoutMs;
    private volatile long lastActivityNanos = 0;
    private volatile boolean idleTimedOut = false;

    public SSEStreamBuffer(Vertx vertx, String conversationId,
                           Consumer<SSEEvent> eventConsumer,
                           Runnable onComplete,
                           Consumer<Throwable> onError) {
        this(vertx, conversationId, eventConsumer, onComplete, onError, 0L);
    }

    public SSEStreamBuffer(Vertx vertx, String conversationId,
                           Consumer<SSEEvent> eventConsumer,
                           Runnable onComplete,
                           Consumer<Throwable> onError,
                           long idleTimeoutMs) {
        this.vertx = vertx;
        this.conversationId = conversationId;
        this.eventConsumer = eventConsumer;
        this.onComplete = onComplete;
        this.onError = onError;
        this.idleTimeoutMs = Math.max(0L, idleTimeoutMs);
    }

    // Drain timer - adaptive interval based on buffer pressure
    private Long drainTimerId = null;
    private static final long DRAIN_INTERVAL_FAST_MS = 1;  // When buffer has items
    private static final long DRAIN_INTERVAL_IDLE_MS = 10; // When buffer is empty
    private static final int BATCH_SIZE = 50; // Max events per drain cycle to avoid starving event loop

    // Performance tracking
    private long startTimeNanos = 0;
    private long firstEventTimeNanos = 0;

    /**
     * SSE event wrapper - minimal data for fast queuing.
     * Uses String interning avoided to prevent contention.
     */
    public static class SSEEvent {
        public final String data;
        public final long receivedAtNanos;

        public SSEEvent(String data) {
            this.data = data;
            this.receivedAtNanos = System.nanoTime();
        }
    }

    /**
     * Start the buffer drain loop.
     * Call this before starting the SSE connection.
     */
    public void start() {
        if (!isStarted.compareAndSet(false, true)) {
            log.warn("[SSEBuffer] Already started for conversation {}", conversationId);
            return;
        }
        startTimeNanos = System.nanoTime();
        lastActivityNanos = startTimeNanos;
        // Start with fast interval, will adapt based on load
        scheduleDrain(DRAIN_INTERVAL_FAST_MS);
        log.debug("[SSEBuffer] Started drain loop for conversation {} (idleTimeoutMs={})",
                conversationId, idleTimeoutMs);
    }

    private void scheduleDrain(long intervalMs) {
        if (drainTimerId != null) {
            vertx.cancelTimer(drainTimerId);
        }
        drainTimerId = vertx.setPeriodic(intervalMs, id -> drainBuffer());
    }

    /**
     * Add event to buffer - called from OkHttp SSE thread.
     * MUST BE FAST - O(1), no blocking, no heavy processing.
     *
     * This is the critical path for preventing backpressure.
     */
    public void addEvent(String data) {
        if (completionHandled.get()) return;
        long now = System.nanoTime();
        // Record first event time for latency tracking
        if (eventsReceived.get() == 0) {
            firstEventTimeNanos = now;
        }
        // Reset idle counter — any traffic from the upstream server counts.
        lastActivityNanos = now;

        // O(1) lock-free enqueue
        buffer.offer(new SSEEvent(data));
        long count = eventsReceived.incrementAndGet();

        // Update high-water mark (non-blocking)
        int currentSize = buffer.size();
        int hwm;
        while (currentSize > (hwm = highWaterMark.get())) {
            if (highWaterMark.compareAndSet(hwm, currentSize)) {
                break;
            }
        }

        // Warn if buffer is growing too large (indicates slow processing)
        if (count % 500 == 0 && currentSize > 100) {
            log.warn("[SSEBuffer] Buffer growing: conversation={}, pending={}, hwm={}",
                conversationId, currentSize, highWaterMark.get());
        }
    }

    /**
     * Mark stream as complete - called from OkHttp SSE thread.
     */
    public void markComplete() {
        isComplete.set(true);
        // Trigger immediate drain to process remaining events
        vertx.runOnContext(() -> {
            drainBuffer();
            // Schedule another drain to ensure completion is handled
            vertx.setTimer(5, id -> drainBuffer());
        });
    }

    /**
     * Mark stream as failed - called from OkHttp SSE thread.
     */
    public void markError(Throwable t) {
        this.error = t;
        this.errorMessage = t != null ? t.getMessage() : "Unknown error";
        hasError.set(true);
        isComplete.set(true);
        // Trigger error handling on Vert.x thread. Guard with completionHandled so
        // handleError()/onError fires exactly once even if markError races the
        // drainBuffer completion path or the idle-timeout path.
        vertx.runOnContext(() -> {
            synchronized (this) {
                stopDrainLoop();
                if (completionHandled.compareAndSet(false, true)) {
                    handleError();
                }
            }
        });
    }

    /**
     * Drain buffer and process events - runs on Vert.x event loop.
     * Processes in batches to avoid starving the event loop.
     */
    // Timer drains and OkHttp-triggered runOnContext drains can use different
    // Vert.x contexts. Serialize the whole batch, including terminal callbacks:
    // a concurrent queue alone does not preserve callback order across consumers.
    // addEvent remains lock-free so the upstream socket never waits on this lock.
    private synchronized void drainBuffer() {
        if (completionHandled.get()) {
            return; // Already completed, don't process
        }

        // Idle-timeout check (only after we've actually started receiving — or
        // since start() — and only if the stream hasn't already finished).
        // Resets every time addEvent() is called, so a long generation that
        // continues to stream tokens never trips this.
        if (idleTimeoutMs > 0 && !isComplete.get() && !idleTimedOut) {
            long idleMs = (System.nanoTime() - lastActivityNanos) / 1_000_000L;
            if (idleMs >= idleTimeoutMs) {
                idleTimedOut = true;
                log.warn("[SSEBuffer] Idle timeout after {}ms with no traffic from upstream "
                                + "(conversation={}, processed={}/{}). Failing stream.",
                        idleMs, conversationId, eventsProcessed.get(), eventsReceived.get());
                markError(new RuntimeException(
                        "LLM idle timeout: no streaming activity for " + idleMs + "ms"));
                return;
            }
        }

        int processed = 0;
        SSEEvent event;
        List<SSEEvent> batch = new ArrayList<>(BATCH_SIZE);

        // Collect batch of events
        while (processed < BATCH_SIZE && (event = buffer.poll()) != null) {
            batch.add(event);
            processed++;
        }

        // Process batch
        for (SSEEvent e : batch) {
            try {
                eventConsumer.accept(e);
                eventsProcessed.incrementAndGet();
            } catch (Exception ex) {
                log.error("[SSEBuffer] Error processing event for conversation {}: {}",
                    conversationId, ex.getMessage(), ex);
            }
        }

        // Check if we're done
        if (isComplete.get() && buffer.isEmpty()) {
            if (completionHandled.compareAndSet(false, true)) {
                stopDrainLoop();
                if (hasError.get()) {
                    handleError();
                } else {
                    handleComplete();
                }
            }
            return;
        }

        // Adapt drain interval based on buffer pressure
        // If buffer has items, drain fast; if empty, slow down to save CPU
        if (!buffer.isEmpty()) {
            if (drainTimerId != null) {
                // More items waiting, schedule immediate drain
                vertx.runOnContext(() -> drainBuffer());
            }
        }

        // Log stats periodically for debugging
        long totalProcessed = eventsProcessed.get();
        if (processed > 0 && totalProcessed % 100 == 0) {
            log.debug("[SSEBuffer] conversation={}, received={}, processed={}, pending={}, hwm={}",
                conversationId, eventsReceived.get(), totalProcessed, buffer.size(), highWaterMark.get());
        }
    }

    private void handleComplete() {
        long totalTimeMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
        long firstEventLatencyMs = firstEventTimeNanos > 0 ?
            (firstEventTimeNanos - startTimeNanos) / 1_000_000 : 0;

        log.debug("[SSEBuffer] Stream complete: conversation={}, events={}, time={}ms, firstEventLatency={}ms, hwm={}",
            conversationId, eventsProcessed.get(), totalTimeMs, firstEventLatencyMs, highWaterMark.get());

        try {
            onComplete.run();
        } catch (Exception e) {
            log.error("[SSEBuffer] Error in completion handler: {}", e.getMessage(), e);
        }
    }

    private void handleError() {
        log.error("[SSEBuffer] Stream error for conversation {}: {}, processed={}/{}",
            conversationId, errorMessage, eventsProcessed.get(), eventsReceived.get());
        try {
            onError.accept(error);
        } catch (Exception e) {
            log.error("[SSEBuffer] Error in error handler: {}", e.getMessage(), e);
        }
    }

    private void stopDrainLoop() {
        if (drainTimerId != null) {
            vertx.cancelTimer(drainTimerId);
            drainTimerId = null;
        }
    }

    /**
     * Cancel the buffer and stop processing.
     */
    public synchronized void cancel() {
        if (completionHandled.compareAndSet(false, true)) {
            stopDrainLoop();
            int dropped = buffer.size();
            buffer.clear();
            log.debug("[SSEBuffer] Cancelled for conversation {}, dropped {} events", conversationId, dropped);
        }
    }

    /**
     * Stop an abandoned generation and notify its owner so the async request
     * terminates too. Quiet cancel() is only for already-terminated streams.
     */
    public synchronized void abort(Throwable cause) {
        if (completionHandled.compareAndSet(false, true)) {
            stopDrainLoop();
            buffer.clear();
            try {
                onError.accept(cause);
            } catch (Exception e) {
                log.error("[SSEBuffer] Error terminating cancelled generation: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Check if buffer is still active (not completed/cancelled).
     */
    public boolean isActive() {
        return isStarted.get() && !completionHandled.get();
    }

    /**
     * Get buffer stats for monitoring.
     */
    public JsonObject getStats() {
        long totalTimeMs = startTimeNanos > 0 ?
            (System.nanoTime() - startTimeNanos) / 1_000_000 : 0;

        return new JsonObject()
            .put("received", eventsReceived.get())
            .put("processed", eventsProcessed.get())
            .put("pending", buffer.size())
            .put("highWaterMark", highWaterMark.get())
            .put("isComplete", isComplete.get())
            .put("hasError", hasError.get())
            .put("totalTimeMs", totalTimeMs);
    }
}
