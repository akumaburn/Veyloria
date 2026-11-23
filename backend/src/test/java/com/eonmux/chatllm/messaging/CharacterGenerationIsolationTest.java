package com.eonmux.chatllm.messaging;

import com.eonmux.chatllm.config.AppConfig;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CharacterGenerationIsolationTest {
    @Test void pendingGenerationsKeepTheEventLoopResponsiveAndRepliesIsolated() throws Exception {
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        OkHttpClient client = new OkHttpClient();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String name = request.contains("Torres") ? "Maya Torres" : "Maya Chen";
            if (name.equals("Maya Torres")) {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Test response timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(error);
                }
            }
            String content = new JsonObject().put("name", name).put("systemPrompt", "You are " + name).encode();
            byte[] body = new JsonObject().put("choices", new JsonArray().add(new JsonObject()
                    .put("message", new JsonObject().put("content", content)))).encode().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            LLMBridge bridge = new LLMBridge();
            bridge.vertx = vertx;
            bridge.config = mock(AppConfig.class);
            when(bridge.config.isLlmExternalServer()).thenReturn(true);
            when(bridge.config.getLlmExternalBaseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            when(bridge.config.getLlmExternalModel()).thenReturn("test-model");
            var field = LLMBridge.class.getDeclaredField("sseHttpClient");
            field.setAccessible(true);
            field.set(bridge, client);
            var consumer = vertx.eventBus().<JsonObject>consumer("test.character.generate")
                    .handler(bridge::handleGenerateCharacter);
            consumer.completionHandlerAndAwait();
            var first = vertx.eventBus().<JsonObject>request("test.character.generate",
                    new JsonObject().put("prompt", "Create Maya Torres")).subscribeAsCompletionStage();
            assertTrue(started.await(3, TimeUnit.SECONDS), "First generation should reach the server");
            var second = vertx.eventBus().<JsonObject>request("test.character.generate",
                    new JsonObject().put("prompt", "Create Maya Chen")).subscribeAsCompletionStage();
            CountDownLatch heartbeat = new CountDownLatch(1);
            vertx.runOnContext(heartbeat::countDown);
            assertTrue(heartbeat.await(1, TimeUnit.SECONDS), "Pending HTTP generation must not block the event loop");
            release.countDown();
            assertEquals("Maya Torres", first.toCompletableFuture().get(5, TimeUnit.SECONDS).body().getString("name"));
            assertEquals("Maya Chen", second.toCompletableFuture().get(5, TimeUnit.SECONDS).body().getString("name"));
        } finally {
            release.countDown();
            server.stop(0);
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            vertx.closeAndAwait();
        }
    }
    @Test void rosterUsesOneJsonRequestAndDisablesReasoningOnlyForStructuredGeneration() throws Exception {
        Vertx vertx = Vertx.vertx();
        OkHttpClient client = new OkHttpClient();
        java.util.concurrent.atomic.AtomicReference<JsonObject> captured = new java.util.concurrent.atomic.AtomicReference<>();
        JsonArray cast = new JsonArray();
        for (int i = 0; i < 2; i++) cast.add(new JsonObject().put("name", "Resident " + i).put("role", "Harbor worker")
                .put("description", "A dock resident").put("systemPrompt", "Speak as a harbor resident.")
                .put("backstory", "Survived the siege.").put("relationships", new JsonArray().add(new JsonObject()
                        .put("name", "Resident " + (1 - i)).put("description", "Rebuilt the quay together."))));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(new JsonObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String content = new JsonObject().put("characters", cast).encode();
            byte[] response = new JsonObject().put("choices", new JsonArray().add(new JsonObject()
                    .put("message", new JsonObject().put("content", content)))).encode().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            LLMBridge bridge = new LLMBridge(); bridge.vertx = vertx; bridge.config = mock(AppConfig.class);
            when(bridge.config.isLlmExternalServer()).thenReturn(true);
            when(bridge.config.isLlmRosterDisableThinking()).thenReturn(true);
            when(bridge.config.getLlmExternalBaseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            when(bridge.config.getLlmExternalModel()).thenReturn("test-model");
            var field = LLMBridge.class.getDeclaredField("sseHttpClient"); field.setAccessible(true); field.set(bridge, client);
            var consumer = vertx.eventBus().<JsonObject>consumer("test.roster").handler(bridge::handleGenerateCharacter);
            consumer.completionHandlerAndAwait();
            JsonObject result = vertx.eventBus().<JsonObject>request("test.roster", new JsonObject()
                    .put("prompt", "A harbor after a siege with rival guilds.").put("rosterCount", 2))
                    .await().atMost(java.time.Duration.ofSeconds(5)).body();
            assertEquals(2, result.getJsonArray("characters").size());
            assertEquals("json_schema", captured.get().getJsonObject("response_format").getString("type"));
            assertEquals(2, captured.get().getJsonObject("response_format").getJsonObject("json_schema")
                    .getJsonObject("schema").getJsonObject("properties").getJsonObject("characters").getInteger("maxItems"));
            assertFalse(captured.get().getJsonObject("chat_template_kwargs").getBoolean("enable_thinking"));
            assertTrue(captured.get().getJsonArray("messages").getJsonObject(0).getString("content").contains("exactly 2"));
        } finally {
            server.stop(0); client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll(); vertx.closeAndAwait();
        }
    }

}
