package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.UserProgression;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@ApplicationScoped
@Slf4j
public class ProgressionService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;

    public Uni<UserProgression> getProgression(String userId) {
        String query = "SELECT * FROM user_progression WHERE userId = ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(userId))
            .chain(rows -> {
                if (rows.size() > 0) {
                    return Uni.createFrom().item(mapRowToProgression(rows.iterator().next()));
                }
                return createProgression(userId);
            });
    }

    private Uni<UserProgression> createProgression(String userId) {
        String insert = "INSERT INTO user_progression (userId) VALUES (?)";
        return pool.preparedQuery(insert)
            .execute(Tuple.of(userId))
            .map(v -> UserProgression.builder()
                .userId(UUID.fromString(userId))
                .totalXP(0L)
                .level(1)
                .title("Novice Storyteller")
                .build());
    }

    public Uni<UserProgression> addXP(String userId, long xp) {
        String update = "UPDATE user_progression SET totalXP = totalXP + ? WHERE userId = ?";
        return pool.preparedQuery(update)
            .execute(Tuple.of(xp, userId))
            .chain(v -> getProgression(userId))
            .chain(prog -> {
                prog.addXP(0); // Recalculate level from totalXP
                String levelUpdate = "UPDATE user_progression SET level = ?, title = ? WHERE userId = ?";
                return pool.preparedQuery(levelUpdate)
                    .execute(Tuple.of(prog.getLevel(), prog.getTitle(), userId))
                    .map(v -> prog);
            });
    }

    public Uni<Void> incrementMessages(String userId) {
        String update = "UPDATE user_progression SET totalMessages = totalMessages + 1 WHERE userId = ?";
        return ensureExists(userId).chain(v ->
            pool.preparedQuery(update).execute(Tuple.of(userId)).replaceWithVoid()
        );
    }

    public Uni<Void> incrementDiceRolls(String userId) {
        String update = "UPDATE user_progression SET totalDiceRolls = totalDiceRolls + 1 WHERE userId = ?";
        return ensureExists(userId).chain(v ->
            pool.preparedQuery(update).execute(Tuple.of(userId)).replaceWithVoid()
        );
    }

    private Uni<Void> ensureExists(String userId) {
        String insert = "INSERT IGNORE INTO user_progression (userId) VALUES (?)";
        return pool.preparedQuery(insert).execute(Tuple.of(userId)).replaceWithVoid();
    }

    private UserProgression mapRowToProgression(Row row) {
        UserProgression p = new UserProgression();
        p.setUserId(UUID.fromString(row.getString("userId")));
        p.setTotalXP(row.getLong("totalXP"));
        p.setLevel(row.getInteger("level"));
        p.setTitle(row.getString("title"));
        p.setStoriesCompleted(row.getInteger("storiesCompleted"));
        p.setTotalMessages(row.getInteger("totalMessages"));
        p.setTotalDiceRolls(row.getInteger("totalDiceRolls"));
        p.setTotalPlaytimeMin(row.getInteger("totalPlaytimeMin"));
        p.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        p.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return p;
    }
}
