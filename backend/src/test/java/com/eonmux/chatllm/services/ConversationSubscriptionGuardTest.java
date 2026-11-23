package com.eonmux.chatllm.services;

import com.eonmux.chatllm.utils.ServiceHelper;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationSubscriptionGuardTest {
    private Vertx vertx;
    private ConversationSubscriptionGuard guard;

    @BeforeEach void setup() {
        vertx = Vertx.vertx();
        guard = new ConversationSubscriptionGuard();
        guard.vertx = vertx;
        guard.serviceHelper = mock(ServiceHelper.class);
        when(guard.serviceHelper.verifyConversationAccess("chat", "member")).thenReturn(Uni.createFrom().item(true));
    }

    @AfterEach void close() { vertx.closeAndAwait(); }

    @Test void deniedMembershipNeverOpensTheSource() {
        when(guard.serviceHelper.verifyConversationAccess("chat", "member")).thenReturn(Uni.createFrom().item(false));
        AtomicBoolean opened = new AtomicBoolean();
        assertEquals(List.of(), guard.flux("chat", "member", () -> {
            opened.set(true);
            return Flux.just("secret");
        }).collectList().block(java.time.Duration.ofSeconds(2)));
        assertFalse(opened.get());
    }

    @Test void removalClosesTheLiveStreamAndCancelsItsSource() throws Exception {
        Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
        List<String> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> ended = new CompletableFuture<>();
        guard.flux("chat", "member", source::asFlux).subscribe(received::add, ended::completeExceptionally,
                () -> ended.complete(null));
        assertEquals(Sinks.EmitResult.OK, source.tryEmitNext("before removal"));
        vertx.eventBus().publish("participant.left.chat", new JsonObject().put("userId", "member"));
        ended.get(2, TimeUnit.SECONDS);
        assertEquals(Sinks.EmitResult.FAIL_CANCELLED, source.tryEmitNext("secret after removal"));
        assertEquals(List.of("before removal"), received);
    }

    @Test void removalDuringAuthorizationCannotOpenTheSourceLater() throws Exception {
        CompletableFuture<Boolean> access = new CompletableFuture<>();
        when(guard.serviceHelper.verifyConversationAccess("chat", "member"))
                .thenReturn(Uni.createFrom().completionStage(access));
        AtomicBoolean opened = new AtomicBoolean();
        CompletableFuture<Void> ended = new CompletableFuture<>();
        guard.flux("chat", "member", () -> {
            opened.set(true);
            return Flux.just("secret");
        }).subscribe(v -> fail("Revoked stream emitted a value"), ended::completeExceptionally,
                () -> ended.complete(null));
        vertx.eventBus().publish("participant.left.chat", new JsonObject().put("userId", "member"));
        ended.get(2, TimeUnit.SECONDS);
        access.complete(true);
        assertFalse(opened.get());
    }

    @Test void deletionClosesAMutinyStreamToo() throws Exception {
        CompletableFuture<Void> ended = new CompletableFuture<>();
        guard.multi("chat", "member", () -> io.smallrye.mutiny.Multi.createFrom().nothing())
                .subscribe().with(v -> fail("Unexpected item"), ended::completeExceptionally, () -> ended.complete(null));
        vertx.eventBus().publish("conversation.deleted.chat", new JsonObject());
        ended.get(2, TimeUnit.SECONDS);
    }

    @Test void anotherParticipantsRemovalDoesNotRevokeThisMember() throws Exception {
        Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
        CompletableFuture<String> received = new CompletableFuture<>();
        var subscription = guard.flux("chat", "member", source::asFlux)
                .subscribe(received::complete, received::completeExceptionally);
        vertx.eventBus().publish("participant.left.chat", new JsonObject().put("userId", "someone-else"));
        vertx.setTimer(50, ignored -> source.tryEmitNext("still a member"));
        assertEquals("still a member", received.get(2, TimeUnit.SECONDS));
        subscription.dispose();
    }
}
