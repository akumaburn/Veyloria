package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.CharacterStat;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Slf4j
public class CharacterStatService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;

    public Uni<List<CharacterStat>> getStats(String conversationId, String characterId) {
        String query = "SELECT * FROM character_stat WHERE conversationId = ? AND characterId = ? ORDER BY statCategory, statName";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId, characterId))
            .map(rows -> {
                List<CharacterStat> stats = new ArrayList<>();
                for (Row row : rows) stats.add(mapRowToStat(row));
                return stats;
            });
    }

    public Uni<CharacterStat> setStat(String conversationId, String characterId, String statName,
                                          Double statValue, Double statMax, String statType,
                                          String statCategory, String textValue) {
        String id = UUID.randomUUID().toString();
        String upsert = """
            INSERT INTO character_stat (statId, conversationId, characterId, statName, statValue, statMax, statType, statCategory, textValue)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE statValue = VALUES(statValue), statMax = VALUES(statMax),
                                    statType = VALUES(statType), statCategory = VALUES(statCategory), textValue = VALUES(textValue)
            """;
        return pool.preparedQuery(upsert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(conversationId).addValue(characterId).addValue(statName)
                .addValue(statValue != null ? statValue : 0.0).addValue(statMax)
                .addValue(statType != null ? statType : "numeric")
                .addValue(statCategory).addValue(textValue))
            .chain(v -> {
                String query = "SELECT * FROM character_stat WHERE conversationId = ? AND characterId = ? AND statName = ?";
                return pool.preparedQuery(query)
                    .execute(Tuple.of(conversationId, characterId, statName))
                    .map(rows -> rows.size() > 0 ? mapRowToStat(rows.iterator().next()) : null);
            });
    }

    public Uni<Boolean> removeStat(String conversationId, String characterId, String statName) {
        String delete = "DELETE FROM character_stat WHERE conversationId = ? AND characterId = ? AND statName = ?";
        return pool.preparedQuery(delete)
            .execute(Tuple.of(conversationId, characterId, statName))
            .map(rows -> rows.rowCount() > 0);
    }

    public Uni<CharacterStat> modifyStat(String conversationId, String characterId, String statName, double delta) {
        String update = """
            UPDATE character_stat SET statValue = GREATEST(0, IF(statMax IS NOT NULL, LEAST(statMax, statValue + ?), statValue + ?))
            WHERE conversationId = ? AND characterId = ? AND statName = ?
            """;
        return pool.preparedQuery(update)
            .execute(Tuple.of(delta, delta, conversationId, characterId, statName))
            .chain(result -> {
                if (result.rowCount() == 0) {
                    // Stat doesn't exist yet — create it with delta as initial value
                    double initialValue = Math.max(0, delta);
                    log.info("[CharacterStatService] Stat '{}' not found for {}/{}, creating with value {}",
                        statName, conversationId, characterId, initialValue);
                    return setStat(conversationId, characterId, statName, initialValue, null, "numeric", "auto-tracked", null);
                }
                // Fetch the updated stat to return the full object
                return getStat(conversationId, characterId, statName);
            });
    }

    public Uni<CharacterStat> getStat(String conversationId, String characterId, String statName) {
        String query = "SELECT * FROM character_stat WHERE conversationId = ? AND characterId = ? AND statName = ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId, characterId, statName))
            .map(rows -> rows.size() > 0 ? mapRowToStat(rows.iterator().next()) : null);
    }

    private CharacterStat mapRowToStat(Row row) {
        CharacterStat s = new CharacterStat();
        s.setStatId(UUID.fromString(row.getString("statId")));
        s.setConversationId(UUID.fromString(row.getString("conversationId")));
        s.setCharacterId(UUID.fromString(row.getString("characterId")));
        s.setStatName(row.getString("statName"));
        s.setStatValue(row.getDouble("statValue"));
        s.setStatMax(row.getDouble("statMax"));
        s.setStatType(row.getString("statType"));
        s.setStatCategory(row.getString("statCategory"));
        s.setTextValue(row.getString("textValue"));
        s.setIsVisible(row.getBoolean("isVisible"));
        s.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        s.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return s;
    }
}
