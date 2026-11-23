package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.services.ConversationSubscriptionGuard;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.smallrye.mutiny.Multi;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SharedMessageSubscriptionTest {
    @Test void disconnectingOneViewerDoesNotStopGenerationForOtherViewers() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            SubscriptionResolver resolver = new SubscriptionResolver();
            resolver.vertx = vertx;
            resolver.graphQLAuthContext = mock(GraphQLAuthContext.class);
            AuthContext auth = mock(AuthContext.class);
            when(auth.getUserId()).thenReturn(UUID.randomUUID());
            when(resolver.graphQLAuthContext.requireAuth()).thenReturn(auth);
            resolver.subscriptionGuard = mock(ConversationSubscriptionGuard.class);
            when(resolver.subscriptionGuard.multi(anyString(), anyString(), any()))
                    .thenAnswer(call -> call.<Supplier<Multi<Object>>>getArgument(2).get());
            AtomicBoolean stopped = new AtomicBoolean();
            vertx.eventBus().consumer("llm.stop").handler(message -> stopped.set(true));
            CompletableFuture<String> remainingViewer = new CompletableFuture<>();
            var departing = resolver.messageStream("chat").subscribe().with(v -> {});
            var remaining = resolver.messageStream("chat").subscribe().with(
                    message -> remainingViewer.complete(message.getContent()), remainingViewer::completeExceptionally);
            departing.cancel();
            vertx.setTimer(50, ignored -> vertx.eventBus().publish("conversation:chat:message-stream",
                    new JsonObject().put("messageId", "reply").put("conversationId", "chat")
                            .put("content", "still generating").put("isComplete", false)));
            assertEquals("still generating", remainingViewer.get(2, TimeUnit.SECONDS));
            assertFalse(stopped.get(), "A viewer disconnect must not issue a global stop");
            remaining.cancel();
        } finally {
            vertx.closeAndAwait();
        }
    }
}
