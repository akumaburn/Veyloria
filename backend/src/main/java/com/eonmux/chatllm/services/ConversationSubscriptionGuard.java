package com.eonmux.chatllm.services;

import com.eonmux.chatllm.utils.ServiceHelper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.converters.multi.MultiReactorConverters;
import io.smallrye.mutiny.converters.uni.UniReactorConverters;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.function.Supplier;

/** Keeps conversation subscriptions subject to membership for their entire lifetime. */
@ApplicationScoped
@Slf4j
public class ConversationSubscriptionGuard {
    @Inject Vertx vertx;
    @Inject ServiceHelper serviceHelper;

    public <T> Multi<T> multi(String conversationId, String userId, Supplier<Multi<T>> source) {
        return Multi.createFrom().converter(MultiReactorConverters.fromFlux(),
                flux(conversationId, userId, () -> source.get().convert().with(MultiReactorConverters.toFlux())));
    }

    public <T> Flux<T> flux(String conversationId, String userId, Supplier<Publisher<T>> source) {
        return Flux.defer(() -> {
            // takeUntilOther subscribes to this before the access query. A removal
            // during authorization therefore cannot slip between the check and listener registration.
            Flux<Object> revoked = Flux.create(sink -> {
                var left = vertx.eventBus().<JsonObject>consumer("participant.left." + conversationId);
                var deleted = vertx.eventBus().consumer("conversation.deleted." + conversationId);
                left.handler(message -> {
                    if (userId.equals(message.body().getString("userId"))) sink.next(Boolean.TRUE);
                });
                deleted.handler(message -> sink.next(Boolean.TRUE));
                sink.onDispose(() -> {
                    left.unregister().subscribe().with(v -> {}, error -> log.warn("Failed to unregister membership listener", error));
                    deleted.unregister().subscribe().with(v -> {}, error -> log.warn("Failed to unregister deletion listener", error));
                });
            });
            return serviceHelper.verifyConversationAccess(conversationId, userId)
                    .convert().with(UniReactorConverters.toMono())
                    .flatMapMany(allowed -> allowed ? Flux.from(source.get()) : Flux.<T>empty())
                    .takeUntilOther(revoked);
        });
    }
}
