package com.eonmux.chatllm.messaging;

import com.eonmux.chatllm.services.SSEStreamBuffer;
import com.eonmux.chatllm.services.StreamingMessageBuffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamingReasoningTest {
    private static class Stream {
        final LLMBridge bridge = new LLMBridge();
        final StringBuilder text = new StringBuilder();
        final AtomicLong tokens = new AtomicLong();
        final AtomicBoolean done = new AtomicBoolean(), opened = new AtomicBoolean(), closed = new AtomicBoolean();
        final long[] published = {0}, synced = {0};
        final int[] publishedLength = {0};
        final JsonObject[] usage = {null};
        final SSEStreamBuffer[] holder = {mock(SSEStreamBuffer.class)};
        final List<JsonObject> frames = new ArrayList<>();
        Stream() {
            bridge.vertx = mock(Vertx.class, RETURNS_DEEP_STUBS);
            bridge.messageBuffer = new StreamingMessageBuffer();
            bridge.messageBuffer.startStreaming("chat", "message", "2026-11-13T17:00:00Z");
            var bus = bridge.vertx.eventBus();
            doAnswer(call -> { frames.add(call.getArgument(1)); return null; })
                    .when(bus).publish(eq("stream"), any());
        }
        void external(JsonObject delta, String finish, long interval) {
            JsonObject choice = new JsonObject().put("delta", delta).put("finish_reason", finish);
            bridge.processOpenAISSEEvent(new JsonObject().put("choices", new JsonArray().add(choice)).encode(),
                    null, text, tokens, System.currentTimeMillis(), new long[1], "stream", "typing", "now", "Assistant", null,
                    "message", "chat", done, usage, published, interval, synced, publishedLength, Long.MAX_VALUE,
                    opened, closed, holder);
        }
    }

    @Test void nullableReasoningAndContentStillProduceTheAnswerAndCompleteWithoutADelta() {
        Stream stream = new Stream();
        stream.external(new JsonObject().putNull("content").put("reasoning_content", "Plan first."), null, 0);
        stream.external(new JsonObject().put("content", "A complete").putNull("reasoning_content"), null, 0);
        stream.external(new JsonObject().put("content", " answer.").putNull("reasoning_content"), null, 0);
        stream.external(null, "stop", 0);
        assertEquals("<think>Plan first.</think>A complete answer.", stream.text.toString());
        assertTrue(stream.done.get());
        verify(stream.holder[0]).markComplete();
    }

    @Test void publishingAfterThrottledChunksIncludesEveryCharacterAndTheThinkBoundary() {
        Stream stream = new Stream();
        stream.external(new JsonObject().put("reasoning_content", "Reason"), null, Long.MAX_VALUE);
        stream.external(new JsonObject().put("content", "Answer"), null, 0);
        assertEquals("<think>Reason</think>Answer", stream.frames.getFirst().getString("streamingContent"));
        stream.external(new JsonObject().put("content", " one"), null, Long.MAX_VALUE);
        stream.external(new JsonObject().put("content", " two"), null, 0);
        assertEquals(" one two", stream.frames.getLast().getString("content"));
        assertEquals("<think>Reason</think>Answer one two", stream.text.toString());
    }

    @Test void storedStoryTextExcludesCompletedAndUnfinishedReasoning() {
        assertEquals("The door opens.", LLMBridge.stripReasoning(
                "<think>Plan first.</think>The door opens."));
        // An unfinished block is truncated reasoning. There is no reliable
        // boundary that separates it from a story reply, so drop all of it.
        assertEquals("", LLMBridge.stripReasoning(
                "<think>Plan first. The door is still opening."));
        assertEquals("", LLMBridge.stripReasoning("<think>Plan only.</think>"));
        assertEquals("Plain answer.", LLMBridge.stripReasoning("Plain answer."));
    }

    @Test void localProviderAlsoAcceptsExplicitNullContentAndReasoning() {
        Stream stream = new Stream();
        for (JsonObject chunk : List.of(new JsonObject().putNull("content").put("reasoning_content", "Plan"),
                new JsonObject().put("content", "Answer").putNull("reasoning_content"))) {
            stream.bridge.processBufferedSSEEvent(chunk.encode(), null, stream.text, stream.tokens,
                    System.currentTimeMillis(), new long[1], "stream", "typing", "now", "Assistant", null,
                    "message", "chat", false, null, new AtomicBoolean(), new JsonObject[1], new AtomicBoolean(),
                    stream.published, 0, stream.synced, stream.publishedLength, Long.MAX_VALUE, stream.opened, stream.closed);
        }
        assertEquals("<think>Plan</think>Answer", stream.text.toString());
    }
}
