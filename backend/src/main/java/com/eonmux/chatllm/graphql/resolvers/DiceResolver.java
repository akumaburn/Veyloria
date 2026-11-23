package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.config.RoleplayEventAddresses;
import com.eonmux.chatllm.models.DiceRoll;
import com.eonmux.chatllm.models.inputs.*;
import com.eonmux.chatllm.services.DiceService;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
/**
 * GraphQL resolver for dice rolling operations.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class DiceResolver {

    @Inject
    Vertx vertx;
    @Inject
    Pool mysqlPool;
    @Inject
    DiceService diceService;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    jakarta.enterprise.inject.Instance<io.smallrye.graphql.api.Context> graphqlInputContext;

    @Inject
    com.eonmux.chatllm.utils.ServiceHelper serviceHelper;

    /**
     * Get dice roll history for a conversation.
     * Schema: diceHistory(conversationId: ID!, limit: Int): [DiceRoll!]! @auth
     */
    @Query
    public Uni<List<DiceRoll>> diceHistory(@Name("conversationId") String conversationId, @Name("limit") Integer limit) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return serviceHelper.verifyConversationAccess(conversationId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                }
                return diceService.getHistory(conversationId, limit);
            })
            .onFailure().invoke(err -> log.error("Failed to fetch dice history for conversation {}", conversationId, err));
    }

    /**
     * Roll dice and save the result.
     * Schema: rollDice(input: RollDiceInput!): DiceRoll! @auth
     */
    @Mutation
    public Uni<DiceRoll> rollDice(@Name("input") RollDiceInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String characterId = (String) input.get("characterId");
        String expression = (String) input.get("expression");
        String purpose = (String) input.get("purpose");
        Boolean isHidden = (Boolean) input.get("isHidden");

        return serviceHelper.verifyConversationAccess(conversationId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                }
                // The roll is attributed to a character; the caller must not
                // roll AS another user's character. Accept the character only
                // if it is an active member of this conversation, or owned by
                // the caller, or public.
                if (characterId != null) {
                    return verifyCharacterRollAccess(conversationId, characterId, userId);
                }
                return Uni.createFrom().item(true);
            })
            .chain(ok -> diceService.rollAndSave(conversationId, characterId, userId, expression, purpose, isHidden))
            .map(roll -> {
                // Publish to event bus for real-time updates
                JsonObject eventData = new JsonObject()
                    .put("rollId", roll.getRollId().toString())
                    .put("conversationId", conversationId)
                    .put("characterId", characterId)
                    .put("expression", expression)
                    .put("results", roll.getResults())
                    .put("total", roll.getTotal())
                    .put("modifier", roll.getModifier() != null ? roll.getModifier() : 0)
                    .put("purpose", purpose)
                    .put("isHidden", roll.getIsHidden() != null ? roll.getIsHidden() : false);

                vertx.eventBus().publish(RoleplayEventAddresses.DICE_ROLLED + conversationId, eventData);

                return roll;
            })
            .onFailure().invoke(err -> log.error("Failed to roll dice for conversation {}", conversationId, err));
    }

    /**
     * Verify a character may be used for a dice roll in this conversation:
     * it must be an active member of the conversation, or owned by the caller,
     * or a public character. Otherwise a user could roll dice attributed to
     * another user's character.
     */
    private Uni<Boolean> verifyCharacterRollAccess(String conversationId, String characterId, String userId) {
        String query = """
            SELECT COUNT(*) as count
            FROM `character` c
            LEFT JOIN conversation_character cc
                ON cc.conversationId = ? AND cc.characterId = c.characterUUID AND cc.isActive = true
            WHERE c.characterUUID = ?
              AND (cc.characterId IS NOT NULL OR c.ownerId = ? OR c.isPublic = true)
            """;
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversationId, characterId, userId))
            .map(rows -> rows.iterator().next().getLong("count") > 0)
            .map(valid -> {
                if (!valid) {
                    throw new RuntimeException("Character not found in conversation or not accessible");
                }
                return true;
            });
    }
}
