package com.eonmux.chatllm.services;

import jakarta.annotation.PostConstruct;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.Conversation;
import com.eonmux.chatllm.models.Message;
import com.eonmux.chatllm.utils.CacheHelper;
import com.eonmux.chatllm.utils.ServiceHelper;
import com.eonmux.chatllm.utils.ValidationHelper;
import io.smallrye.mutiny.Uni;

import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.redis.client.RedisAPI;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for conversation-related operations.
 * Handles conversation management, history, and message threading.
 *
 * Uses CacheHelper and ServiceHelper to reduce code duplication.
 */
@ApplicationScoped
@Slf4j
public class ConversationService {

    private static final int DEFAULT_HISTORY_LIMIT = 50;
    private static final int CACHE_TTL = 300; // 5 minutes

    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    @Inject
    RedisAPI redis;
    // EventBus obtained from vertx.eventBus()

    // Utility helpers to reduce code duplication
    private CacheHelper<Conversation> cacheHelper;
    @Inject
    ServiceHelper serviceHelper;

    
    @PostConstruct
    void init() {
        this.cacheHelper = new CacheHelper<>(vertx.eventBus(), "conversation", CACHE_TTL, Conversation.class);
    }

    /**
     * Create a new conversation.
     */
    public Uni<Conversation> createConversation(String userId, String title, String characterId) {
        
        String conversationId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        
        // Auto-generate title if not provided
        if (title == null || title.trim().isEmpty()) {
            title = "New Conversation " + now.toLocalDate();
        }
        // Truncate title to max length using ValidationHelper
        title = ValidationHelper.truncate(title, ValidationHelper.MAX_TITLE_LENGTH);
        
        final String finalTitle = title;
        
        // Verify character exists and belongs to user or is public
        Uni<Boolean> characterCheck = characterId != null ? 
            verifyCharacterAccess(userId, characterId) : 
            Uni.createFrom().item(true);
        
        return characterCheck
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Character not found or access denied"));
                }

