package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.AgentModelConfig;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.models.AgentRun;
import com.eonmux.chatllm.models.inputs.*;
import com.eonmux.chatllm.services.auth.AuthContext;
import com.eonmux.chatllm.utils.ServiceHelper;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
/**
 * GraphQL resolver for agent pipeline configuration and run history.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class AgentConfigResolver {

    @Inject
    Vertx vertx;
    @Inject
    Pool mysqlPool;
    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    jakarta.enterprise.inject.Instance<io.smallrye.graphql.api.Context> graphqlInputContext;


    /**
     * Get agent model configuration for a conversation.
     * Schema: agentModelConfig(conversationId: ID!): AgentModelConfig!
     */
    @Query
    public Uni<AgentModelConfig> agentModelConfig(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String verifyQuery = "SELECT COUNT(*) as count FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
        return mysqlPool.preparedQuery(verifyQuery)
            .execute(Tuple.of(conversationId, auth.getUserId().toString()))
            .chain(verifyRows -> {
                Long count = verifyRows.iterator().next().getLong("count");
                if (count == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or unauthorized"));
                }

                String query = "SELECT * FROM agent_model_config WHERE conversationId = ?";
                return mysqlPool.preparedQuery(query)
                    .execute(Tuple.of(conversationId))
                    .map(rows -> {
                        if (rows.size() > 0) {
                            var row = rows.iterator().next();
                            String configIdStr = row.getString("configId");
                            String convIdStr = row.getString("conversationId");
                            return AgentModelConfig.builder()
                                .configId(configIdStr != null ? UUID.fromString(configIdStr) : null)
                                .conversationId(convIdStr != null ? UUID.fromString(convIdStr) : null)
                                .defaultModel(row.getString("defaultModel"))
                                .highlighterModel(row.getString("highlighterModel"))
                                .museModel(row.getString("museModel"))
                                .narratorModel(row.getString("narratorModel"))
                                .accountantModel(row.getString("accountantModel"))
                                .skepticModel(row.getString("skepticModel"))
                                .casterModel(row.getString("casterModel"))
                                .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                                .updatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")))
                                .build();
                        } else {
                            return AgentModelConfig.builder()
                                .conversationId(UUID.fromString(conversationId))
                                .defaultModel("default")
                                .build();
                        }
                    });
            });
    }

    /**
     * Update agent model configuration for a conversation.
     * Schema: updateAgentModelConfig(conversationId: ID!, input: AgentModelConfigInput!): AgentModelConfig!
     */
    @Mutation
    public Uni<AgentModelConfig> updateAgentModelConfig(@Name("conversationId") String conversationId, @Name("input") AgentModelConfigInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);

        String verifyQuery = "SELECT COUNT(*) as count FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
        return mysqlPool.preparedQuery(verifyQuery)
            .execute(Tuple.of(conversationId, auth.getUserId().toString()))
            .chain(verifyRows -> {
                Long count = verifyRows.iterator().next().getLong("count");
                if (count == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or unauthorized"));
                }

                String configId = UUID.randomUUID().toString();
                String upsertQuery = """
                    INSERT INTO agent_model_config
                    (configId, conversationId, defaultModel, highlighterModel, museModel, narratorModel, accountantModel, skepticModel, casterModel)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                    defaultModel = VALUES(defaultModel),
                    highlighterModel = VALUES(highlighterModel),
                    museModel = VALUES(museModel),
                    narratorModel = VALUES(narratorModel),
                    accountantModel = VALUES(accountantModel),
                    skepticModel = VALUES(skepticModel),
                    casterModel = VALUES(casterModel),
                    updatedAt = CURRENT_TIMESTAMP
                    """;

                return mysqlPool.preparedQuery(upsertQuery)
                    .execute(Tuple.tuple()
                        .addValue(configId)
                        .addValue(conversationId)
                        .addValue(input.get("defaultModel"))
                        .addValue(input.get("highlighterModel"))
                        .addValue(input.get("museModel"))
                        .addValue(input.get("narratorModel"))
                        .addValue(input.get("accountantModel"))
                        .addValue(input.get("skepticModel"))
                        .addValue(input.get("casterModel")))
                    .chain(result -> {
                        String selectQuery = "SELECT * FROM agent_model_config WHERE conversationId = ?";
                        return mysqlPool.preparedQuery(selectQuery)
                            .execute(Tuple.of(conversationId))
                            .map(rows -> {
                                if (rows.size() == 0) {
                                    throw new RuntimeException("Failed to retrieve config after upsert");
                                }
                                var row = rows.iterator().next();
                                String cfgIdStr = row.getString("configId");
                                String convIdStr = row.getString("conversationId");
                                return AgentModelConfig.builder()
                                    .configId(cfgIdStr != null ? UUID.fromString(cfgIdStr) : null)
                                    .conversationId(convIdStr != null ? UUID.fromString(convIdStr) : null)
                                    .defaultModel(row.getString("defaultModel"))
                                    .highlighterModel(row.getString("highlighterModel"))
                                    .museModel(row.getString("museModel"))
                                    .narratorModel(row.getString("narratorModel"))
                                    .accountantModel(row.getString("accountantModel"))
                                    .skepticModel(row.getString("skepticModel"))
                                    .casterModel(row.getString("casterModel"))
                                    .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                                    .updatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")))
                                    .build();
                            });
                    });
            });
    }

    /**
     * Get agent pipeline run history for a conversation.
     * Schema: agentRuns(conversationId: ID!, limit: Int): [AgentRun!]!
     */
    @Query
    public Uni<List<AgentRun>> agentRuns(@Name("conversationId") String conversationId, @Name("limit") Integer limit) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 50;

        String verifyQuery = "SELECT COUNT(*) as count FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
        return mysqlPool.preparedQuery(verifyQuery)
            .execute(Tuple.of(conversationId, auth.getUserId().toString()))
            .chain(verifyRows -> {
                Long count = verifyRows.iterator().next().getLong("count");
                if (count == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or unauthorized"));
                }

                String query = "SELECT * FROM agent_run WHERE conversationId = ? ORDER BY createdAt DESC LIMIT ?";
                return mysqlPool.preparedQuery(query)
                    .execute(Tuple.of(conversationId, effectiveLimit))
                    .map(rows -> {
                        List<AgentRun> runs = new ArrayList<>();
                        rows.forEach(row -> {
                            String runIdStr = row.getString("runId");
                            String convIdStr = row.getString("conversationId");
                            String triggerMsgIdStr = row.getString("triggerMessageId");

                            JsonObject output = ServiceHelper.safeGetJsonObject(row, "output");

                            AgentRun run = AgentRun.builder()
                                .runId(runIdStr != null ? UUID.fromString(runIdStr) : null)
                                .conversationId(convIdStr != null ? UUID.fromString(convIdStr) : null)
                                .triggerMessageId(triggerMsgIdStr != null ? UUID.fromString(triggerMsgIdStr) : null)
                                .agentName(row.getString("agentName"))
                                .agentPass(row.getString("agentPass"))
                                .modelUsed(row.getString("modelUsed"))
                                .inputTokens(row.getInteger("inputTokens"))
                                .outputTokens(row.getInteger("outputTokens"))
                                .durationMs(row.getInteger("durationMs"))
                                .output(output)
                                .status(row.getString("status"))
                                .retryCount(row.getInteger("retryCount"))
                                .errorMessage(row.getString("errorMessage"))
                                .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                                .build();

                            runs.add(run);
                        });
                        return runs;
                    });
            });
    }
}
