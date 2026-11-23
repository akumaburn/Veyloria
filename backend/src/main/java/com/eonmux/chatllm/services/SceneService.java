package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.Scene;
import com.eonmux.chatllm.utils.ServiceHelper;
import com.eonmux.chatllm.models.SceneCharacter;
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
public class SceneService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;

    public Uni<Scene> createScene(String conversationId, String name, String location, String description) {
        String id = UUID.randomUUID().toString();
        // End any currently active scenes
        String deactivate = "UPDATE scene SET isActive = FALSE, endedAt = NOW() WHERE conversationId = ? AND isActive = TRUE";
        return pool.preparedQuery(deactivate)
            .execute(Tuple.of(conversationId))
            .chain(v -> {
                String insert = """
                    INSERT INTO scene (sceneId, conversationId, name, location, description)
                    VALUES (?, ?, ?, ?, ?)
                    """;
                return pool.preparedQuery(insert)
                    .execute(Tuple.of(id, conversationId, name, location, description));
            })
            .map(v -> Scene.builder()
                .sceneId(UUID.fromString(id))
                .conversationId(UUID.fromString(conversationId))
                .name(name)
                .location(location)
                .description(description)
                .isActive(true)
                .build());
    }

    /**
     * Activates an existing scene by deactivating all others in the conversation
     * and setting the target scene as active.
     */
    public Uni<Scene> activateScene(String conversationId, String sceneId) {
        String deactivate = "UPDATE scene SET isActive = FALSE, endedAt = NOW() WHERE conversationId = ? AND isActive = TRUE AND sceneId != ?";
        return pool.preparedQuery(deactivate)
            .execute(Tuple.of(conversationId, sceneId))
            .chain(v -> {
                String activate = "UPDATE scene SET isActive = TRUE, endedAt = NULL WHERE sceneId = ? AND conversationId = ?";
                return pool.preparedQuery(activate)
                    .execute(Tuple.of(sceneId, conversationId));
            })
            .chain(v -> pool.preparedQuery("SELECT * FROM scene WHERE sceneId = ? AND conversationId = ?")
                .execute(Tuple.of(sceneId, conversationId))
                .map(rows -> {
                    if (rows.size() == 0) throw new RuntimeException("Scene not found: " + sceneId);
                    return mapRowToScene(rows.iterator().next());
                }));
    }

    public Uni<Scene> getActiveScene(String conversationId) {
        String query = "SELECT * FROM scene WHERE conversationId = ? AND isActive = TRUE ORDER BY createdAt DESC LIMIT 1";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                if (rows.size() == 0) return null;
                return mapRowToScene(rows.iterator().next());
            });
    }

    public Uni<Void> addCharacterToScene(String sceneId, String characterId) {
        String id = UUID.randomUUID().toString();
        String insert = """
            INSERT INTO scene_character (id, sceneId, characterId)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE leftAt = NULL
            """;
        return pool.preparedQuery(insert)
            .execute(Tuple.of(id, sceneId, characterId))
            .replaceWithVoid();
    }

    public Uni<Void> removeCharacterFromScene(String sceneId, String characterId) {
        String update = "UPDATE scene_character SET leftAt = NOW() WHERE sceneId = ? AND characterId = ? AND leftAt IS NULL";
        return pool.preparedQuery(update)
            .execute(Tuple.of(sceneId, characterId))
            .replaceWithVoid();
    }

    public Uni<List<String>> getCharactersInScene(String sceneId) {
        String query = "SELECT characterId FROM scene_character WHERE sceneId = ? AND leftAt IS NULL";
        return pool.preparedQuery(query)
            .execute(Tuple.of(sceneId))
            .map(rows -> {
                List<String> ids = new ArrayList<>();
                for (Row row : rows) ids.add(row.getString("characterId"));
                return ids;
            });
    }

    public Uni<String> getCharacterScene(String conversationId, String characterId) {
        String query = """
            SELECT s.sceneId FROM scene s
            JOIN scene_character sc ON s.sceneId = sc.sceneId
            WHERE s.conversationId = ? AND sc.characterId = ? AND s.isActive = TRUE AND sc.leftAt IS NULL
            ORDER BY s.createdAt DESC LIMIT 1
            """;
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId, characterId))
            .map(rows -> rows.size() > 0 ? rows.iterator().next().getString("sceneId") : null);
    }

    public Uni<Boolean> areCharactersColocated(String conversationId, String charId1, String charId2) {
        String query = """
            SELECT COUNT(*) as cnt FROM scene_character sc1
            JOIN scene_character sc2 ON sc1.sceneId = sc2.sceneId
            JOIN scene s ON s.sceneId = sc1.sceneId
            WHERE s.conversationId = ? AND s.isActive = TRUE
            AND sc1.characterId = ? AND sc2.characterId = ?
            AND sc1.leftAt IS NULL AND sc2.leftAt IS NULL
            """;
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId, charId1, charId2))
            .map(rows -> rows.iterator().next().getInteger("cnt") > 0);
    }

    private Scene mapRowToScene(Row row) {
        Scene s = new Scene();
        s.setSceneId(UUID.fromString(row.getString("sceneId")));
        s.setConversationId(UUID.fromString(row.getString("conversationId")));
        s.setName(row.getString("name"));
        s.setLocation(row.getString("location"));
        s.setDescription(row.getString("description"));
        s.setIsActive(row.getBoolean("isActive"));
        if (row.getString("parentSceneId") != null) s.setParentSceneId(UUID.fromString(row.getString("parentSceneId")));
        s.setMetadata(ServiceHelper.safeGetJsonObject(row, "metadata"));
        s.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        s.setEndedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("endedAt")));
        return s;
    }
}
