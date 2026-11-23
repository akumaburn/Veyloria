package com.eonmux.chatllm.messaging;

import com.eonmux.chatllm.config.AppConfig;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentPipelineBridgeLifecycleTest {
    Vertx vertx;
    AgentPipelineBridge bridge;
    @BeforeEach void setup() {
        vertx=Vertx.vertx();
        bridge=new AgentPipelineBridge();
        bridge.vertx=vertx;
        bridge.config=mock(AppConfig.class);
    }
    @AfterEach void close() { bridge.onStop(null); vertx.closeAndAwait(); }

    @Test void reloadRemovesThePreviousPipelineHandlerBeforeTheNewOneStarts() {
        bridge.onStart(null);
        bridge.onStop(null);
        var error=assertThrows(ReplyException.class, () -> vertx.eventBus().request("roleplay.pipeline.process",new JsonObject(),
            new DeliveryOptions().setSendTimeout(500)).await().atMost(Duration.ofSeconds(2)));
        assertEquals(ReplyFailure.NO_HANDLERS,error.failureType());
        // The next application can own the address exclusively, even though
        // the same Vert.x instance (as in dev reload) remains alive.
        var current=vertx.eventBus().<JsonObject>consumer("roleplay.pipeline.process",message -> message.reply("current pipeline"));
        current.completionHandlerAndAwait();
        for(int i=0;i<8;i++) assertEquals("current pipeline",vertx.eventBus().request("roleplay.pipeline.process",new JsonObject(),
            new DeliveryOptions().setSendTimeout(500)).await().atMost(Duration.ofSeconds(2)).body());
        current.unregisterAndAwait();
    }
    @Test void shutdownIsSafeBeforeStartupAndWhenCalledRepeatedly() {
        bridge.onStop(null);
        bridge.onStart(null);
        bridge.onStop(null);
        bridge.onStop(null);
    }
}
