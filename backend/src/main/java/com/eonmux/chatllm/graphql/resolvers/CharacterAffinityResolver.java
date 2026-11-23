package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.models.*;
import com.eonmux.chatllm.services.CharacterAffinityService;
import com.eonmux.chatllm.utils.AffinityRules;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@GraphQLApi
@ApplicationScoped
public class CharacterAffinityResolver {
    @Inject GraphQLAuthContext authContext;
    @Inject CharacterAffinityService service;

    @Query
    public @NonNull CompletableFuture<List<@NonNull RelationshipActor>> relationshipActors() {
        return service.actors(authContext.requireAuth().getUserId().toString()).toCompletionStage().toCompletableFuture();
    }
    @Query
    public @NonNull CompletableFuture<List<@NonNull CharacterAffinity>> characterAffinities() {
        return service.list(authContext.requireAuth().getUserId().toString()).toCompletionStage().toCompletableFuture();
    }
    @Mutation
    public @NonNull CompletableFuture<CharacterAffinity> reactToCharacter(
            @Name("fromCharacterId") String fromCharacterId, @Name("toCharacterId") String toCharacterId,
            @Name("reaction") @NonNull String reaction, @Name("reason") String reason,
            @Name("requestId") @NonNull String requestId) throws GraphQLException {
        String owner = authContext.requireAuth().getUserId().toString();
        try {
            String from = AffinityRules.actorId(fromCharacterId), to = AffinityRules.actorId(toCharacterId);
            AffinityRules.validatePair(from, to);
            String normalized = AffinityRules.reaction(reaction);
            String key = requestKey(requestId);
            String note = note(reason);
            return service.react(owner, from, to, normalized, note, key)
                    .recover(error -> io.vertx.core.Future.failedFuture(friendly(error)))
                    .toCompletionStage().toCompletableFuture();
        } catch (IllegalArgumentException error) { throw new GraphQLException(error.getMessage()); }
    }

    /** Sets a direction to an exact point value for tier controls and sliders. */
    @Mutation("setCharacterAffinity")
    public @NonNull CompletableFuture<CharacterAffinity> setCharacterAffinity(
            @Name("fromCharacterId") String fromCharacterId, @Name("toCharacterId") String toCharacterId,
            @Name("points") int points, @Name("reason") String reason,
            @Name("requestId") @NonNull String requestId) throws GraphQLException {
        String owner = authContext.requireAuth().getUserId().toString();
        try {
            String from = AffinityRules.actorId(fromCharacterId), to = AffinityRules.actorId(toCharacterId);
            AffinityRules.validatePair(from, to);
            AffinityRules.validatePoints(points);
            String key = requestKey(requestId);
            String note = note(reason);
            return service.set(owner, from, to, points, note, key)
                    .recover(error -> io.vertx.core.Future.failedFuture(friendly(error)))
                    .toCompletionStage().toCompletableFuture();
        } catch (IllegalArgumentException error) { throw new GraphQLException(error.getMessage()); }
    }

    private static String requestKey(String requestId) {
        if (requestId == null) throw new IllegalArgumentException("Invalid request identifier.");
        try {
            String key = UUID.fromString(requestId).toString();
            if (!key.equalsIgnoreCase(requestId)) throw new IllegalArgumentException();
            return key;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid request identifier.");
        }
    }

    private static String note(String reason) {
        if (reason == null || reason.isBlank()) return null;
        if (reason.codePointCount(0, reason.length()) > 500)
            throw new IllegalArgumentException("Keep the reason within 500 characters.");
        return reason.strip();
    }

    private static GraphQLException friendly(Throwable error) {
        return new GraphQLException(error instanceof IllegalArgumentException
                ? error.getMessage() : "Unable to save this relationship change. Please retry.");
    }
}
