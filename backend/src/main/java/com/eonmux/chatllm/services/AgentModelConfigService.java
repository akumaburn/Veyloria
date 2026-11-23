package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.AgentModelConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@ApplicationScoped
@Slf4j
public class AgentModelConfigService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;

    public Uni<AgentModelConfig> getConfig(String conversationId) {
        String query = "SELECT * FROM agent_model_config WHERE conversationId = ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                if (rows.size() > 0) {
                    return mapRowToConfig(rows.iterator().next());
                }
                // Return default config
                return AgentModelConfig.builder()
                    .conversationId(UUID.fromString(conversationId))
                    .defaultModel("")
                    .build();
            });
    }

    public Uni<AgentModelConfig> updateConfig(String conversationId, String defaultModel,
                                                  String highlighterModel, String museModel,
                                                  String narratorModel, String accountantModel,
                                                  String skepticModel) {
        return updateConfig(conversationId, defaultModel, highlighterModel, museModel,
            narratorModel, accountantModel, skepticModel, null);
    }

    public Uni<AgentModelConfig> updateConfig(String conversationId, String defaultModel,
                                                  String highlighterModel, String museModel,
                                                  String narratorModel, String accountantModel,
                                                  String skepticModel, String casterModel) {
        String id = UUID.randomUUID().toString();
        String upsert = """
            INSERT INTO agent_model_config (configId, conversationId, defaultModel, highlighterModel, museModel, narratorModel, accountantModel, skepticModel, casterModel)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE defaultModel = VALUES(defaultModel), highlighterModel = VALUES(highlighterModel),
                                    museModel = VALUES(museModel), narratorModel = VALUES(narratorModel),
                                    accountantModel = VALUES(accountantModel), skepticModel = VALUES(skepticModel),
                                    casterModel = VALUES(casterModel)
            """;
        return pool.preparedQuery(upsert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(conversationId).addValue(defaultModel != null ? defaultModel : "")
                .addValue(highlighterModel).addValue(museModel).addValue(narratorModel)
                .addValue(accountantModel).addValue(skepticModel).addValue(casterModel))
            .chain(v -> getConfig(conversationId));
    }

    private AgentModelConfig mapRowToConfig(Row row) {
        AgentModelConfig c = new AgentModelConfig();
        c.setConfigId(UUID.fromString(row.getString("configId")));
        c.setConversationId(UUID.fromString(row.getString("conversationId")));
        c.setDefaultModel(row.getString("defaultModel"));
        c.setHighlighterModel(row.getString("highlighterModel"));
        c.setMuseModel(row.getString("museModel"));
        c.setNarratorModel(row.getString("narratorModel"));
        c.setAccountantModel(row.getString("accountantModel"));
        c.setSkepticModel(row.getString("skepticModel"));
        c.setCasterModel(row.getString("casterModel"));
        c.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        c.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return c;
    }
}
