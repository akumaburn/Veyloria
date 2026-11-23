package com.eonmux.chatllm.services;

import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SSEStreamOrderingTest {
    @Test void completionFromTransportCannotOvertakeAnActiveTimerBatch() throws Exception {
        Vertx vertx = Vertx.vertx();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondBatchEntered = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var received = Collections.synchronizedList(new ArrayList<Integer>());
        var completions = new AtomicInteger();
        var failures = new AtomicInteger();
        try {
            var stream = new SSEStreamBuffer(vertx, "burst-order", event -> {
                int value = Integer.parseInt(event.data);
                if (value == 0) {
                    firstEntered.countDown();
                    try { assertTrue(releaseFirst.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new RuntimeException(error); }
                }
                if (value == 50) secondBatchEntered.countDown();
                received.add(value);
            }, () -> { completions.incrementAndGet(); finished.countDown(); },
                error -> { failures.incrementAndGet(); finished.countDown(); });
            for (int i = 0; i < 100; i++) stream.addEvent(Integer.toString(i));
            stream.start();
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            // OkHttp calls this outside the timer's owning Vert.x context.
            stream.markComplete();
            assertFalse(secondBatchEntered.await(150, TimeUnit.MILLISECONDS),
                "A transport-triggered drain must not process the next batch concurrently");
            assertEquals(0, completions.get());
            releaseFirst.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(java.util.stream.IntStream.range(0, 100).boxed().toList(), received);
            assertEquals(1, completions.get());
            assertEquals(0, failures.get());
            assertFalse(stream.isActive());
        } finally { releaseFirst.countDown(); vertx.closeAndAwait(); }
    }
}
