package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.Achievement;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.models.UserAchievement;
import com.eonmux.chatllm.models.UserProgression;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Name;
/**
 * GraphQL resolver for achievement and progression operations.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class AchievementResolver {

    @Inject
    Pool mysqlPool;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    /**
     * Get all achievements, optionally filtered by category.
     * Schema: achievements(category: String): [Achievement!]! @auth
     */
    @Query
    public Uni<List<Achievement>> achievements(@Name("category") String category) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String query;
        Tuple params;

        if (category != null && !category.isEmpty()) {
            query = "SELECT * FROM achievement WHERE category = ? ORDER BY name";
            params = Tuple.of(category);
        } else {
            query = "SELECT * FROM achievement ORDER BY category, name";
            params = Tuple.tuple();
        }

        return mysqlPool.preparedQuery(query)
            .execute(params)
            .map(rows -> {
                List<Achievement> achievements = new ArrayList<>();
                rows.forEach(row -> {
                    Achievement achievement = Achievement.builder()
                        .achievementId(row.getString("achievementId"))
                        .name(row.getString("name"))
                        .description(row.getString("description"))
                        .category(row.getString("category"))
                        .icon(row.getString("icon"))
                        .tier(row.getString("tier"))
                        .xpReward(row.getInteger("xpReward"))
                        .isHidden(row.getBoolean("isHidden"))
                        .isBuiltIn(row.getBoolean("isBuiltIn"))
                        .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                        .build();
                    achievements.add(achievement);
                });
                return achievements;
            })
            .onFailure().invoke(err -> {
                log.error("Failed to fetch achievements", err);
            });
    }

    /**
     * Get achievements unlocked by a user.
     * Schema: userAchievements(userId: ID): [UserAchievement!]! @auth
     */
    @Query
    public Uni<List<UserAchievement>> userAchievements(@Name("userId") String userId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String effectiveUserId = (userId == null || userId.isEmpty())
            ? auth.getUserId().toString()
            : userId;

        // Only the user themselves (or an admin) may read another user's
        // achievements; otherwise anyone could enumerate userIds.
        if (!auth.isAdmin() && !auth.getUserId().toString().equals(effectiveUserId)) {
            return Uni.createFrom().failure(new RuntimeException("Access denied"));
        }

        String query = "SELECT ua.*, a.name AS a_name, a.description AS a_description, a.category AS a_category, " +
            "a.icon AS a_icon, a.tier AS a_tier, a.xpReward AS a_xpReward, a.isHidden AS a_isHidden " +
            "FROM user_achievement ua " +
            "JOIN achievement a ON ua.achievementId = a.achievementId " +
            "WHERE ua.userId = ? ORDER BY ua.completedAt DESC";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(effectiveUserId))
            .map(rows -> {
                List<UserAchievement> userAchievements = new ArrayList<>();
                rows.forEach(row -> {
                    Achievement achievement = Achievement.builder()
                        .achievementId(row.getString("achievementId"))
                        .name(row.getString("a_name"))
                        .description(row.getString("a_description"))
                        .category(row.getString("a_category"))
                        .icon(row.getString("a_icon"))
                        .tier(row.getString("a_tier"))
                        .xpReward(row.getInteger("a_xpReward"))
                        .isHidden(row.getBoolean("a_isHidden"))
                        .build();

                    UserAchievement ua = UserAchievement.builder()
                        .id(uuidFromString(row.getString("id")))
                        .achievementId(row.getString("achievementId"))
                        .userId(uuidFromString(row.getString("userId")))
                        .progress(row.getDouble("progress"))
                        .isComplete(row.getBoolean("isComplete"))
                        .completedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("completedAt")))
                        .conversationId(uuidFromString(row.getString("conversationId")))
                        .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                        .updatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")))
                        .achievement(achievement)
                        .build();
                    userAchievements.add(ua);
                });
                return userAchievements;
            })
            .onFailure().invoke(err -> {
                log.error("Failed to fetch user achievements for userId {}", effectiveUserId, err);
            });
    }

    /**
     * Get user progression (level, XP, etc.).
     * Schema: userProgression(userId: ID): UserProgression! @auth
     */
    @Query
    public Uni<UserProgression> userProgression(@Name("userId") String userId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String effectiveUserId = (userId == null || userId.isEmpty())
            ? auth.getUserId().toString()
            : userId;

        // Only the user themselves (or an admin) may read another user's
        // progression. This also prevents injecting a progression row into
        // another user's table via the default-entry insert below.
        if (!auth.isAdmin() && !auth.getUserId().toString().equals(effectiveUserId)) {
            return Uni.createFrom().failure(new RuntimeException("Access denied"));
        }

        String query = "SELECT * FROM user_progression WHERE userId = ?";
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(effectiveUserId))
            .chain(rows -> {
                if (rows.size() > 0) {
                    var row = rows.iterator().next();
                    UserProgression progression = UserProgression.builder()
                        .userId(uuidFromString(row.getString("userId")))
                        .totalXP(row.getLong("totalXP"))
                        .level(row.getInteger("level"))
                        .title(row.getString("title"))
                        .storiesCompleted(row.getInteger("storiesCompleted"))
                        .totalMessages(row.getInteger("totalMessages"))
                        .totalDiceRolls(row.getInteger("totalDiceRolls"))
                        .totalPlaytimeMin(row.getInteger("totalPlaytimeMin"))
                        .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                        .updatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")))
                        .build();
                    return Uni.createFrom().item(progression);
                } else {
                    // Create default progression entry
                    String insertQuery = "INSERT INTO user_progression (userId, totalXP, level, storiesCompleted, title) VALUES (?, 0, 1, 0, 'Novice')";
                    return mysqlPool.preparedQuery(insertQuery)
                        .execute(Tuple.of(effectiveUserId))
                        .map(insertResult -> UserProgression.builder()
                            .userId(uuidFromString(effectiveUserId))
                            .totalXP(0L)
                            .level(1)
                            .title("Novice")
                            .storiesCompleted(0)
                            .totalMessages(0)
                            .totalDiceRolls(0)
                            .totalPlaytimeMin(0)
                            .build());
                }
            })
            .onFailure().invoke(err -> {
                log.error("Failed to fetch user progression for userId {}", effectiveUserId, err);
            });
    }

    /**
     * Null-safe UUID conversion from String.
     */
    private static UUID uuidFromString(String str) {
        return str != null ? UUID.fromString(str) : null;
    }
}
