package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.config.RoleplayEventAddresses;
import com.eonmux.chatllm.models.Achievement;
import com.eonmux.chatllm.utils.ServiceHelper;
import com.eonmux.chatllm.models.UserAchievement;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Slf4j
public class AchievementService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    // EventBus obtained from vertx.eventBus()

    public Uni<List<Achievement>> getAllAchievements() {
        return pool.preparedQuery("SELECT * FROM achievement ORDER BY category, tier, name")
            .execute(Tuple.tuple())
            .map(rows -> {
                List<Achievement> achievements = new ArrayList<>();
                for (Row row : rows) achievements.add(mapRowToAchievement(row));
                return achievements;
            });
    }

    public Uni<List<UserAchievement>> getUserAchievements(String userId) {
        String query = """
            SELECT ua.*, a.name as achName, a.description as achDescription, a.icon, a.category, a.tier, a.xpReward
            FROM user_achievement ua
            JOIN achievement a ON ua.achievementId = a.achievementId
            WHERE ua.userId = ? ORDER BY ua.completedAt DESC
            """;
        return pool.preparedQuery(query)
            .execute(Tuple.of(userId))
            .map(rows -> {
                List<UserAchievement> achievements = new ArrayList<>();
                for (Row row : rows) {
                    UserAchievement ua = mapRowToUserAchievement(row);
                    Achievement ach = new Achievement();
                    ach.setAchievementId(row.getString("achievementId"));
                    ach.setName(row.getString("achName"));
                    ach.setDescription(row.getString("achDescription"));
                    ach.setIcon(row.getString("icon"));
                    ach.setCategory(row.getString("category"));
                    ach.setTier(row.getString("tier"));
                    ach.setXpReward(row.getInteger("xpReward"));
                    ua.setAchievement(ach);
                    achievements.add(ua);
                }
                return achievements;
            });
    }

    public Uni<UserAchievement> awardAchievement(String userId, String achievementId, String conversationId) {
        String id = UUID.randomUUID().toString();
        String upsert = """
            INSERT INTO user_achievement (id, userId, achievementId, progress, isComplete, completedAt, conversationId)
            VALUES (?, ?, ?, 1.0, TRUE, NOW(), ?)
            ON DUPLICATE KEY UPDATE progress = 1.0, isComplete = TRUE, completedAt = COALESCE(completedAt, NOW())
            """;
        return pool.preparedQuery(upsert)
            .execute(Tuple.of(id, userId, achievementId, conversationId))
            .chain(v -> {
                // Fetch the full achievement data to publish to subscribers
                return pool.preparedQuery("SELECT * FROM achievement WHERE achievementId = ?")
                    .execute(Tuple.of(achievementId))
                    .map(rows -> {
                        UserAchievement ua = UserAchievement.builder()
                            .id(UUID.fromString(id))
                            .userId(UUID.fromString(userId))
                            .achievementId(achievementId)
                            .progress(1.0)
                            .isComplete(true)
                            .build();

                        if (rows.iterator().hasNext()) {
                            Row row = rows.iterator().next();
                            // Publish achievement unlock event for subscription
                            JsonObject event = new JsonObject()
                                .put("achievementId", achievementId)
                                .put("name", row.getString("name"))
                                .put("description", row.getString("description"))
                                .put("icon", row.getString("icon"))
                                .put("category", row.getString("category"))
                                .put("tier", row.getString("tier"))
                                .put("xpReward", row.getInteger("xpReward"))
                                .put("isHidden", row.getBoolean("isHidden"));
                            vertx.eventBus().publish(RoleplayEventAddresses.ACHIEVEMENT_UNLOCKED + userId, event);
                            log.info("Published achievement unlock event for user {}: {}", userId, row.getString("name"));
                        }

                        return ua;
                    });
            });
    }

    public Uni<Void> updateProgress(String userId, String achievementId, double progress) {
        String upsert = """
            INSERT INTO user_achievement (id, userId, achievementId, progress)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE progress = GREATEST(progress, VALUES(progress)),
                                    isComplete = IF(VALUES(progress) >= 1.0, TRUE, isComplete),
                                    completedAt = IF(VALUES(progress) >= 1.0 AND completedAt IS NULL, NOW(), completedAt)
            """;
        return pool.preparedQuery(upsert)
            .execute(Tuple.of(UUID.randomUUID().toString(), userId, achievementId, progress))
            .replaceWithVoid();
    }

    private Achievement mapRowToAchievement(Row row) {
        Achievement a = new Achievement();
        a.setAchievementId(row.getString("achievementId"));
        a.setName(row.getString("name"));
        a.setDescription(row.getString("description"));
        a.setIcon(row.getString("icon"));
        a.setCategory(row.getString("category"));
        a.setTier(row.getString("tier"));
        a.setXpReward(row.getInteger("xpReward"));
        a.setTriggerCondition(ServiceHelper.safeGetJsonObject(row, "triggerCondition"));
        a.setIsHidden(row.getBoolean("isHidden"));
        a.setIsBuiltIn(row.getBoolean("isBuiltIn"));
        a.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        return a;
    }

    private UserAchievement mapRowToUserAchievement(Row row) {
        UserAchievement ua = new UserAchievement();
        ua.setId(UUID.fromString(row.getString("id")));
        ua.setUserId(UUID.fromString(row.getString("userId")));
        ua.setAchievementId(row.getString("achievementId"));
        ua.setProgress(row.getDouble("progress"));
        ua.setIsComplete(row.getBoolean("isComplete"));
        ua.setCompletedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("completedAt")));
        if (row.getString("conversationId") != null) ua.setConversationId(UUID.fromString(row.getString("conversationId")));
        ua.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        ua.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return ua;
    }
}
