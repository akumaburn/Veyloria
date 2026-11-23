package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.CommunityTemplate;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.models.CommunityTemplatePagination;
import com.eonmux.chatllm.models.PublishedStory;
import com.eonmux.chatllm.models.PublishedStoryPagination;
import com.eonmux.chatllm.models.StoryRating;
import com.eonmux.chatllm.models.StoryTemplate;
import com.eonmux.chatllm.models.inputs.*;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;

/**
 * GraphQL resolver for publishing stories and community template operations.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class CommunityResolver {

    @Inject
    Vertx vertx;
    @Inject
    Pool mysqlPool;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    jakarta.enterprise.inject.Instance<io.smallrye.graphql.api.Context> graphqlInputContext;

    /**
     * Get published stories with optional genre filter and pagination.
     * Schema: publishedStories(genre: String, limit: Int, offset: Int): PublishedStoryPagination! @auth
     */
    @Query
    public CompletableFuture<PublishedStoryPagination> publishedStories(@Name("genre") String genre, @Name("limit") Integer limit, @Name("offset") Integer offset) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        final int effectiveLimit = limit != null ? limit : 20;
        final int effectiveOffset = offset != null ? offset : 0;

        CompletableFuture<PublishedStoryPagination> future = new CompletableFuture<>();

        String query;
        Tuple params;
        if (genre != null && !genre.isEmpty()) {
            query = "SELECT *, (SELECT COUNT(*) FROM published_story WHERE genre = ? AND isPublic = TRUE) as total FROM published_story WHERE genre = ? AND isPublic = TRUE ORDER BY publishedAt DESC LIMIT ? OFFSET ?";
            params = Tuple.of(genre, genre, effectiveLimit, effectiveOffset);
        } else {
            query = "SELECT *, (SELECT COUNT(*) FROM published_story WHERE isPublic = TRUE) as total FROM published_story WHERE isPublic = TRUE ORDER BY publishedAt DESC LIMIT ? OFFSET ?";
            params = Tuple.of(effectiveLimit, effectiveOffset);
        }

        mysqlPool.preparedQuery(query)
            .execute(params)
            .subscribe().with(rows -> {
                List<PublishedStory> stories = new ArrayList<>();
                long total = 0;
                for (var row : rows) {
                    total = row.getLong("total");
                    stories.add(mapStoryRow(row));
                }

                PublishedStoryPagination result = PublishedStoryPagination.builder()
                    .items(stories)
                    .totalCount(total)
                    .hasMore((effectiveOffset + effectiveLimit) < total)
                    .build();
                future.complete(result);
            },
            err -> {
                log.error("Failed to fetch published stories", err);
                future.completeExceptionally(err);
            });

        return future;
    }

    /**
     * Get a single published story by ID and increment its view count.
     * Schema: publishedStory(storyId: ID!): PublishedStory @auth
     */
    @Query
    public CompletableFuture<PublishedStory> publishedStory(@Name("storyId") String storyId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        CompletableFuture<PublishedStory> future = new CompletableFuture<>();

        String query = "SELECT * FROM published_story WHERE storyId = ?";
        mysqlPool.preparedQuery(query)
            .execute(Tuple.of(storyId))
            .subscribe().with(rows -> {
                if (rows.size() == 0) {
                    future.complete(null);
                    return;
                }

                var row = rows.iterator().next();
                PublishedStory story = mapStoryRow(row);

                // Increment view count
                String updateQuery = "UPDATE published_story SET viewCount = viewCount + 1 WHERE storyId = ?";
                mysqlPool.preparedQuery(updateQuery)
                    .execute(Tuple.of(storyId))
                    .subscribe().with(
                        updateResult -> future.complete(story),
                        updateErr -> {
                            log.warn("Failed to increment view count for story {}", storyId, updateErr);
                            future.complete(story);
                        });
            },
            err -> {
                log.error("Failed to fetch published story {}", storyId, err);
                future.completeExceptionally(err);
            });

        return future;
    }

    /**
     * Get approved community templates with pagination.
     * Schema: communityTemplates(limit: Int, offset: Int): CommunityTemplatePagination! @auth
     */
    @Query
    public CompletableFuture<CommunityTemplatePagination> communityTemplates(@Name("limit") Integer limit, @Name("offset") Integer offset) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        final int effectiveLimit = limit != null ? limit : 20;
        final int effectiveOffset = offset != null ? offset : 0;

        CompletableFuture<CommunityTemplatePagination> future = new CompletableFuture<>();

        String query = "SELECT ct.*, st.name AS st_name, st.genre AS st_genre, st.description AS st_description, " +
            "st.defaultWorldState AS st_defaultWorldState, st.suggestedCharacterArchetypes AS st_suggestedCharacterArchetypes, " +
            "st.rules AS st_rules, st.moodPresets AS st_moodPresets, st.isBuiltIn AS st_isBuiltIn, " +
            "st.isPublic AS st_isPublic, st.ownerId AS st_ownerId, " +
            "(SELECT COUNT(*) FROM community_template WHERE status = 'approved') as total " +
            "FROM community_template ct " +
            "LEFT JOIN story_template st ON ct.templateId = st.templateId " +
            "WHERE ct.status = 'approved' ORDER BY ct.submittedAt DESC LIMIT ? OFFSET ?";
        mysqlPool.preparedQuery(query)
            .execute(Tuple.of(effectiveLimit, effectiveOffset))
            .subscribe().with(rows -> {
                List<CommunityTemplate> templates = new ArrayList<>();
                long total = 0;
                for (var row : rows) {
                    total = row.getLong("total");

                    CommunityTemplate.CommunityTemplateBuilder templateBuilder = CommunityTemplate.builder()
                        .submissionId(uuidFromString(row.getString("submissionId")))
                        .templateId(row.getString("templateId"))
                        .userId(uuidFromString(row.getString("userId")))
                        .status(row.getString("status"))
                        .downloads(row.getInteger("downloads"))
                        .rating(row.getDouble("rating"))
                        .ratingCount(row.getInteger("ratingCount"))
                        .submittedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("submittedAt")));

                    // Nested story template
                    String stName = row.getString("st_name");
                    if (stName != null) {
                        StoryTemplate.StoryTemplateBuilder stBuilder = StoryTemplate.builder()
                            .templateId(row.getString("templateId"))
                            .name(stName)
                            .genre(row.getString("st_genre"))
                            .description(row.getString("st_description"))
                            .isBuiltIn(row.getBoolean("st_isBuiltIn"))
                            .isPublic(row.getBoolean("st_isPublic"))
                            .ownerId(uuidFromString(row.getString("st_ownerId")));

                        // Parse JSON fields
                        for (String jsonField : List.of("st_defaultWorldState", "st_suggestedCharacterArchetypes", "st_rules", "st_moodPresets")) {
                            try {
                                String jsonStr = row.getString(jsonField);
                                if (jsonStr != null && !jsonStr.isEmpty()) {
                                    JsonObject parsed = new JsonObject(jsonStr);
                                    switch (jsonField) {
                                        case "st_defaultWorldState" -> stBuilder.defaultWorldState(parsed);
                                        case "st_suggestedCharacterArchetypes" -> stBuilder.suggestedCharacterArchetypes(parsed);
                                        case "st_rules" -> stBuilder.rules(parsed);
                                        case "st_moodPresets" -> stBuilder.moodPresets(parsed);
                                    }
                                }
                            } catch (Exception e) {
                                log.debug("Failed to parse {} for template {}", jsonField, row.getString("templateId"));
                            }
                        }
                        templateBuilder.template(stBuilder.build());
                    }

                    templates.add(templateBuilder.build());
                }

                CommunityTemplatePagination result = CommunityTemplatePagination.builder()
                    .items(templates)
                    .totalCount(total)
                    .hasMore((effectiveOffset + effectiveLimit) < total)
                    .build();
                future.complete(result);
            },
            err -> {
                log.error("Failed to fetch community templates", err);
                future.completeExceptionally(err);
            });

        return future;
    }

    /**
     * Get featured stories (high-rated and popular).
     * Schema: featuredStories(limit: Int): [PublishedStory!]! @auth
     */
    @Query
    public CompletableFuture<List<PublishedStory>> featuredStories(@Name("limit") Integer limit) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        final int effectiveLimit = limit != null ? limit : 10;

        CompletableFuture<List<PublishedStory>> future = new CompletableFuture<>();

        String query = "SELECT * FROM published_story WHERE isPublic = TRUE AND isFeatured = TRUE ORDER BY publishedAt DESC LIMIT ?";
        mysqlPool.preparedQuery(query)
            .execute(Tuple.of(effectiveLimit))
            .subscribe().with(rows -> {
                List<PublishedStory> stories = new ArrayList<>();
                rows.forEach(row -> stories.add(mapStoryRow(row)));
                future.complete(stories);
            },
            err -> {
                log.debug("isFeatured query failed, falling back to rating-based featured stories", err);
                String fallbackQuery = "SELECT * FROM published_story WHERE isPublic = TRUE ORDER BY rating DESC, viewCount DESC LIMIT ?";
                mysqlPool.preparedQuery(fallbackQuery)
                    .execute(Tuple.of(effectiveLimit))
                    .subscribe().with(
                        fallbackRows -> {
                            List<PublishedStory> stories = new ArrayList<>();
                            fallbackRows.forEach(row -> stories.add(mapStoryRow(row)));
                            future.complete(stories);
                        },
                        fallbackErr -> {
                            log.error("Failed to fetch featured stories", fallbackErr);
                            future.completeExceptionally(fallbackErr);
                        });
            });

        return future;
    }

    /**
     * Publish a story from a conversation.
     * Schema: publishStory(input: PublishStoryInput!): PublishedStory! @auth
     */
    @Mutation
    public CompletableFuture<PublishedStory> publishStory(@Name("input") PublishStoryInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String title = (String) input.get("title");
        String synopsis = (String) input.get("synopsis");
        String genre = (String) input.get("genre");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) input.get("tags");
        String coverImageId = (String) input.get("coverImageId");
        String userId = auth.getUserId().toString();

        CompletableFuture<PublishedStory> future = new CompletableFuture<>();

        // Verify the conversation belongs to the authenticated user
        String verifyQuery = "SELECT COUNT(*) as count FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
        mysqlPool.preparedQuery(verifyQuery)
            .execute(Tuple.of(conversationId, userId))
            .subscribe().with(verifyRows -> {
                Long count = verifyRows.iterator().next().getLong("count");
                if (count == 0) {
                    future.completeExceptionally(new RuntimeException("Conversation not found or unauthorized"));
                    return;
                }

                String storyId = UUID.randomUUID().toString();
                String tagsJson = tags != null && !tags.isEmpty() ? new JsonArray(tags).encode() : null;

                String insertQuery = """
                    INSERT INTO published_story (storyId, conversationId, userId, title, synopsis, genre, tags, coverImageId, isPublic)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                    """;

                mysqlPool.preparedQuery(insertQuery)
                    .execute(Tuple.tuple()
                        .addValue(storyId)
                        .addValue(conversationId)
                        .addValue(userId)
                        .addValue(title)
                        .addValue(synopsis)
                        .addValue(genre)
                        .addValue(tagsJson)
                        .addValue(coverImageId))
                    .subscribe().with(
                        insertResult -> {
                            PublishedStory story = PublishedStory.builder()
                                .storyId(UUID.fromString(storyId))
                                .conversationId(UUID.fromString(conversationId))
                                .userId(UUID.fromString(userId))
                                .title(title)
                                .synopsis(synopsis)
                                .genre(genre)
                                .tags(tags != null ? tags : List.of())
                                .coverImageId(uuidFromString(coverImageId))
                                .isPublic(true)
                                .isFeatured(false)
                                .viewCount(0)
                                .likeCount(0)
                                .wordCount(0)
                                .rating(0.0)
                                .ratingCount(0)
                                .publishedAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                                .build();
                            future.complete(story);
                        },
                        err -> {
                            log.error("Failed to publish story for conversation {}", conversationId, err);
                            future.completeExceptionally(err);
                        });
            },
            err -> {
                log.error("Failed to verify conversation ownership for publishing", err);
                future.completeExceptionally(err);
            });

        return future;
    }

    /**
     * Unpublish a story (delete it). Only the author can unpublish.
     * Schema: unpublishStory(storyId: ID!): Boolean! @auth
     */
    @Mutation
    public CompletableFuture<Boolean> unpublishStory(@Name("storyId") String storyId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        String deleteQuery = "DELETE FROM published_story WHERE storyId = ? AND userId = ?";
        mysqlPool.preparedQuery(deleteQuery)
            .execute(Tuple.of(storyId, userId))
            .subscribe().with(
                result -> {
                    boolean deleted = result.rowCount() > 0;
                    future.complete(deleted);
                },
                err -> {
                    log.error("Failed to unpublish story {}", storyId, err);
                    future.completeExceptionally(err);
                });

        return future;
    }

    /**
     * Rate a published story with an optional review. Upserts the rating.
     * Schema: rateStory(input: RateStoryInput!): StoryRating! @auth
     */
    @Mutation
    public CompletableFuture<StoryRating> rateStory(@Name("input") RateStoryInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String storyId = (String) input.get("storyId");
        Integer rating = (Integer) input.get("rating");
        String review = (String) input.get("review");
        String userId = auth.getUserId().toString();

        // Guard against a null rating from a malformed input: the unboxing in
        // `rating < 1` below would NPE the whole mutation.
        if (rating == null || rating < 1 || rating > 5) {
            return CompletableFuture.failedFuture(new RuntimeException("Rating must be between 1 and 5"));
        }

        CompletableFuture<StoryRating> future = new CompletableFuture<>();

        // Verify the story exists before inserting a rating row.
        String storyCheck = "SELECT 1 FROM published_story WHERE storyId = ?";
        mysqlPool.preparedQuery(storyCheck)
            .execute(Tuple.of(storyId))
            .subscribe().with(
                storyRows -> {
                    if (storyRows.size() == 0) {
                        future.completeExceptionally(new RuntimeException("Story not found: " + storyId));
                        return;
                    }
                    doRateStory(storyId, rating, review, userId, future);
                },
                err -> future.completeExceptionally(err));

        return future;
    }

    private void doRateStory(String storyId, Integer rating, String review, String userId,
                             CompletableFuture<StoryRating> future) {
        String ratingId = UUID.randomUUID().toString();
        String upsertQuery = """
            INSERT INTO story_rating (id, storyId, userId, rating, review)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE rating = VALUES(rating), review = VALUES(review), createdAt = CURRENT_TIMESTAMP
            """;

        mysqlPool.preparedQuery(upsertQuery)
            .execute(Tuple.of(ratingId, storyId, userId, rating, review))
            .subscribe().with(upsertResult -> {
                // Update the average rating on the published story
                String updateAvgQuery = "UPDATE published_story SET rating = (SELECT AVG(rating) FROM story_rating WHERE storyId = ?), ratingCount = (SELECT COUNT(*) FROM story_rating WHERE storyId = ?) WHERE storyId = ?";
                mysqlPool.preparedQuery(updateAvgQuery)
                    .execute(Tuple.of(storyId, storyId, storyId))
                    .subscribe().with(
                        updateResult -> {
                            // Fetch the actual rating record
                            String selectQuery = "SELECT * FROM story_rating WHERE storyId = ? AND userId = ?";
                            mysqlPool.preparedQuery(selectQuery)
                                .execute(Tuple.of(storyId, userId))
                                .subscribe().with(
                                    selectRows -> {
                                        StoryRating ratingResult;
                                        if (selectRows.size() > 0) {
                                            var row = selectRows.iterator().next();
                                            ratingResult = StoryRating.builder()
                                                .id(uuidFromString(row.getString("id")))
                                                .storyId(uuidFromString(row.getString("storyId")))
                                                .userId(uuidFromString(row.getString("userId")))
                                                .rating(row.getInteger("rating"))
                                                .review(row.getString("review"))
                                                .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                                                .build();
                                        } else {
                                            ratingResult = StoryRating.builder()
                                                .id(UUID.fromString(ratingId))
                                                .storyId(UUID.fromString(storyId))
                                                .userId(UUID.fromString(userId))
                                                .rating(rating)
                                                .review(review)
                                                .createdAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                                                .build();
                                        }
                                        future.complete(ratingResult);
                                    },
                                    selectErr -> {
                                        log.warn("Failed to fetch rating record after upsert, returning constructed result", selectErr);
                                        StoryRating ratingResult = StoryRating.builder()
                                            .id(UUID.fromString(ratingId))
                                            .storyId(UUID.fromString(storyId))
                                            .userId(UUID.fromString(userId))
                                            .rating(rating)
                                            .review(review)
                                            .createdAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                                            .build();
                                        future.complete(ratingResult);
                                    });
                        },
                        updateErr -> {
                            log.warn("Failed to update average rating for story {}", storyId, updateErr);
                            StoryRating ratingResult = StoryRating.builder()
                                .id(UUID.fromString(ratingId))
                                .storyId(UUID.fromString(storyId))
                                .userId(UUID.fromString(userId))
                                .rating(rating)
                                .review(review)
                                .createdAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                                .build();
                            future.complete(ratingResult);
                        });
            },
            err -> {
                log.error("Failed to upsert rating for story {}", storyId, err);
                future.completeExceptionally(err);
            });
    }

    /**
     * Submit a community template for review.
     * Schema: submitTemplate(input: SubmitTemplateInput!): CommunityTemplate! @auth
     */
    @Mutation
    public CompletableFuture<CommunityTemplate> submitTemplate(@Name("input") SubmitTemplateInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String templateId = (String) input.get("templateId");
        String userId = auth.getUserId().toString();

        CompletableFuture<CommunityTemplate> future = new CompletableFuture<>();

        String submissionId = UUID.randomUUID().toString();

        String insertQuery = """
            INSERT INTO community_template (submissionId, templateId, userId, status)
            VALUES (?, ?, ?, 'pending')
            """;

        mysqlPool.preparedQuery(insertQuery)
            .execute(Tuple.of(submissionId, templateId, userId))
            .subscribe().with(
                insertResult -> {
                    CommunityTemplate template = CommunityTemplate.builder()
                        .submissionId(UUID.fromString(submissionId))
                        .templateId(templateId)
                        .userId(UUID.fromString(userId))
                        .status("pending")
                        .downloads(0)
                        .rating(0.0)
                        .ratingCount(0)
                        .submittedAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                        .build();
                    future.complete(template);
                },
                err -> {
                    log.error("Failed to submit community template", err);
                    future.completeExceptionally(err);
                });

        return future;
    }

    /**
     * Helper method to map a database row to a PublishedStory object.
     * DB columns: storyId, conversationId, userId, title, synopsis, coverImageId, genre, tags,
     *             wordCount, rating, ratingCount, viewCount, likeCount, isPublic, isFeatured, publishedAt, updatedAt
     */
    private PublishedStory mapStoryRow(Row row) {
        // Parse tags from JSON
        List<String> tags;
        try {
            String tagsStr = row.getString("tags");
            if (tagsStr != null && !tagsStr.isEmpty()) {
                JsonArray tagsArray = new JsonArray(tagsStr);
                tags = new ArrayList<>();
                for (int i = 0; i < tagsArray.size(); i++) {
                    tags.add(tagsArray.getString(i));
                }
            } else {
                tags = List.of();
            }
        } catch (Exception e) {
            log.warn("Failed to parse tags for story {}", row.getString("storyId"), e);
            tags = List.of();
        }

        return PublishedStory.builder()
            .storyId(uuidFromString(row.getString("storyId")))
            .conversationId(uuidFromString(row.getString("conversationId")))
            .userId(uuidFromString(row.getString("userId")))
            .title(row.getString("title"))
            .synopsis(row.getString("synopsis"))
            .genre(row.getString("genre"))
            .tags(tags)
            .coverImageId(uuidFromString(row.getString("coverImageId")))
            .isPublic(row.getBoolean("isPublic"))
            .isFeatured(row.getBoolean("isFeatured"))
            .viewCount(row.getInteger("viewCount"))
            .likeCount(row.getInteger("likeCount"))
            .wordCount(row.getInteger("wordCount"))
            .rating(row.getDouble("rating"))
            .ratingCount(row.getInteger("ratingCount"))
            .publishedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("publishedAt")))
            .updatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")))
            .build();
    }

    /**
     * Safely convert a nullable String to a UUID.
     * Returns null if the input is null or empty.
     */
    private UUID uuidFromString(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return UUID.fromString(value);
    }
}
