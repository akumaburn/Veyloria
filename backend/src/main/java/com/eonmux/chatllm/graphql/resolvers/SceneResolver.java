package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.config.RoleplayEventAddresses;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.models.Scene;
import com.eonmux.chatllm.models.inputs.*;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;

/**
 * GraphQL resolver for scene management in roleplay conversations.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class SceneResolver {

    @Inject
    Vertx vertx;
    @Inject
    Pool mysqlPool;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    jakarta.enterprise.inject.Instance<io.smallrye.graphql.api.Context> graphqlInputContext;

    @Inject
    com.eonmux.chatllm.utils.ServiceHelper serviceHelper;

    /**
     * Verify the caller owns or actively participates in the conversation
     * before mutating scenes. Previously these mutations only required
     * authentication, so any logged-in user could change another user's
     * conversation scene.
     */
    private Uni<Boolean> verifyConversationAccess(String conversationId, String userId) {
        return serviceHelper.verifyConversationAccess(conversationId, userId);
    }

    /**
     * Get the active scene for a conversation.
     * Schema: activeScene(conversationId: ID!): Scene @auth
     */
    @Query
    public CompletableFuture<Scene> activeScene(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        CompletableFuture<Scene> future = new CompletableFuture<>();

        // Gate the read on conversation access — previously any authenticated
        // user could read the active scene of any conversation.
        verifyConversationAccess(conversationId, userId)
            .subscribe().with(hasAccess -> {
                if (!hasAccess) {
                    future.complete(null);
                    return;
                }
                doActiveScene(conversationId, future);
            }, err -> {
                log.error("Failed to verify access for activeScene conversation {}", conversationId, err);
                future.completeExceptionally(err);
            });

        return future;
    }

    private void doActiveScene(String conversationId, CompletableFuture<Scene> future) {
        String query = """
            SELECT * FROM scene
            WHERE conversationId = ? AND isActive = TRUE
            LIMIT 1
            """;

        mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .chain(rows -> {
                if (rows.size() == 0) {
                    future.complete(null);
                    return Uni.createFrom().voidItem();
                }

                var row = rows.iterator().next();
                String sceneIdStr = row.getString("sceneId");
                String convIdStr = row.getString("conversationId");
                String parentSceneIdStr = row.getString("parentSceneId");

                if (sceneIdStr == null) {
                    future.complete(null);
                    return Uni.createFrom().voidItem();
                }

                // Read every column before the second query: the row belongs to
                // the first result set, which is already consumed by then.
                String sceneName = row.getString("name");
                String sceneLocation = row.getString("location");
                String sceneDescription = row.getString("description");
                Boolean sceneActive = row.getBoolean("isActive");
                String sceneCreatedAt = Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt"));
                String sceneEndedAt = Time4JHelper.localDateTimeToString(row.getLocalDateTime("endedAt"));

                return loadSceneCharacters(sceneIdStr)
                    .invoke(characters -> {
                        Scene scene = Scene.builder()
                            .sceneId(UUID.fromString(sceneIdStr))
                            .conversationId(convIdStr != null ? UUID.fromString(convIdStr) : null)
                            .name(sceneName)
                            .location(sceneLocation)
                            .description(sceneDescription)
                            .isActive(sceneActive)
                            .parentSceneId(parentSceneIdStr != null ? UUID.fromString(parentSceneIdStr) : null)
                            .createdAt(sceneCreatedAt)
                            .endedAt(sceneEndedAt)
                            .characters(characters)
                            .build();

                        future.complete(scene);
                    })
                    .replaceWithVoid();
            })
            .onFailure().invoke(err -> {
                log.error("Failed to fetch active scene for conversation {}", conversationId, err);
                future.completeExceptionally(err);
            }).subscribe().with(ignored -> {}, future::completeExceptionally);
    }

    /**
     * Loads the cast of a scene with the fields the client renders.
     *
     * Only the ids used to be selected, so the chat header called charAt on a
     * null character name and the error boundary replaced the whole page. Every
     * scene response now carries the same shape as the character list.
     */
    private Uni<List<Character>> loadSceneCharacters(String sceneId) {
        return mysqlPool.preparedQuery("""
                SELECT c.characterUUID, c.name, c.avatar
                FROM scene_character sc
                JOIN `character` c ON c.characterUUID = sc.characterId
                WHERE sc.sceneId = ? AND sc.leftAt IS NULL
                ORDER BY sc.joinedAt, c.name
                """)
            .execute(Tuple.of(sceneId))
            .map(rows -> {
                List<Character> characters = new ArrayList<>();
                for (var row : rows) {
                    characters.add(Character.builder()
                        .id(UUID.fromString(row.getString("characterUUID")))
                        .name(row.getString("name"))
                        .avatar(row.getString("avatar"))
                        .build());
                }
                return characters;
            });
    }

    /**
     * Change the current scene.
     * Schema: changeScene(input: ChangeSceneInput!): Scene! @auth
     */
    @Mutation
    public CompletableFuture<Scene> changeScene(@Name("input") ChangeSceneInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String name = (String) input.get("sceneName");
        String location = (String) input.get("location");
        String description = (String) input.get("description");
        @SuppressWarnings("unchecked")
        List<String> characterIds = (List<String>) input.get("characterIds");

        CompletableFuture<Scene> future = new CompletableFuture<>();

        // Verify conversation access before mutating scene tables.
        verifyConversationAccess(conversationId, auth.getUserId().toString())
            .subscribe().with(hasAccess -> {
                if (!hasAccess) {
                    future.completeExceptionally(new RuntimeException("Conversation not found or access denied"));
                    return;
                }

                changeSceneInternal(conversationId, name, location, description, characterIds, future);
            }, err -> {
                log.error("Failed to verify access for changeScene conversation {}", conversationId, err);
                future.completeExceptionally(err);
            });

        return future;
    }

    private void changeSceneInternal(String conversationId, String name, String location,
                                     String description, List<String> characterIds,
                                     CompletableFuture<Scene> future) {
        // Step 1: Deactivate current scene
        String deactivateQuery = "UPDATE scene SET isActive = FALSE, endedAt = NOW() WHERE conversationId = ? AND isActive = TRUE";
        mysqlPool.preparedQuery(deactivateQuery)
            .execute(Tuple.of(conversationId))
            .invoke(deactivateResult -> {
                // Step 2: Create new scene
                String sceneId = UUID.randomUUID().toString();
                String insertSceneQuery = """
                    INSERT INTO scene (sceneId, conversationId, name, location, description, isActive)
                    VALUES (?, ?, ?, ?, ?, TRUE)
                    """;
                mysqlPool.preparedQuery(insertSceneQuery)
                    .execute(Tuple.of(sceneId, conversationId, name, location, description))
                    .invoke(insertResult -> {
                        // Step 3: Add characters to scene if provided
                        if (characterIds != null && !characterIds.isEmpty()) {
                            List<Uni<Void>> characterFutures = new ArrayList<>();
                            for (String characterId : characterIds) {
                                String insertCharQuery = """
                                    INSERT INTO scene_character (id, sceneId, characterId)
                                    VALUES (?, ?, ?)
                                    """;
                                Uni<Void> charFuture = mysqlPool.preparedQuery(insertCharQuery)
                                    .execute(Tuple.of(UUID.randomUUID().toString(), sceneId, characterId))
                                    .replaceWithVoid();
                                characterFutures.add(charFuture);
                            }

                            Uni.join().all(characterFutures).andFailFast()
                                .subscribe().with(
                                    v -> updateWorldStateAndPublish(conversationId, sceneId, name, location, future),
                                    err -> {
                                        log.error("Failed to add characters to scene {}", sceneId, err);
                                        future.completeExceptionally(err);
                                    });
                        } else {
                            updateWorldStateAndPublish(conversationId, sceneId, name, location, future);
                        }
                    })
                    .onFailure().invoke(err -> {
                        log.error("Failed to create new scene for conversation {}", conversationId, err);
                        future.completeExceptionally(err);
                    }).subscribe().with(ignored -> {}, future::completeExceptionally);
            })
            .onFailure().invoke(err -> {
                log.error("Failed to deactivate current scene for conversation {}", conversationId, err);
                future.completeExceptionally(err);
            }).subscribe().with(ignored -> {}, future::completeExceptionally);
    }

    private void updateWorldStateAndPublish(String conversationId, String sceneId, String name,
                                           String location, CompletableFuture<Scene> future) {
        // Step 4: Update world state
        String updateWorldStateQuery = """
            UPDATE world_state SET currentScene = ?, currentLocation = ?,
                currentArea = NULL, currentAreaId = NULL
            WHERE conversationId = ?
            """;
        mysqlPool.preparedQuery(updateWorldStateQuery)
            .execute(Tuple.of(name, location, conversationId))
            .chain(wsResult -> finishSceneResponse(conversationId, sceneId, name, location, true, future))
            .onFailure().recoverWithUni(err -> {
                log.warn("World state update failed for scene {}, scene created but state may be stale", sceneId, err);
                return finishSceneResponse(conversationId, sceneId, name, location, false, future);
            }).subscribe().with(ignored -> {}, future::completeExceptionally);
    }

    /**
     * Completes a changeScene response with the cast that was just linked to
     * the scene, then publishes the change. Loading the cast here keeps the
     * mutation and activeScene responses identical.
     */
    private Uni<Void> finishSceneResponse(String conversationId, String sceneId, String name,
                                          String location, boolean publish,
                                          CompletableFuture<Scene> future) {
        return loadSceneCharacters(sceneId)
            .invoke(characters -> {
                Scene scene = Scene.builder()
                    .sceneId(UUID.fromString(sceneId))
                    .conversationId(UUID.fromString(conversationId))
                    .name(name)
                    .location(location)
                    .isActive(true)
                    .characters(characters)
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                    .build();

                if (publish) {
                    JsonObject eventData = new JsonObject()
                        .put("sceneId", sceneId)
                        .put("conversationId", conversationId)
                        .put("name", name)
                        .put("location", location);
                    vertx.eventBus().publish(RoleplayEventAddresses.SCENE_CHANGED + conversationId, eventData);
                }

                future.complete(scene);
            })
            .replaceWithVoid();
    }

    /**
     * Move a character to a different scene.
     * Schema: moveCharacterToScene(conversationId: ID!, characterId: ID!, sceneId: ID!): Boolean! @auth
     */
    @Mutation
    public CompletableFuture<Boolean> moveCharacterToScene(@Name("conversationId") String conversationId, @Name("characterId") String characterId, @Name("sceneId") String sceneId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Verify conversation access before moving characters between scenes.
        verifyConversationAccess(conversationId, auth.getUserId().toString())
            .subscribe().with(hasAccess -> {
                if (!hasAccess) {
                    future.completeExceptionally(new RuntimeException("Conversation not found or access denied"));
                    return;
                }

                moveCharacterToSceneInternal(conversationId, characterId, sceneId, future);
            }, err -> {
                log.error("Failed to verify access for moveCharacterToScene conversation {}", conversationId, err);
                future.completeExceptionally(err);
            });

        return future;
    }

    private void moveCharacterToSceneInternal(String conversationId, String characterId, String sceneId,
                                              CompletableFuture<Boolean> future) {
        // Verify the target scene belongs to the verified conversation, and
        // the character is part of that conversation (or owned/public).
        // Without this, a caller with access to ANY conversation could insert
        // any characterId into any sceneId of a conversation they don't own.
        String verifyQuery = """
            SELECT COUNT(*) as count
            FROM scene s
            LEFT JOIN conversation_character cc
                ON cc.conversationId = s.conversationId AND cc.characterId = ? AND cc.isActive = true
            LEFT JOIN `character` c ON c.characterUUID = ?
            WHERE s.sceneId = ? AND s.conversationId = ?
              AND (cc.characterId IS NOT NULL OR (c.ownerId = ? OR c.isPublic = true))
            """;
        mysqlPool.preparedQuery(verifyQuery)
            .execute(Tuple.of(characterId, characterId, sceneId, conversationId, characterId))
            .invoke(verifyRows -> {
                long count = verifyRows.iterator().next().getLong("count");
                if (count == 0) {
                    future.completeExceptionally(new RuntimeException("Scene not found in conversation or character not in conversation"));
                    return;
                }

                // Step 1: Remove character from old scenes
                String removeQuery = """
                    UPDATE scene_character SET leftAt = NOW()
                    WHERE characterId = ? AND leftAt IS NULL
                    AND sceneId IN (SELECT sceneId FROM scene WHERE conversationId = ?)
                    """;
                mysqlPool.preparedQuery(removeQuery)
                    .execute(Tuple.of(characterId, conversationId))
                    .invoke(removeResult -> {
                        // Step 2: Add character to new scene
                        String insertQuery = """
                            INSERT INTO scene_character (id, sceneId, characterId)
                            VALUES (?, ?, ?)
                            """;
                        mysqlPool.preparedQuery(insertQuery)
                            .execute(Tuple.of(UUID.randomUUID().toString(), sceneId, characterId))
                            .invoke(insertResult -> {
                                future.complete(true);
                            })
                            .onFailure().invoke(err -> {
                                log.error("Failed to add character {} to scene {}", characterId, sceneId, err);
                                future.completeExceptionally(err);
                            }).subscribe().with(ignored -> {}, future::completeExceptionally);
                    })
                    .onFailure().invoke(err -> {
                        log.error("Failed to remove character {} from old scenes", characterId, err);
                        future.completeExceptionally(err);
                    }).subscribe().with(ignored -> {}, future::completeExceptionally);
            })
            .onFailure().invoke(err -> {
                log.error("Failed to verify scene/character for moveCharacterToScene {}", sceneId, err);
                future.completeExceptionally(err);
            }).subscribe().with(ignored -> {}, future::completeExceptionally);
    }
}
