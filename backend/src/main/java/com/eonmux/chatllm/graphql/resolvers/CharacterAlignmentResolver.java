package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.models.CharacterAlignment;
import com.eonmux.chatllm.services.CharacterAlignmentService;
import com.eonmux.chatllm.utils.ActorRules;
import com.eonmux.chatllm.utils.AlignmentRules;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@GraphQLApi
@ApplicationScoped
public class CharacterAlignmentResolver {
    @Inject GraphQLAuthContext authContext;
    @Inject CharacterAlignmentService service;

    @Query
    public @NonNull CompletableFuture<List<@NonNull CharacterAlignment>> characterAlignments() {
        return service.list(authContext.requireAuth().getUserId().toString())
                .toCompletionStage().toCompletableFuture();
    }

    @Mutation("setCharacterAlignment")
    public @NonNull CompletableFuture<CharacterAlignment> setCharacterAlignment(
            @Name("characterId") String characterId,
            @Name("lawfulness") int lawfulness,
            @Name("goodness") int goodness,
            @Name("reason") String reason,
            @Name("requestId") @NonNull String requestId) throws GraphQLException {
        try {
            String actor = ActorRules.characterId(characterId);
            AlignmentRules.validatePoints(lawfulness);
            AlignmentRules.validatePoints(goodness);
            String key = requestKey(requestId);
            String note = note(reason);
            return service.set(owner(), actor, lawfulness, goodness, note, key)
                    .recover(error -> io.vertx.core.Future.failedFuture(friendly(error)))
                    .toCompletionStage().toCompletableFuture();
        } catch (IllegalArgumentException error) {
            throw new GraphQLException(error.getMessage());
        }
    }

    @Mutation
    public @NonNull CompletableFuture<CharacterAlignment> adjustCharacterAlignment(
            @Name("characterId") String characterId,
            @Name("lawfulnessDelta") int lawfulnessDelta,
            @Name("goodnessDelta") int goodnessDelta,
            @Name("reason") String reason,
            @Name("requestId") @NonNull String requestId) throws GraphQLException {
        try {
            String actor = ActorRules.characterId(characterId);
            AlignmentRules.validateDelta(lawfulnessDelta);
            AlignmentRules.validateDelta(goodnessDelta);
            if (lawfulnessDelta == 0 && goodnessDelta == 0)
                throw new IllegalArgumentException("Choose at least one alignment to change.");
            String key = requestKey(requestId);
            String note = note(reason);
            return service.adjust(owner(), actor, lawfulnessDelta, goodnessDelta, note, key)
                    .recover(error -> io.vertx.core.Future.failedFuture(friendly(error)))
                    .toCompletionStage().toCompletableFuture();
        } catch (IllegalArgumentException error) {
            throw new GraphQLException(error.getMessage());
        }
    }

    private String owner() { return authContext.requireAuth().getUserId().toString(); }

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
                ? error.getMessage() : "Unable to save this alignment change. Please retry.");
    }
}
