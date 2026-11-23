package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.StoryBookmark;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.models.inputs.*;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
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
 * GraphQL resolver for bookmark operations.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class BookmarkResolver {

    @Inject
    Vertx vertx;
    @Inject
    Pool mysqlPool;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    jakarta.enterprise.inject.Instance<io.smallrye.graphql.api.Context> graphqlInputContext;

    /**
     * Get bookmarks for a conversation.
     * Schema: bookmarks(conversationId: ID!): [Bookmark!]! @auth
     */
    @Query
    public Uni<List<StoryBookmark>> bookmarks(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();


        String query = "SELECT * FROM story_bookmark WHERE conversationId = ? AND userId = ? ORDER BY createdAt DESC";
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversationId, userId))
            .map(rows -> {
                List<StoryBookmark> bookmarks = new ArrayList<>();
                rows.forEach(row -> bookmarks.add(mapRowToStoryBookmark(row)));
                return bookmarks;
            })
            .onFailure().invoke(err -> {
                log.error("Failed to fetch bookmarks for conversation {}", conversationId, err);
            });
    }

    /**
     * Create a bookmark for a conversation.
     * Schema: createBookmark(input: CreateBookmarkInput!): Bookmark! @auth
     */
    @Mutation
    public Uni<StoryBookmark> createBookmark(@Name("input") CreateBookmarkInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String messageId = (String) input.get("messageId");
        String label = (String) input.get("label");
        String description = (String) input.get("description");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) input.get("tags");
        String userId = auth.getUserId().toString();


        // First, get the current world state snapshot
        String worldStateQuery = "SELECT * FROM world_state WHERE conversationId = ?";
        return mysqlPool.preparedQuery(worldStateQuery)
            .execute(Tuple.of(conversationId))
            .chain(wsRows -> {
                JsonObject worldStateSnapshot = new JsonObject();
                if (wsRows.size() > 0) {
                    var wsRow = wsRows.iterator().next();
                    worldStateSnapshot.put("currentScene", wsRow.getString("currentScene"));
                    worldStateSnapshot.put("currentLocation", wsRow.getString("currentLocation"));
                    worldStateSnapshot.put("currentLocationId", wsRow.getString("currentLocationId"));
                    worldStateSnapshot.put("currentArea", wsRow.getString("currentArea"));
                    worldStateSnapshot.put("currentAreaId", wsRow.getString("currentAreaId"));
                    worldStateSnapshot.put("timeOfDay", wsRow.getString("timeOfDay"));
                    worldStateSnapshot.put("dayNumber", wsRow.getInteger("dayNumber"));
                    worldStateSnapshot.put("weather", wsRow.getString("weather"));
                    worldStateSnapshot.put("mood", wsRow.getString("mood"));
                    try {
                        JsonObject globalFlags = wsRow.getJsonObject("globalFlags");
                        if (globalFlags != null) {
                            worldStateSnapshot.put("globalFlags", globalFlags);
                        }
                    } catch (Exception e) {
                        log.debug("No globalFlags for world state");
                    }
                }

                // Insert the bookmark
                String bookmarkId = UUID.randomUUID().toString();
                String tagsStr = tags != null && !tags.isEmpty() ? new io.vertx.core.json.JsonArray(tags).encode() : null;
                String insertQuery = """
                    INSERT INTO story_bookmark (bookmarkId, conversationId, userId, messageId, label, description, worldStateSnapshot, tags)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;

                return mysqlPool.preparedQuery(insertQuery)
                    .execute(Tuple.tuple()
                        .addValue(bookmarkId)
                        .addValue(conversationId)
                        .addValue(userId)
                        .addValue(messageId)
                        .addValue(label)
                        .addValue(description)
                        .addValue(worldStateSnapshot.encode())
                        .addValue(tagsStr))
                    .map(insertResult -> StoryBookmark.builder()
                        .bookmarkId(UUID.fromString(bookmarkId))
                        .conversationId(UUID.fromString(conversationId))
                        .userId(UUID.fromString(userId))
                        .messageId(messageId != null ? UUID.fromString(messageId) : null)
                        .label(label)
                        .description(description)
                        // worldStateSnapshot is @Ignore - skip
                        .tags(tags != null ? tags : List.of())
                        .createdAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                        .build());
            })
            .onFailure().invoke(err -> {
                log.error("Failed to create bookmark", err);
            });
    }

    /**
     * Delete a bookmark.
     * Schema: deleteBookmark(bookmarkId: ID!): Boolean! @auth
     */
    @Mutation
    public Uni<Boolean> deleteBookmark(@Name("bookmarkId") String bookmarkId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();


        String deleteQuery = "DELETE FROM story_bookmark WHERE bookmarkId = ? AND userId = ?";
        return mysqlPool.preparedQuery(deleteQuery)
            .execute(Tuple.of(bookmarkId, userId))
            .map(result -> result.rowCount() > 0)
            .onFailure().invoke(err -> {
                log.error("Failed to delete bookmark {}", bookmarkId, err);
            });
    }

    private StoryBookmark mapRowToStoryBookmark(Row row) {
        String bookmarkIdStr = row.getString("bookmarkId");
        String convIdStr = row.getString("conversationId");
        String userIdStr = row.getString("userId");
        String messageIdStr = row.getString("messageId");

        // Parse tags: stored as JSON array string or comma-separated
        List<String> tags;
        String tagsStr = row.getString("tags");
        if (tagsStr != null && !tagsStr.isEmpty()) {
            try {
                tags = new io.vertx.core.json.JsonArray(tagsStr).getList();
            } catch (Exception tagEx) {
                // Fallback: treat as comma-separated
                tags = Arrays.asList(tagsStr.split(","));
            }
        } else {
            tags = List.of();
        }

        return StoryBookmark.builder()
            .bookmarkId(bookmarkIdStr != null ? UUID.fromString(bookmarkIdStr) : null)
            .conversationId(convIdStr != null ? UUID.fromString(convIdStr) : null)
            .userId(userIdStr != null ? UUID.fromString(userIdStr) : null)
            .messageId(messageIdStr != null ? UUID.fromString(messageIdStr) : null)
            .label(row.getString("label"))
            .description(row.getString("description"))
            // worldStateSnapshot is @Ignore - skip
            .tags(tags)
            .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
            .build();
    }
}
