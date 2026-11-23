package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.PublishedStory;
import com.eonmux.chatllm.models.StoryRating;
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
public class PublishingService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;

    public Uni<PublishedStory> publishStory(String conversationId, String userId, String title,
                                                String synopsis, String genre, List<String> tags) {
        String id = UUID.randomUUID().toString();
        String tagsStr = tags != null && !tags.isEmpty() ? new io.vertx.core.json.JsonArray(tags).encode() : null;
        String insert = """
            INSERT INTO published_story (storyId, conversationId, userId, title, synopsis, genre, tags)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        return pool.preparedQuery(insert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(conversationId).addValue(userId).addValue(title)
                .addValue(synopsis).addValue(genre).addValue(tagsStr))
            .map(v -> PublishedStory.builder()
                .storyId(UUID.fromString(id))
                .conversationId(UUID.fromString(conversationId))
                .userId(UUID.fromString(userId))
                .title(title)
                .synopsis(synopsis)
                .genre(genre)
                .tags(tags != null ? tags : new ArrayList<>())
                .build());
    }

    public Uni<List<PublishedStory>> browseStories(String genre, Integer limit, Integer offset) {
        String query;
        Tuple params;
        if (genre != null && !genre.isEmpty()) {
            query = "SELECT * FROM published_story WHERE isPublic = TRUE AND genre = ? ORDER BY rating DESC, publishedAt DESC LIMIT ? OFFSET ?";
            params = Tuple.of(genre, limit != null ? limit : 20, offset != null ? offset : 0);
        } else {
            query = "SELECT * FROM published_story WHERE isPublic = TRUE ORDER BY rating DESC, publishedAt DESC LIMIT ? OFFSET ?";
            params = Tuple.of(limit != null ? limit : 20, offset != null ? offset : 0);
        }
        return pool.preparedQuery(query)
            .execute(params)
            .map(rows -> {
                List<PublishedStory> stories = new ArrayList<>();
                for (Row row : rows) stories.add(mapRowToStory(row));
                return stories;
            });
    }

    public Uni<StoryRating> rateStory(String storyId, String userId, int rating, String review) {
        String id = UUID.randomUUID().toString();
        String upsert = """
            INSERT INTO story_rating (id, storyId, userId, rating, review)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE rating = VALUES(rating), review = VALUES(review)
            """;
        return pool.preparedQuery(upsert)
            .execute(Tuple.of(id, storyId, userId, rating, review))
            .chain(v -> {
                // Update aggregate rating
                String updateAgg = """
                    UPDATE published_story SET
                        rating = (SELECT AVG(rating) FROM story_rating WHERE storyId = ?),
                        ratingCount = (SELECT COUNT(*) FROM story_rating WHERE storyId = ?)
                    WHERE storyId = ?
                    """;
                return pool.preparedQuery(updateAgg).execute(Tuple.of(storyId, storyId, storyId));
            })
            .map(v -> StoryRating.builder()
                .id(UUID.fromString(id))
                .storyId(UUID.fromString(storyId))
                .userId(UUID.fromString(userId))
                .rating(rating)
                .review(review)
                .build());
    }

    private PublishedStory mapRowToStory(Row row) {
        PublishedStory s = new PublishedStory();
        s.setStoryId(UUID.fromString(row.getString("storyId")));
        s.setConversationId(UUID.fromString(row.getString("conversationId")));
        s.setUserId(UUID.fromString(row.getString("userId")));
        s.setTitle(row.getString("title"));
        s.setSynopsis(row.getString("synopsis"));
        if (row.getString("coverImageId") != null) s.setCoverImageId(UUID.fromString(row.getString("coverImageId")));
        s.setGenre(row.getString("genre"));
        if (row.getString("tags") != null) s.setTagsFromString(row.getString("tags"));
        s.setWordCount(row.getInteger("wordCount"));
        s.setRating(row.getDouble("rating"));
        s.setRatingCount(row.getInteger("ratingCount"));
        s.setViewCount(row.getInteger("viewCount"));
        s.setLikeCount(row.getInteger("likeCount"));
        s.setIsPublic(row.getBoolean("isPublic"));
        s.setIsFeatured(row.getBoolean("isFeatured"));
        s.setPublishedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("publishedAt")));
        s.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return s;
    }
}
