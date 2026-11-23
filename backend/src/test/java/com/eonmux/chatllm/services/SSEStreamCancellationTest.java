package com.eonmux.chatllm.services;

import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SSEStreamCancellationTest {
    @Test void abortTerminatesOnceAndDropsLateTransportEvents() {
        Vertx vertx=Vertx.vertx();
        try {
            var failures=new AtomicInteger();var completions=new AtomicInteger();
            var stream=new SSEStreamBuffer(vertx,"chat",data->fail("Cancelled content must not be delivered"),
                    completions::incrementAndGet,error->{assertInstanceOf(CancellationException.class,error);failures.incrementAndGet();});
            stream.start();
            stream.abort(new CancellationException("Replaced by a new player turn"));
            stream.abort(new CancellationException("Repeated stop"));
            stream.addEvent("late data");
            stream.markComplete();
            assertFalse(stream.isActive());
            assertEquals(1,failures.get());
            assertEquals(0,completions.get());
            assertEquals(0,stream.getStats().getInteger("pending"));
        } finally {vertx.closeAndAwait();}
    }
}
