package com.eonmux.chatllm.services;

import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationProgressServiceTest {
    Vertx vertx;
    ConversationProgressService service;
    @BeforeEach void setup() { vertx=Vertx.vertx(); service=new ConversationProgressService(); service.vertx=vertx; }
    @AfterEach void close() { vertx.closeAndAwait(); }
    @Test void staleRunsCannotChangeOrFinishCurrentWork() {
        service.begin("conversation","old");
        service.begin("conversation","new");
        service.update("conversation","new","travel_decision","Resolving travel…");
        var current=service.get("conversation");
        service.update("conversation","old","narrator","Writing the scene…");
        service.finish("conversation","old");
        assertSame(current,service.get("conversation"));
        service.update("conversation","new","travel_decision","Resolving travel…");
        assertSame(current,service.get("conversation"),"Repeated phase updates should not publish new frames");
        service.cancel("conversation");
        service.update("conversation","new","narrator","Too late");
        assertNull(service.get("conversation"));
    }
    @Test void restartingSameActiveRunKeepsTheActualPhase() {
        service.begin("conversation","round");
        service.update("conversation","round","narrator","Writing the scene…");
        var writing=service.get("conversation");
        service.begin("conversation","round");
        assertSame(writing,service.get("conversation"));
        service.finish("conversation","round");
        assertNull(service.get("conversation"));
        service.begin("other","opening:other");
        assertTrue(service.get("other").isActive());
    }
}
