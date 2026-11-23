package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.Conversation;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.models.inputs.*;
import com.eonmux.chatllm.services.GroupChatService;
import com.eonmux.chatllm.services.OpeningCastService;
import com.eonmux.chatllm.services.WorldStateService;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
/**
 * GraphQL resolver for roleplay conversation operations.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class RoleplayResolver {

    @Inject
    Vertx vertx;
    @Inject
    Pool mysqlPool;
    @Inject
    WorldStateService worldStateService;
    @Inject
    OpeningCastService openingCastService;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    jakarta.enterprise.inject.Instance<io.smallrye.graphql.api.Context> graphqlInputContext;

    /**
     * Create a roleplay conversation with characters and optional configuration.
     * Schema: createRoleplayConversation(input: CreateRoleplayConversationInput!): Conversation! @auth
     */
    @Mutation
    public Uni<Conversation> createRoleplayConversation(@Name("input") CreateRoleplayConversationInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String title = (String) input.get("title");
        String description = (String) input.get("description");
        @SuppressWarnings("unchecked")
        List<String> characterIds = (List<String>) input.get("characterIds");
        String rpMode = (String) input.get("rpMode");
        String storyTemplateId = (String) input.get("templateId");
        Boolean agentPipelineEnabled = (Boolean) input.get("agentPipelineEnabled");
        String turnMode = (String) input.get("turnMode");
        @SuppressWarnings("unchecked")
        Map<String, Object> agentModels = (Map<String, Object>) input.get("agentModels");
        String userId = auth.getUserId().toString();

        // Step 1: Create conversation
        String conversationId = UUID.randomUUID().toString();
        String insertConvQuery = """
            INSERT INTO conversation (conversationGUID, ownerId, title, description, isGroupChat, isRoleplay, rpMode,
                                     agentPipelineEnabled, storyTemplateId, turnMode, groupState, active)
            VALUES (?, ?, ?, ?, ?, TRUE, ?, ?, ?, ?, 'WAITING_FOR_USER', TRUE)
            """;

        // Roleplay conversations always use the group chat path (even with 1 character)
        boolean isGroupChat = characterIds != null && !characterIds.isEmpty();
        String finalTurnMode = turnMode != null ? turnMode : "ROUND_ROBIN";
        String finalRpMode = rpMode != null ? rpMode : "narrative";

        return mysqlPool.preparedQuery(insertConvQuery)
            .execute(Tuple.tuple()
                .addValue(conversationId).addValue(userId).addValue(title).addValue(description)
                .addValue(isGroupChat).addValue(finalRpMode).addValue(agentPipelineEnabled)
                .addValue(storyTemplateId).addValue(finalTurnMode))
            .chain(convResult -> {
                // Step 2: Add characters to conversation
                if (characterIds != null && !characterIds.isEmpty()) {
                    String openingScenario = (title == null ? "" : title)
                            + (description == null ? "" : "\n" + description);
                    return addCharactersToConversation(conversationId, userId, characterIds, storyTemplateId,
                            agentModels, openingScenario);
                } else if (storyTemplateId != null) {
                    return initializeTemplateAndComplete(conversationId, userId, title, description, storyTemplateId,
                                                          finalRpMode, finalTurnMode);
                } else {
                    return Uni.createFrom().item(completeConversationCreation(conversationId, userId, title, description,
                                               finalRpMode, finalTurnMode));
                }
            });
    }

    private void fireSessionInit(String conversationId, String userId) {
        // Fire async session initialization (pipeline runs in background)
        vertx.eventBus().send("roleplay.session.init",
            new io.vertx.core.json.JsonObject()
                .put("conversationId", conversationId)
                .put("userId", userId));
    }

    private Uni<Conversation> addCharactersToConversation(String conversationId, String userId,
                                            List<String> characterIds, String storyTemplateId,
                                            Map<String, Object> agentModels, String openingScenario) {
        // Insert all characters in sequence. The first few are a provisional
        // cast; OpeningCastService replaces them with the group that fits the
        // scenario. Keeping the provisional marks makes creation safe if the
        // selection query fails.
        Uni<Void> insertChain = Uni.createFrom().voidItem();
        int[] turnOrder = {1};

        for (String characterId : characterIds) {
            final int order = turnOrder[0]++;
            boolean introduced = order <= GroupChatService.INITIAL_CAST_SIZE;
            String insertCharQuery = """
                INSERT INTO conversation_character
                    (id, conversationId, characterId, turnOrder, isActive, isIntroduced, introducedAt)
                VALUES (?, ?, ?, ?, TRUE, ?, ?)
                """;
            insertChain = insertChain.chain(v ->
                mysqlPool.preparedQuery(insertCharQuery)
                    .execute(Tuple.of(UUID.randomUUID().toString(), conversationId, characterId, order,
                            introduced, introduced ? java.time.LocalDateTime.now(java.time.ZoneOffset.UTC) : null))
                    .replaceWithVoid());
        }

        return insertChain
            .chain(v -> openingCastService.selectOpeningCast(conversationId, userId, openingScenario,
                    storyTemplateId, GroupChatService.INITIAL_CAST_SIZE))
            .chain(v -> {
                if (storyTemplateId != null) {
                    return worldStateService.initializeFromTemplate(conversationId, storyTemplateId)
                        .chain(ws -> {
                            if (agentModels != null) {
                                return addAgentModels(conversationId, agentModels);
                            }
                            return retrieveAndCompleteConversation(conversationId);
                        });
                } else if (agentModels != null) {
                    return addAgentModels(conversationId, agentModels);
                } else {
                    return retrieveAndCompleteConversation(conversationId);
                }
            });
    }

    private Uni<Conversation> addAgentModels(String conversationId, Map<String, Object> agentModels) {
        String defaultModel = (String) agentModels.getOrDefault("defaultModel", "default");
        String highlighterModel = (String) agentModels.get("highlighterModel");
        String museModel = (String) agentModels.get("museModel");
        String narratorModel = (String) agentModels.get("narratorModel");
        String accountantModel = (String) agentModels.get("accountantModel");
        String skepticModel = (String) agentModels.get("skepticModel");
        String casterModel = (String) agentModels.get("casterModel");

        String insertQuery = """
            INSERT INTO agent_model_config (configId, conversationId, defaultModel, highlighterModel,
                                           museModel, narratorModel, accountantModel, skepticModel, casterModel)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE defaultModel = VALUES(defaultModel),
                highlighterModel = VALUES(highlighterModel), museModel = VALUES(museModel),
                narratorModel = VALUES(narratorModel), accountantModel = VALUES(accountantModel),
                skepticModel = VALUES(skepticModel), casterModel = VALUES(casterModel)
            """;

        return mysqlPool.preparedQuery(insertQuery)
            .execute(Tuple.tuple()
                .addValue(UUID.randomUUID().toString()).addValue(conversationId).addValue(defaultModel)
                .addValue(highlighterModel).addValue(museModel).addValue(narratorModel)
                .addValue(accountantModel).addValue(skepticModel).addValue(casterModel))
            .chain(v -> retrieveAndCompleteConversation(conversationId));
    }

    private Uni<Conversation> initializeTemplateAndComplete(String conversationId, String userId, String title,
                                              String description, String storyTemplateId, String rpMode,
                                              String turnMode) {
        return worldStateService.initializeFromTemplate(conversationId, storyTemplateId)
            .map(worldState -> completeConversationCreation(conversationId, userId, title, description,
                                                                 rpMode, turnMode));
    }

    private Uni<Conversation> retrieveAndCompleteConversation(String conversationId) {
        String query = "SELECT * FROM conversation WHERE conversationGUID = ?";
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                if (rows.size() > 0) {
                    var row = rows.iterator().next();
                    String convIdStr = row.getString("conversationGUID");
                    String ownerIdStr = row.getString("ownerId");

                    return Conversation.builder()
                        .id(convIdStr != null ? UUID.fromString(convIdStr) : null)
                        .ownerId(ownerIdStr != null ? UUID.fromString(ownerIdStr) : null)
                        .title(row.getString("title"))
                        .description(row.getString("description"))
                        .isGroupChat(row.getBoolean("isGroupChat"))
                        .isRoleplay(row.getBoolean("isRoleplay"))
                        .rpMode(row.getString("rpMode"))
                        .agentPipelineEnabled(row.getBoolean("agentPipelineEnabled"))
                        .turnMode(row.getString("turnMode"))
                        .groupState(row.getString("groupState"))
                        .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                        .build();
                } else {
                    throw new RuntimeException("Failed to retrieve created conversation");
                }
            })
            .invoke(conversation -> {
                // Fire async session initialization if agent pipeline is enabled.
                // Delayed to allow frontend to navigate and establish WebSocket subscriptions.
                UUID conversationIdVal = conversation.getId();
                Boolean agentPipelineEnabled = conversation.getAgentPipelineEnabled();
                Boolean isRoleplay = conversation.getIsRoleplay();
                if (Boolean.TRUE.equals(agentPipelineEnabled) && Boolean.TRUE.equals(isRoleplay)) {
                    UUID ownerId = conversation.getOwnerId();
                    String convIdString = conversationIdVal != null ? conversationIdVal.toString() : null;
                    String ownerIdString = ownerId != null ? ownerId.toString() : null;
                    mysqlPool.preparedQuery(
                        "UPDATE conversation SET groupState = 'INITIALIZING' WHERE conversationGUID = ?")
                        .execute(Tuple.of(convIdString))
                        .subscribe().with(v -> {
                            vertx.eventBus().publish("group:" + convIdString + ":state-changed",
                                new io.vertx.core.json.JsonObject()
                                    .put("conversationId", convIdString)
                                    .put("groupState", "INITIALIZING"));
                            vertx.setTimer(3000, tid ->
                                fireSessionInit(convIdString, ownerIdString));
                        }, err -> log.warn("Failed to set INITIALIZING state", err));
                }
            });
    }

    private Conversation completeConversationCreation(String conversationId, String userId, String title,
                                            String description, String rpMode, String turnMode) {
        return Conversation.builder()
            .id(UUID.fromString(conversationId))
            .ownerId(UUID.fromString(userId))
            .title(title)
            .description(description)
            .isRoleplay(true)
            .rpMode(rpMode)
            .turnMode(turnMode)
            .createdAt(LocalDateTime.now(ZoneOffset.UTC).toString())
            .build();
    }
}
