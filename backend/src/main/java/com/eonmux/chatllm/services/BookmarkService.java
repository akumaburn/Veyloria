package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.StoryBookmark;
import com.eonmux.chatllm.utils.ServiceHelper;
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
public class BookmarkService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;

    public Uni<List<StoryBookmark>> getBookmarks(String conversationId) {
        String query = "SELECT * FROM story_bookmark WHERE conversationId = ? ORDER BY createdAt DESC";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                List<StoryBookmark> bookmarks = new ArrayList<>();
                for (Row row : rows) bookmarks.add(mapRowToBookmark(row));
                return bookmarks;
            });
    }

    public Uni<StoryBookmark> createBookmark(String conversationId, String userId, String messageId,
                                                 String label, String description, List<String> tags,
                                                 JsonObject worldStateSnapshot) {
        String id = UUID.randomUUID().toString();
        String insert = """
            INSERT INTO story_bookmark (bookmarkId, conversationId, userId, messageId, label, description, tags, worldStateSnapshot)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        String tagsStr = tags != null && !tags.isEmpty() ? new io.vertx.core.json.JsonArray(tags).encode() : null;
        return pool.preparedQuery(insert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(conversationId).addValue(userId).addValue(messageId)
                .addValue(label).addValue(description).addValue(tagsStr)
                .addValue(worldStateSnapshot != null ? worldStateSnapshot.encode() : null))
            .map(v -> {
                StoryBookmark b = new StoryBookmark();
                b.setBookmarkId(UUID.fromString(id));
                b.setConversationId(UUID.fromString(conversationId));
                b.setUserId(UUID.fromString(userId));
                if (messageId != null) b.setMessageId(UUID.fromString(messageId));
                b.setLabel(label);
                b.setDescription(description);
                b.setTags(tags != null ? tags : new ArrayList<>());
                b.setWorldStateSnapshot(worldStateSnapshot);
                return b;
            });
    }

    public Uni<Boolean> deleteBookmark(String bookmarkId) {
        return pool.preparedQuery("DELETE FROM story_bookmark WHERE bookmarkId = ?")
            .execute(Tuple.of(bookmarkId))
            .map(rows -> rows.rowCount() > 0);
    }

    private StoryBookmark mapRowToBookmark(Row row) {
        StoryBookmark b = new StoryBookmark();
        b.setBookmarkId(UUID.fromString(row.getString("bookmarkId")));
        b.setConversationId(UUID.fromString(row.getString("conversationId")));
        b.setUserId(UUID.fromString(row.getString("userId")));
        if (row.getString("messageId") != null) b.setMessageId(UUID.fromString(row.getString("messageId")));
        b.setLabel(row.getString("label"));
        b.setDescription(row.getString("description"));
        b.setWorldStateSnapshot(ServiceHelper.safeGetJsonObject(row, "worldStateSnapshot"));
        if (row.getString("tags") != null) b.setTagsFromString(row.getString("tags"));
        b.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        return b;
    }
}