                String insertQuery = """
                    INSERT INTO conversation (
                        conversationGUID, ownerId, title, createdAt,
                        lastModified, characterId, active
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;

                return pool.preparedQuery(insertQuery)
                    .execute(Tuple.tuple()
                        .addValue(conversationId)
                        .addValue(userId)
                        .addValue(finalTitle)
                        .addValue(now)
                        .addValue(now)
                        .addValue(characterId)
                        .addValue(true));
            })
            .map(result -> {
                // Create conversation object
                Conversation conversation = new Conversation();
                conversation.setId(UUID.fromString(conversationId));
                conversation.setOwnerId(UUID.fromString(userId));
                conversation.setTitle(finalTitle);
                conversation.setCreatedAt(now.toString());
                conversation.setUpdatedAt(now.toString());
                conversation.setCharacterId(characterId != null ? UUID.fromString(characterId) : null);
                conversation.setMessageCount(0);

                // Track metric using ServiceHelper to reduce duplication
                serviceHelper.trackMetric("metrics.conversation.created", new JsonObject()
                    .put("userId", userId)
                    .put("conversationId", conversationId));

                // Publish event for real-time updates
                publishConversationEvent("created", conversation);

                return conversation;
            })

            .onFailure().invoke(error -> {
                log.error("Failed to create conversation for user: {}", userId, error);
            });
    }
    
    /**
     * Get conversation by ID.
     * Uses CacheHelper pattern to reduce duplicate code.
     */
    public Uni<Conversation> getConversation(String conversationId, String userId) {
        String cacheKey = cacheHelper.buildKey(conversationId);

        return cacheHelper.get(cacheKey)
            .chain(cached -> {
                if (cached != null) {
                    // Verify access (owner OR active participant). Previously
                    // owner-only, which let participants read message history
                    // but failed to load the conversation object itself.
                    return serviceHelper.verifyConversationAccess(conversationId, userId)
                        .chain(hasAccess -> {
                            if (!hasAccess) {
                                return Uni.createFrom().failure(new RuntimeException("Access denied"));
                            }
                            return Uni.createFrom().item(cached);
                        });
                }

                // Load from database
                return loadConversationFromDb(conversationId, userId)
                    .map(conversation -> {
                        // Cache the result
                        cacheHelper.set(cacheKey, conversation);
                        return conversation;
                    });
            });
    }

    /**
     * Load conversation from database. Extracted for reuse and clarity.
     * Access is owner OR active participant, matching verifyConversationAccess
     * and the message-history query, so invited participants can load the
     * conversation object (and export it).
     */
    private Uni<Conversation> loadConversationFromDb(String conversationId, String userId) {
        String query = """
            SELECT c.*,
                   ch.name as characterName,
                   ch.description as characterDescription,
                   (SELECT COUNT(*) FROM message WHERE conversationId = c.conversationGUID AND (isDeleted = false OR isDeleted IS NULL)) as messageCount
            FROM conversation c
            LEFT JOIN `character` ch ON c.characterId = ch.characterUUID
            LEFT JOIN conversation_participant cp
                ON c.conversationGUID = cp.conversation_id AND cp.user_id = ? AND cp.is_active = true
            WHERE c.conversationGUID = ? AND (c.ownerId = ? OR cp.user_id = ?)
            """;

        return pool.preparedQuery(query)
            .execute(Tuple.of(userId, conversationId, userId, userId))
            .map(rows -> {
                if (rows.size() == 0) {
                    throw new RuntimeException("Conversation not found");
                }
                return mapRowToConversation(rows.iterator().next());
            });
    }
    
    /**
     * Get all conversations for a user.
     */
    public Uni<List<Conversation>> getUserConversations(String userId, int limit, int offset) {
        
        String query = """
            SELECT c.*,
                   ch.name as characterName,
                   ch.description as characterDescription,
                   (SELECT COUNT(*) FROM message WHERE conversationId = c.conversationGUID AND (isDeleted = false OR isDeleted IS NULL)) as messageCount,
                   (SELECT MAX(createdAt) FROM message WHERE conversationId = c.conversationGUID AND (isDeleted = false OR isDeleted IS NULL)) as lastMessageTime
            FROM conversation c
            LEFT JOIN `character` ch ON c.characterId = ch.characterUUID
            WHERE c.ownerId = ? AND c.active = true
            ORDER BY c.lastModified DESC
            LIMIT ? OFFSET ?
            """;
        
        return pool.preparedQuery(query)
            .execute(Tuple.of(userId, limit, offset))
            .map(rows -> {
                List<Conversation> conversations = new ArrayList<>();
                rows.forEach(row -> {
                    conversations.add(mapRowToConversation(row));
                });

                return conversations;
            });
    }
    
    /**
     * Update conversation title.
     */
    public Uni<Conversation> updateTitle(String conversationId, String userId, String newTitle) {
        
        if (newTitle == null || newTitle.trim().isEmpty()) {
            return Uni.createFrom().failure(new RuntimeException("Title cannot be empty"));
    }

        // Truncate title to max length using ValidationHelper
        newTitle = ValidationHelper.truncate(newTitle, ValidationHelper.MAX_TITLE_LENGTH);
        
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String updateQuery = """
            UPDATE conversation
            SET title = ?, lastModified = ?, updatedAt = ?
            WHERE conversationGUID = ? AND ownerId = ?
            """;

        return pool.preparedQuery(updateQuery)
            .execute(Tuple.of(newTitle, now, now, conversationId, userId))
            .chain(result -> {
                if (result.rowCount() == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                }

                // Invalidate cache
                invalidateConversationCache(conversationId);

                // Get updated conversation
                return getConversation(conversationId, userId);
            })
            .invoke(conversation -> {
                // Publish update event
                publishConversationEvent("updated", conversation);
            });
    }
    
    /**
     * Get conversation history (messages).
     */
    public Uni<JsonArray> getConversationHistory(String conversationId, String userId, int limit, int offset) {
        
        // Verify conversation access (owner or active participant)
        return serviceHelper.verifyConversationAccess(conversationId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }

                String query = """
                    SELECT m.*,
                           me.editedAt as lastEditTime,
                           me.editCount,
                           u.displayName as userDisplayName,
                           ch.name as characterName
                    FROM message m
                    LEFT JOIN (
                        SELECT messageId, MAX(editedAt) as editedAt, COUNT(*) as editCount
                        FROM message_edit
                        GROUP BY messageId
                    ) me ON m.messageUUID = me.messageId
                    LEFT JOIN user u ON m.ownerId = u.userUUID
                    LEFT JOIN conversation c ON m.conversationId = c.conversationGUID
                    LEFT JOIN `character` ch ON COALESCE(m.characterId, c.characterId) = ch.characterUUID
                    WHERE m.conversationId = ?
                    ORDER BY m.seq ASC
                    LIMIT ? OFFSET ?
                    """;

                return pool.preparedQuery(query)
                    .execute(Tuple.of(conversationId, limit, offset));
            })
            .map(rows -> {
                JsonArray messages = new JsonArray();

                rows.forEach(row -> {
                    JsonObject message = new JsonObject()
                        .put("id", row.getString("messageUUID"))
                        .put("conversationId", row.getString("conversationId"))
                        .put("content", decodeMessageContent(row))
                        .put("role", row.getString("role"))
                        .put("displayName", row.getString("displayName"))
                        .put("createdAt", Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                        .put("isBase64Encoded", row.getBoolean("isBase64Encoded"))
                        .put("editCount", row.getInteger("editCount"))
                        .put("lastEditTime", row.getLocalDateTime("lastEditTime") != null ?
                            Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastEditTime")) : null);

                    // Add character info for assistant messages
                    if ("assistant".equals(row.getString("role"))) {
                        message.put("characterName", row.getString("characterName"));
                    } else {
                        message.put("userDisplayName", row.getString("userDisplayName"));
                    }

                    messages.add(message);
                });

                return messages;
            });
    }
    
    /**
     * Delete conversation (soft delete).
     */
    public Uni<Void> deleteConversation(String conversationId, String userId) {
        
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String updateQuery = """
            UPDATE conversation
            SET active = false, lastModified = ?, updatedAt = ?
            WHERE conversationGUID = ? AND ownerId = ?
            """;

        return pool.preparedQuery(updateQuery)
            .execute(Tuple.of(now, now, conversationId, userId))
            .chain(result -> {
                if (result.rowCount() == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                }

                // Invalidate cache
                invalidateConversationCache(conversationId);

                // Notify the conversationDeleted subscription consumer.
                // SubscriptionResolver registers on "user:<userId>:conversations:deleted"
                // and reads the body as a raw UUID string.
                vertx.eventBus().publish("user:" + userId + ":conversations:deleted", conversationId);

                // Publish delete event
                publishConversationEvent("deleted", new JsonObject()
                    .put("conversationId", conversationId)
                    .put("userId", userId));

                // Track metric using ServiceHelper to reduce duplication
                serviceHelper.trackMetric("metrics.conversation.deleted", new JsonObject()
                    .put("conversationId", conversationId)
                    .put("userId", userId));

                return Uni.createFrom().voidItem();
            });
    }
    
    /**
     * Clear conversation history.
     */
    public Uni<Void> clearHistory(String conversationId, String userId) {
        
        // Verify ownership
        return verifyConversationOwnership(conversationId, userId)
            .chain(owned -> {
                if (!owned) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }

                // Start transaction
                return pool.withTransaction(client -> {
                    // Delete message edits first
                    return client.preparedQuery("""
                        DELETE FROM message_edit
                        WHERE messageId IN (
                            SELECT messageUUID FROM message WHERE conversationId = ?
                        )
                        """)
                        .execute(Tuple.of(conversationId))
                        .chain(v -> {
                            // Delete messages
                            return client.preparedQuery("DELETE FROM message WHERE conversationId = ?")
                                .execute(Tuple.of(conversationId));
                        })
                        .chain(v -> {
                            // Update conversation
                            LocalDateTime clearTime = LocalDateTime.now(ZoneOffset.UTC);
                            return client.preparedQuery("""
                                UPDATE conversation
                                SET lastModified = ?, updatedAt = ?
                                WHERE conversationGUID = ?
                                """)
                                .execute(Tuple.of(clearTime, clearTime, conversationId));
                        })
                        .replaceWithVoid();
                });
            })
            .invoke(v -> {
                // Invalidate cache
                invalidateConversationCache(conversationId);
                invalidateMessageCache(conversationId);

                // Publish event
                publishConversationEvent("history_cleared", new JsonObject()
                    .put("conversationId", conversationId)
                    .put("userId", userId));

                log.info("Cleared history for conversation: {}", conversationId);
            });
    }
    
    /**
     * Switch character for conversation.
     */
    public Uni<Conversation> switchCharacter(String conversationId, String userId, String newCharacterId) {
        
        // Verify character access
        Uni<Boolean> characterCheck = newCharacterId != null ?
            verifyCharacterAccess(userId, newCharacterId) :
            Uni.createFrom().item(true);
        
        return characterCheck
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Character not found or access denied"));
                }

                LocalDateTime switchTime = LocalDateTime.now(ZoneOffset.UTC);
                String updateQuery = """
                    UPDATE conversation
                    SET characterId = ?, lastModified = ?, updatedAt = ?
                    WHERE conversationGUID = ? AND ownerId = ?
                    """;

                return pool.preparedQuery(updateQuery)
                    .execute(Tuple.of(newCharacterId, switchTime, switchTime, conversationId, userId));
            })
            .chain(result -> {
                if (result.rowCount() == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                }

                // Invalidate cache
                invalidateConversationCache(conversationId);

                // Get updated conversation
                return getConversation(conversationId, userId);
            })
            .invoke(conversation -> {
                // Publish character switch event
                publishConversationEvent("character_switched", new JsonObject()
                    .put("conversationId", conversationId)
                    .put("userId", userId)
                    .put("characterId", newCharacterId));
            });
    }
    
    /**
     * Export conversation as JSON.
     */
    public Uni<JsonObject> exportConversation(String conversationId, String userId) {
        
        return getConversation(conversationId, userId)
            .chain(conversation -> {
                JsonObject export = new JsonObject()
                    .put("conversation", JsonObject.mapFrom(conversation))
                    .put("exportedAt", LocalDateTime.now(ZoneOffset.UTC).toString())
                    .put("version", "1.0");

                // Get all messages
                return getConversationHistory(conversationId, userId, Integer.MAX_VALUE, 0)
                    .map(messages -> {
                        export.put("messages", messages);
                        export.put("messageCount", messages.size());
                        return export;
                    });
            })
            .chain(export -> {
                // Get character info if present
                String characterId = export.getJsonObject("conversation").getString("characterId");
                if (characterId != null) {
                    return getCharacterInfo(characterId)
                        .map(character -> {
                            export.put("character", character);
                            return export;
                        });
                }
                return Uni.createFrom().item(export);
            });
    }
    
    /**
     * Get conversation statistics.
     */
    public Uni<JsonObject> getConversationStats(String conversationId, String userId) {
        
        // Verify ownership
        return verifyConversationOwnership(conversationId, userId)
            .chain(owned -> {
                if (!owned) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }

                String query = """
                    SELECT
                        COUNT(*) as totalMessages,
                        SUM(CASE WHEN role = 'user' THEN 1 ELSE 0 END) as userMessages,
                        SUM(CASE WHEN role = 'assistant' THEN 1 ELSE 0 END) as assistantMessages,
                        SUM(LENGTH(content)) as totalCharacters,
                        AVG(LENGTH(content)) as avgMessageLength,
                        MIN(createdAt) as firstMessageTime,
                        MAX(createdAt) as lastMessageTime,
                        COUNT(DISTINCT DATE(createdAt)) as activeDays
                    FROM message
                    WHERE conversationId = ?
                    """;

                return pool.preparedQuery(query)
                    .execute(Tuple.of(conversationId));
            })
            .map(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    return new JsonObject()
                        .put("conversationId", conversationId)
                        .put("totalMessages", row.getLong(0))
                        .put("userMessages", row.getLong(1))
                        .put("assistantMessages", row.getLong(2))
                        .put("totalCharacters", row.getLong(3))
                        .put("avgMessageLength", row.getDouble(4))
                        .put("firstMessageTime", row.getLocalDateTime(5) != null ?
                            Time4JHelper.localDateTimeToString(row.getLocalDateTime(5)) : null)
                        .put("lastMessageTime", row.getLocalDateTime(6) != null ?
                            Time4JHelper.localDateTimeToString(row.getLocalDateTime(6)) : null)
                        .put("activeDays", row.getLong(7));
                } else {
                    return new JsonObject();
                }
            });
    }
    
    /**
     * Search conversations by content.
     */
    public Uni<JsonArray> searchConversations(String userId, String searchQuery, int limit) {
        
        String query = """
            SELECT DISTINCT c.*,
                   COUNT(m.messageUUID) as matchCount,
                   MAX(m.createdAt) as lastMatchTime
            FROM conversation c
            INNER JOIN message m ON c.conversationGUID = m.conversationId
            WHERE c.ownerId = ?
                AND c.active = true
                AND (c.title LIKE ? OR m.content LIKE ?)
            GROUP BY c.conversationGUID
            ORDER BY matchCount DESC, c.lastModified DESC
            LIMIT ?
            """;
        
        String searchPattern = "%" + searchQuery + "%";
        
        return pool.preparedQuery(query)
            .execute(Tuple.of(userId, searchPattern, searchPattern, limit))
            .map(rows -> {
                JsonArray results = new JsonArray();

                rows.forEach(row -> {
                    JsonObject result = new JsonObject()
                        .put("id", row.getString("conversationGUID"))
                        .put("title", row.getString("title"))
                        .put("created", Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                        .put("lastModified", Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastModified")))
                        .put("matchCount", row.getInteger("matchCount"))
                        .put("lastMatchTime", row.getLocalDateTime("lastMatchTime") != null ?
                            Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastMatchTime")) : null);

                    results.add(result);
                });

                return results;
            });
    }
    
    // Helper methods - Delegates to ServiceHelper to reduce duplication

    /**
     * Verify conversation ownership.
     * Delegates to ServiceHelper.verifyConversationOwnership() to eliminate duplicate code.
     */
    private Uni<Boolean> verifyConversationOwnership(String conversationId, String userId) {
        return serviceHelper.verifyConversationOwnership(conversationId, userId);
    }

    /**
     * Verify character access (owner or public).
     * Delegates to ServiceHelper.verifyCharacterAccess() to eliminate duplicate code.
     */
    private Uni<Boolean> verifyCharacterAccess(String userId, String characterId) {
        return serviceHelper.verifyCharacterAccess(characterId, userId);
    }

    private Uni<JsonObject> getCharacterInfo(String characterId) {
        
        String query = """
            SELECT characterUUID, name, description, systemPrompt, greeting
            FROM `character`
            WHERE characterUUID = ?
            """;

        return pool.preparedQuery(query)
            .execute(Tuple.of(characterId))
            .map(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    return new JsonObject()
                        .put("id", row.getString("characterUUID"))
                        .put("name", row.getString("name"))
                        .put("description", row.getString("description"))
                        .put("systemPrompt", row.getString("systemPrompt"))
                        .put("greeting", row.getString("greeting"));
                } else {
                    return new JsonObject();
                }
            })
            .onFailure().recoverWithItem(error -> new JsonObject());
    }
    
    private String decodeMessageContent(Row row) {
        String content = row.getString("content");
        Boolean isBase64 = row.getBoolean("isBase64Encoded");
        
        if (Boolean.TRUE.equals(isBase64) && content != null) {
            try {
                byte[] decoded = java.util.Base64.getDecoder().decode(content);
                return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("Failed to decode Base64 content for message", e);
                return content;
            }
        }
        
        return content;
    }
    
    private Conversation mapRowToConversation(Row row) {
        Conversation conversation = new Conversation();
        conversation.setId(UUID.fromString(row.getString("conversationGUID")));
        conversation.setOwnerId(UUID.fromString(row.getString("ownerId")));
        conversation.setTitle(row.getString("title"));
        conversation.setDescription(row.getString("description"));
        conversation.setIsPinned(row.getBoolean("isPinned"));
        conversation.setIsArchived(row.getBoolean("isArchived"));
        conversation.setCategory(row.getString("category"));
        conversation.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        conversation.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        conversation.setLastModified(Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastModified")));

        String characterIdStr = row.getString("characterId");
        if (characterIdStr != null && !characterIdStr.isEmpty()) {
            conversation.setCharacterId(UUID.fromString(characterIdStr));
        }

        conversation.setActive(row.getBoolean("active"));
        conversation.setMessageCount(row.getInteger("messageCount"));

        // Add character info if present
        if (row.getString("characterName") != null) {
            conversation.setCharacterName(row.getString("characterName"));
            conversation.setCharacterDescription(row.getString("characterDescription"));
        }

        // Add last message time if present
        if (row.getLocalDateTime("lastMessageTime") != null) {
            conversation.setLastMessageTime(Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastMessageTime")));
        }
        if (row.getLocalDateTime("lastMessageAt") != null) {
            conversation.setLastMessageAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastMessageAt")));
        }

        // Group chat fields with enum normalization to UPPER_CASE
        conversation.setIsGroupChat(row.getBoolean("isGroupChat"));
        String turnMode = row.getString("turnMode");
        conversation.setTurnMode(turnMode != null ? turnMode.toUpperCase() : null);
        String groupState = row.getString("groupState");
        conversation.setGroupState(groupState != null ? groupState.toUpperCase() : null);
        conversation.setMaxConsecutiveResponses(row.getInteger("maxConsecutiveResponses"));
        conversation.setConsecutiveResponseCount(row.getInteger("consecutiveResponseCount"));

        String currentSpeakerId = row.getString("currentSpeakerId");
        if (currentSpeakerId != null) {
            conversation.setCurrentSpeakerId(UUID.fromString(currentSpeakerId));
        }
        String lastSpeakerId = row.getString("lastSpeakerId");
        if (lastSpeakerId != null) {
            conversation.setLastSpeakerId(UUID.fromString(lastSpeakerId));
        }

        // Roleplay fields
        conversation.setIsRoleplay(row.getBoolean("isRoleplay"));
        String storyTemplateId = row.getString("storyTemplateId");
        if (storyTemplateId != null) {
            conversation.setStoryTemplateId(storyTemplateId);
        }
        String activeSceneId = row.getString("activeSceneId");
        if (activeSceneId != null) {
            conversation.setActiveSceneId(UUID.fromString(activeSceneId));
        }
        conversation.setRpMode(row.getString("rpMode"));
        conversation.setAgentPipelineEnabled(row.getBoolean("agentPipelineEnabled"));

        return conversation;
    }

    // ================================================================
    // Cache and Event Helper Delegates (using utilities to reduce duplication)
    // ================================================================

    // ================================================================
    // Additional Operations (moved from ConversationServiceVerticle)
    // ================================================================

    /**
     * Mark a conversation as read by updating the lastRead timestamp.
     * Moved from ConversationServiceVerticle to centralize business logic.
     *
     * @param conversationId The conversation ID
     * @param userId The user ID
     * @return Uni<Boolean> true if updated successfully
     */
    public Uni<Boolean> markConversationAsRead(String conversationId, String userId) {

        String query = """
            UPDATE conversation
            SET lastModified = NOW()
            WHERE conversationGUID = ? AND ownerId = ?
            """;

        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId, userId))
            .map(result -> result.rowCount() > 0)
            .onFailure().invoke(error -> {
                log.error("Failed to mark conversation {} as read for user {}", conversationId, userId, error);
            });
    }

    /**
     * Get the count of unread conversations for a user.
     * Moved from ConversationServiceVerticle to centralize business logic.
     *
     * @param userId The user ID
     * @return Uni<Long> count of unread conversations
     */
    public Uni<Long> getUnreadConversationCount(String userId) {

        String query = """
            SELECT COUNT(*) as unreadCount
            FROM conversation c
            WHERE c.ownerId = ?
                AND c.active = true
                AND EXISTS (
                    SELECT 1 FROM message m
                    WHERE m.conversationId = c.conversationGUID
                    AND m.createdAt > COALESCE(c.lastModified, '1970-01-01')
                )
            """;

        return pool.preparedQuery(query)
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().next().getLong("unreadCount"))
            .onFailure().invoke(error -> {
                log.error("Failed to get unread count for user {}", userId, error);
            });
    }

    /**
     * Archive or unarchive a conversation.
     * Moved from ConversationServiceVerticle to centralize business logic.
     *
     * @param conversationId The conversation ID
     * @param userId The user ID
     * @param archive true to archive, false to unarchive
     * @return Uni<Boolean> true if updated successfully
     */
    public Uni<Boolean> archiveConversation(String conversationId, String userId, boolean archive) {

        String query = """
            UPDATE conversation
            SET isArchived = ?, lastModified = NOW()
            WHERE conversationGUID = ? AND ownerId = ?
            """;

        return pool.preparedQuery(query)
            .execute(Tuple.of(archive, conversationId, userId))
            .map(result -> {
                if (result.rowCount() > 0) {
                    // Invalidate cache
                    invalidateConversationCache(conversationId);
                    return true;
                } else {
                    return false;
                }
            })
            .onFailure().invoke(error -> {
                log.error("Failed to {} conversation {} for user {}",
                    archive ? "archive" : "unarchive", conversationId, userId, error);
            });
    }

    /**
     * Invalidate conversation cache.
     * Delegates to CacheHelper to eliminate duplicate code.
     */
    private void invalidateConversationCache(String conversationId) {
        cacheHelper.invalidate(conversationId);
    }

    /**
     * Invalidate message cache for a conversation.
     */
    private void invalidateMessageCache(String conversationId) {
        String pattern = "messages:" + conversationId + "*";
        vertx.eventBus().send("redis.cache.invalidate", new JsonObject()
            .put("pattern", pattern));
    }

    /**
     * Publish conversation event.
     * Delegates to ServiceHelper to eliminate duplicate code.
     */
    private void publishConversationEvent(String eventType, Object data) {
        serviceHelper.publishEvent("conversation:events", eventType, data);

        // Publish to per-conversation channel for conversationUpdates subscription
        if (data instanceof Conversation conv) {
            JsonObject event = new JsonObject()
                .put("id", conv.getId().toString())
                .put("ownerId", conv.getOwnerId().toString())
                .put("title", conv.getTitle())
                .put("messageCount", conv.getMessageCount() != null ? conv.getMessageCount() : 0)
                .put("characterId", conv.getCharacterId() != null ? conv.getCharacterId().toString() : null)
                .put("createdAt", conv.getCreatedAt() != null ? conv.getCreatedAt().toString() : null)
                .put("updatedAt", conv.getUpdatedAt() != null ? conv.getUpdatedAt().toString() : null);
            vertx.eventBus().publish("conversation:" + conv.getId().toString() + ":updated", event);
        }
    }
}