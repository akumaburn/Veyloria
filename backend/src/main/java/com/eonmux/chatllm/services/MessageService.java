package com.eonmux.chatllm.services;

import jakarta.annotation.PostConstruct;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.Message;
import com.eonmux.chatllm.models.MessageType;
import com.eonmux.chatllm.models.MessageRole;
import com.eonmux.chatllm.models.MessageEdit;
import com.eonmux.chatllm.models.Reaction;
import com.eonmux.chatllm.utils.Base64Helper;
import com.eonmux.chatllm.utils.CacheHelper;
import com.eonmux.chatllm.utils.ServiceHelper;
import com.eonmux.chatllm.utils.ValidationHelper;
import io.smallrye.mutiny.Uni;

import io.vertx.mutiny.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.redis.client.RedisAPI;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Service for message-related operations.
 * Handles message creation, editing, deletion, and Base64 encoding.
 *
 * Uses CacheHelper and ServiceHelper to reduce code duplication.
 */
@ApplicationScoped
@Slf4j
public class MessageService {

    private static final int MAX_EDIT_COUNT = 10;
    private static final long EDIT_TIME_LIMIT_MINUTES = 5;
    private static final int CACHE_TTL = 300; // 5 minutes
    private static final Pattern URL_PATTERN = Pattern.compile(
        "https?://[\\w.-]+(?:\\.[\\w\\.-]+)+[\\w\\-\\._~:/?#\\[\\]@!\\$&'\\(\\)\\*\\+,;=]+"
    );

    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    @Inject
    RedisAPI redis;
    @Inject
    com.eonmux.chatllm.config.AppConfig appConfig;
    // EventBus obtained from vertx.eventBus()

    // Utility helpers to reduce code duplication
    private CacheHelper<Message> cacheHelper;
    @Inject
    ServiceHelper serviceHelper;

    
    @PostConstruct
    void init() {
        this.cacheHelper = new CacheHelper<>(vertx.eventBus(), "message", CACHE_TTL, Message.class);
    }

    /**
     * Send a new message.
     */
    public Uni<Message> sendMessage(String conversationId, String userId, String content, 
                                       String role, boolean encodeBase64) {
        
        // Validate content
        if (content == null || content.trim().isEmpty()) {
            return Uni.createFrom().failure(new RuntimeException("Message content cannot be empty"));
    }
        
        if (content.length() > ValidationHelper.MAX_MESSAGE_LENGTH) {
            return Uni.createFrom().failure(new RuntimeException("Message exceeds maximum length of " + ValidationHelper.MAX_MESSAGE_LENGTH + " characters"));
    }
        
        // Validate role - throw exception instead of silent fallback (Issue #10 fix)
        if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) {
            return Uni.createFrom().failure(new RuntimeException("Invalid message role: " + role + ". Must be one of: user, assistant, system"));
    }
        
        final String finalRole = role;
        
        // Verify conversation ownership
        return verifyConversationAccess(conversationId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                }
                
                String messageId = UUID.randomUUID().toString();
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                
                // Encode content if requested using Base64Helper
                final String processedContent;
                final boolean isBase64Encoded;

                if (encodeBase64 || shouldAutoEncode(content)) {
                    processedContent = Base64Helper.encode(content);
                    isBase64Encoded = true;
                } else {
                    processedContent = content;
                    isBase64Encoded = false;
                }
                
                // Get display name
                return getDisplayName(userId, finalRole)
                    .chain(displayName -> {
                        String insertQuery = """
                            INSERT INTO message (
                                messageUUID, conversationId, ownerId, content,
                                role, displayName, createdAt, isBase64Encoded, editCount
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """;
                        
                        return pool.preparedQuery(insertQuery)
                            .execute(Tuple.tuple()
                                .addValue(messageId)
                                .addValue(conversationId)
                                .addValue(userId)
                                .addValue(processedContent)
                                .addValue(finalRole)
                                .addValue(displayName)
                                .addValue(now)
                                .addValue(isBase64Encoded)
                                .addValue(0))
                            .map(result -> {
                                Message message = new Message();
                                message.setId(UUID.fromString(messageId));
                                message.setConversationId(UUID.fromString(conversationId));
                                message.setOwnerId(UUID.fromString(userId));
                                message.setContent(content); // Return original content
                                message.setRole(MessageRole.fromString(finalRole));
                                message.setDisplayName(displayName);
                                message.setCreatedAt(now.toString());
                                message.setIsBase64Encoded(isBase64Encoded);
                                message.setEditCount(0);
                                // New messages are editable
                                
                                return message;
                            });
                    });
            })
            .chain(message -> {
                // Update conversation last modified
                updateConversationTimestamp(conversationId);
                
                // Invalidate cache
                invalidateMessageCache(conversationId);

                // Track metric using ServiceHelper to reduce duplication
                serviceHelper.trackMetric("metrics.message.sent", new JsonObject()
                    .put("userId", userId)
                    .put("conversationId", conversationId)
                    .put("role", finalRole)
                    .put("size", content.length()));
                
                // Publish message event for real-time updates
                publishMessageEvent("sent", message);
                
                // If user message, trigger LLM response
                if ("user".equals(finalRole)) {
                    triggerLLMResponse(conversationId, userId, message);
                }
                
                return Uni.createFrom().item(message);
            })
            
            .onFailure().invoke(error -> {
                log.error("Failed to send message for conversation: {}", conversationId, error);
            });
    }
    
    /**
     * Edit an existing message.
     */
    public Uni<Message> editMessage(String messageId, String userId, String newContent) {
        
        if (newContent == null || newContent.trim().isEmpty()) {
            return Uni.createFrom().failure(new RuntimeException("Message content cannot be empty"));
    }
        
        if (newContent.length() > ValidationHelper.MAX_MESSAGE_LENGTH) {
            return Uni.createFrom().failure(new RuntimeException("Message exceeds maximum length"));
    }
        
        // Get original message
        return getMessageById(messageId)
            .chain(original -> {
                // Verify ownership - only users can edit their own messages
                // Assistant/system messages (ownerId is null) cannot be edited
                if (!ServiceHelper.isOwner(original.getOwnerId(), userId)) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }

                // Check if message is editable
                if (!isMessageEditable(original)) {
                    return Uni.createFrom().failure(new RuntimeException("Message is no longer editable"));
                }
                
                // Check edit count
                if (original.getEditCount() >= MAX_EDIT_COUNT) {
                    return Uni.createFrom().failure(new RuntimeException("Maximum edit limit reached"));
                }
                
                // Save edit history. newContent is stored in the same form the
                // message will be persisted in (encoded if the message is
                // Base64-encoded), so previousContent/newContent in history are
                // symmetric with what the message.content column actually holds.
                // getEditHistory() decodes both sides before returning them.
                String processedForHistory = newContent;
                if (original.getIsBase64Encoded() || shouldAutoEncode(newContent)) {
                    processedForHistory = Base64Helper.encode(newContent);
                }
                return saveEditHistory(messageId, original.getContent(), processedForHistory, userId)
                    .chain(v -> {
                        // Update message
                        String processedContent = newContent;
                        boolean isBase64Encoded = false;

                        if (original.getIsBase64Encoded() || shouldAutoEncode(newContent)) {
                            processedContent = Base64Helper.encode(newContent);
                            isBase64Encoded = true;
                        }

                        String updateQuery = """
                            UPDATE message
                            SET content = ?, isBase64Encoded = ?, isEdited = true, editCount = editCount + 1, updatedAt = ?
                            WHERE messageUUID = ?
                            """;

                        return pool.preparedQuery(updateQuery)
                            .execute(Tuple.of(processedContent, isBase64Encoded, LocalDateTime.now(ZoneOffset.UTC), messageId));
                    })
                    .chain(result -> {
                        // Get updated message
                        return getMessageById(messageId);
                    });
            })
            .invoke(message -> {
                // Invalidate cache
                invalidateMessageCache(message.getConversationId().toString());

                // Publish edit event on the per-conversation channel the
                // `messageEdited` subscription listens on. Shape must match
                // SubscriptionResolver.messageEdited (id / content / editCount).
                vertx.eventBus().publish(
                    "conversation:" + message.getConversationId() + ":message:edited",
                    new JsonObject()
                        .put("id", message.getId().toString())
                        .put("content", message.getContent())
                        .put("editCount", message.getEditCount()));

                // Generic message-events channel (Redis pub/sub + legacy bus address)
                publishMessageEvent("edited", message);

                // Track metric using ServiceHelper to reduce duplication
                serviceHelper.trackMetric("metrics.message.edited", new JsonObject()
                    .put("messageId", messageId)
                    .put("userId", userId)
                    .put("editCount", message.getEditCount()));
            });
    }
    
    /**
     * Delete a message (soft delete).
     */
    public Uni<Void> deleteMessage(String messageId, String userId) {
        
        // Get message
        return getMessageById(messageId)
            .chain(message -> {
                // Verify ownership - only users can delete their own messages
                // Assistant/system messages (ownerId is null) cannot be deleted by users
                if (!ServiceHelper.isOwner(message.getOwnerId(), userId)) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }

                // Soft delete by replacing content
                String deletedContent = "[Message deleted]";
                String updateQuery = """
                    UPDATE message
                    SET content = ?, isDeleted = true, deletedAt = ?
                    WHERE messageUUID = ?
                    """;

                return pool.preparedQuery(updateQuery)
                    .execute(Tuple.of(deletedContent, LocalDateTime.now(ZoneOffset.UTC), messageId))
                    .replaceWith(message);
            })
            .invoke(message -> {
                // Invalidate cache
                invalidateMessageCache(message.getConversationId().toString());

                // Publish delete event on the per-conversation channel the
                // `messageDeleted` subscription listens on. The consumer body
                // type is `String` (raw UUID), not JsonObject.
                vertx.eventBus().publish(
                    "conversation:" + message.getConversationId() + ":message:deleted",
                    messageId);

                // Generic message-events channel (Redis pub/sub + legacy bus address)
                publishMessageEvent("deleted", new JsonObject()
                    .put("messageId", messageId)
                    .put("conversationId", message.getConversationId().toString()));
            })
            .replaceWithVoid();
    }
    
    /**
     * Get message by ID.
     */
    public Uni<Message> getMessageById(String messageId) {
        
        // Try cache first
        String cacheKey = "message:" + messageId;
        
        return getCachedMessage(cacheKey)
            .chain(cached -> {
                if (cached != null) {
                    return Uni.createFrom().item(cached);
                }

                String query = """
                    SELECT m.*,
                           u.displayName as userDisplayName,
                           (SELECT COUNT(*) FROM message_edit WHERE messageId = m.messageUUID) as totalEdits
                    FROM message m
                    LEFT JOIN user u ON m.ownerId = u.userUUID
                    WHERE m.messageUUID = ?
                    """;

                return pool.preparedQuery(query)
                    .execute(Tuple.of(messageId))
                    .map(rows -> {
                        if (rows.size() == 0) {
                            throw new RuntimeException("Message not found");
                        }

                        Row row = rows.iterator().next();
                        Message message = mapRowToMessage(row);

                        // Cache the message
                        cacheMessage(cacheKey, message);

                        return message;
                    });
            });
    }
    
    /**
     * Get edit history for a message.
     * Access allowed for: message owner OR conversation owner (Issue #9 fix).
     */
    public Uni<JsonArray> getEditHistory(String messageId, String userId) {

        // Verify message ownership OR conversation ownership - expanded access control
        return getMessageById(messageId)
            .chain(message -> {
                UUID ownerId = message.getOwnerId();
                String conversationId = message.getConversationId().toString();

                // Check if user is message owner
                if (ownerId != null && ownerId.toString().equals(userId)) {
                    // User is message owner - allow access
                    return Uni.createFrom().item(message);
                }

                // Check if user is conversation owner (for moderation purposes)
                return verifyConversationAccess(conversationId, userId)
                    .chain(isConversationOwner -> {
                        if (isConversationOwner) {
                            // User is conversation owner - allow access
                            return Uni.createFrom().item(message);
                        } else {
                            return Uni.createFrom().failure(new RuntimeException("Access denied: you must be the message owner or conversation owner to view edit history"));
                        }
                    });
            })
            .chain(message -> {
                String query = """
                    SELECT editId, previousContent, newContent, editedAt, editedBy
                    FROM message_edit
                    WHERE messageId = ?
                    ORDER BY editedAt ASC
                    """;

                return pool.preparedQuery(query)
                    .execute(Tuple.of(messageId));
            })
            .map(rows -> {
                JsonArray history = new JsonArray();
                int[] editNumber = {1};

                for (Row row : rows) {
                    String previousContent = row.getString("previousContent");
                    String newContent = row.getString("newContent");

                    // Decode if Base64 using Base64Helper (both sides; previousContent
                    // is stored encoded when the original was, and newContent keeps
                    // whatever encoding the edit used).
                    previousContent = Base64Helper.decodeIfBase64(previousContent);
                    newContent = Base64Helper.decodeIfBase64(newContent);

                    history.add(new JsonObject()
                        .put("editId", row.getString("editId"))
                        .put("content", previousContent)
                        .put("newContent", newContent)
                        .put("editedAt", Time4JHelper.localDateTimeToString(row.getLocalDateTime("editedAt")))
                        .put("editedBy", row.getString("editedBy"))
                        .put("editNumber", editNumber[0]++));
                }

                return history;
            });
    }
    
    /**
     * Stream message response from LLM.
     */
    public Uni<Void> streamLLMResponse(String conversationId, String userId, String messageId) {

        // Create assistant message placeholder
        String assistantMessageId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Note: ownerId is set to NULL for assistant messages.
        // This is intentional - assistant/system messages should not be editable/deletable by users.
        // See editMessage() and deleteMessage() which verify ownerId != null for user-owned messages.
        String insertQuery = """
            INSERT INTO message (
                messageUUID, conversationId, ownerId, content,
                role, displayName, createdAt, isBase64Encoded, editCount, isStreaming
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        return pool.preparedQuery(insertQuery)
            .execute(Tuple.tuple()
                .addValue(assistantMessageId)
                .addValue(conversationId)
                .addValue(null) // NULL ownerId for assistant messages
                .addValue("") // Empty content initially
                .addValue("assistant")
                .addValue("Assistant")
                .addValue(now)
                .addValue(false)
                .addValue(0)
                .addValue(true)) // Mark as streaming
            .chain(result -> {
                // Request LLM generation (can take longer than default 30s timeout)
                JsonObject llmRequest = new JsonObject()
                    .put("conversationId", conversationId)
                    .put("messageId", assistantMessageId)
                    .put("userId", userId)
                    .put("content", ""); // Will be populated from history

                // Idle-based timeout is enforced inside LLMBridge; pad past
                // the absolute backend cap so the originator never gives up first.
                long replyTimeoutMs = appConfig.getLlmRequestTimeout() + 30_000L;
                DeliveryOptions deliveryOptions = new DeliveryOptions().setSendTimeout(replyTimeoutMs);
                return vertx.eventBus().<JsonObject>request("llm.generate", llmRequest, deliveryOptions);
            })
            .chain(reply -> {
                JsonObject response = reply.body();
                String content = response.getString("content");
                
                // Update message with final content
                String updateQuery = """
                    UPDATE message 
                    SET content = ?, isStreaming = false
                    WHERE messageUUID = ?
                    """;
                
                return pool.preparedQuery(updateQuery)
                    .execute(Tuple.of(content, assistantMessageId));
            })
            .chain(result -> {
                // Invalidate cache
                invalidateMessageCache(conversationId);
                
                // Update conversation timestamp
                updateConversationTimestamp(conversationId);
                
                return Uni.createFrom().voidItem();
            })
            
            .onFailure().invoke(error -> log.error("Failed to stream LLM response", error));
    }
    
    /**
     * React to a message (like, dislike, etc).
     * Returns the created/updated Reaction.
     *
     * NOTE: This operation uses a database transaction to ensure atomicity.
     * The event is published only after successful commit.
     */
    public Uni<Reaction> reactToMessage(String messageId, String userId, String reaction) {

        String reactionId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Verify message exists first (outside transaction)
        return getMessageById(messageId)
            .chain(message -> {
                // Use transaction to ensure atomicity of reaction upsert
                return pool.withTransaction(client -> {
                    // Store reaction in database with INSERT ... ON DUPLICATE KEY UPDATE
                    String upsertQuery = """
                        INSERT INTO message_reaction (id, message_id, user_id, reaction_type, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE reaction_type = ?, created_at = ?, id = ?
                        """;

                    return client.preparedQuery(upsertQuery)
                        .execute(Tuple.tuple()
                            .addValue(reactionId)
                            .addValue(messageId)
                            .addValue(userId)
                            .addValue(reaction)
                            .addValue(now)
                            .addValue(reaction)
                            .addValue(now)
                            .addValue(reactionId))
                        .map(result -> {
                            // Build Reaction object to return
                            return Reaction.builder()
                                .id(UUID.fromString(reactionId))
                                .messageId(UUID.fromString(messageId))
                                .userId(UUID.fromString(userId))
                                .reactionType(reaction)
                                .createdAt(now.toString())
                                .build();
                        });
                });
            })
            .invoke(reactionObj -> {
                // Publish reaction event only after successful transaction commit
                // Note: If event publish fails, we log but don't rollback the database
                // since the transaction is already committed
                try {
                    publishMessageEvent("reaction", new JsonObject()
                        .put("messageId", messageId)
                        .put("userId", userId)
                        .put("reaction", reaction)
                        .put("action", "add"));
                } catch (Exception e) {
                    log.warn("Failed to publish reaction event, but reaction was saved: {}", e.getMessage());
                }
            });
    }

    /**
     * Remove a reaction from a message.
     *
     * NOTE: This operation uses a database transaction to ensure atomicity.
     * The event is published only after successful commit.
     */
    public Uni<Boolean> removeReaction(String messageId, String userId, String reaction) {

        // Use transaction to ensure atomicity
        return pool.withTransaction(client -> {
            String deleteQuery = """
                DELETE FROM message_reaction
                WHERE message_id = ? AND user_id = ? AND reaction_type = ?
                """;

            return client.preparedQuery(deleteQuery)
                .execute(Tuple.of(messageId, userId, reaction))
                .map(result -> result.rowCount() > 0);
        })
        .invoke(deleted -> {
            if (deleted) {
                // Publish reaction removal event only after successful transaction commit
                try {
                    publishMessageEvent("reaction", new JsonObject()
                        .put("messageId", messageId)
                        .put("userId", userId)
                        .put("reaction", reaction)
                        .put("action", "remove"));
                } catch (Exception e) {
                    log.warn("Failed to publish reaction removal event, but reaction was removed: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * Get all reactions for a message.
     */
    public Uni<List<Reaction>> getReactionsForMessage(String messageId) {

        String query = """
            SELECT id, message_id, user_id, reaction_type, created_at
            FROM message_reaction
            WHERE message_id = ?
            ORDER BY created_at ASC
            """;

        return pool.preparedQuery(query)
            .execute(Tuple.of(messageId))
            .map(rows -> {
                List<Reaction> reactions = new ArrayList<>();
                for (Row row : rows) {
                    reactions.add(Reaction.builder()
                        .id(UUID.fromString(row.getString("id")))
                        .messageId(UUID.fromString(row.getString("message_id")))
                        .userId(UUID.fromString(row.getString("user_id")))
                        .reactionType(row.getString("reaction_type"))
                        .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("created_at")))
                        .build());
                }
                return reactions;
            });
    }

    /**
     * Get reaction summary for a message (grouped by reaction type).
     */
    public Uni<List<JsonObject>> getReactionSummary(String messageId, String userId) {

        String query = """
            SELECT
                reaction_type,
                COUNT(*) as count,
                MAX(CASE WHEN user_id = ? THEN 1 ELSE 0 END) as user_reacted
            FROM message_reaction
            WHERE message_id = ?
            GROUP BY reaction_type
            ORDER BY count DESC
            """;

        return pool.preparedQuery(query)
            .execute(Tuple.of(userId, messageId))
            .map(rows -> {
                List<JsonObject> summary = new ArrayList<>();
                for (Row row : rows) {
                    summary.add(new JsonObject()
                        .put("reactionType", row.getString("reaction_type"))
                        .put("count", row.getLong("count"))
                        .put("userReacted", row.getLong("user_reacted") > 0));
                }
                return summary;
            });
    }
    
    /**
     * Get message statistics.
     */
    public Uni<JsonObject> getMessageStats(String conversationId) {
        
        String query = """
            SELECT
                COUNT(*) as totalMessages,
                SUM(CASE WHEN role = 'user' THEN 1 ELSE 0 END) as userMessages,
                SUM(CASE WHEN role = 'assistant' THEN 1 ELSE 0 END) as assistantMessages,
                SUM(editCount) as totalEdits,
                AVG(LENGTH(content)) as avgLength,
                MAX(LENGTH(content)) as maxLength,
                SUM(CASE WHEN isBase64Encoded = true THEN 1 ELSE 0 END) as encodedMessages
            FROM message
            WHERE conversationId = ?
            """;
        
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    return new JsonObject()
                        .put("totalMessages", row.getLong(0))
                        .put("userMessages", row.getLong(1))
                        .put("assistantMessages", row.getLong(2))
                        .put("totalEdits", row.getLong(3))
                        .put("avgLength", row.getDouble(4))
                        .put("maxLength", row.getLong(5))
                        .put("encodedMessages", row.getLong(6));
                } else {
                    return new JsonObject();
                }
            });
    }
    
    // Helper methods
    
    /**
     * Verify that a user has access to a conversation.
     * Access is granted if the user is:
     * 1. The conversation owner, OR
     * 2. An active participant in the conversation
     */
    private Uni<Boolean> verifyConversationAccess(String conversationId, String userId) {

        // Check both ownership and participant status for multi-user conversation support
        String query = """
            SELECT COUNT(*) as count
            FROM conversation c
            LEFT JOIN conversation_participant cp ON c.conversationGUID = cp.conversation_id
            WHERE c.conversationGUID = ?
              AND (c.ownerId = ? OR (cp.user_id = ? AND cp.is_active = true))
            """;

        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId, userId, userId))
            .map(rows -> {
                long count = rows.iterator().next().getLong("count");
                return count > 0;
            })
            .onFailure().recoverWithItem(error -> {
                log.warn("Error verifying conversation access for user {} in conversation {}: {}",
                    userId, conversationId, error.getMessage());
                return false;
            });
    }

    /**
     * Verify that a character exists and is not deleted.
     * This prevents orphaned references when characters are deleted.
     *
     * @param characterId The character UUID to verify
     * @return Uni<Boolean> true if character exists and is not deleted, false otherwise
     */
    private Uni<Boolean> verifyCharacterExists(String characterId) {

        if (characterId == null || characterId.isEmpty()) {
            return Uni.createFrom().item(true); // null characterId is valid (non-group chat)
    }

        String query = """
            SELECT COUNT(*) as count
            FROM `character`
            WHERE characterUUID = ?
            """;

        return pool.preparedQuery(query)
            .execute(Tuple.of(characterId))
            .map(rows -> {
                long count = rows.iterator().next().getLong("count");
                return count > 0;
            })
            .onFailure().recoverWithItem(error -> {
                log.warn("Error verifying character existence for {}: {}",
                    characterId, error.getMessage());
                return false;
            });
    }

    private Uni<String> getDisplayName(String userId, String role) {

        if ("assistant".equals(role) || "system".equals(role)) {
            return Uni.createFrom().item("Assistant");
        } else {
            String query = "SELECT displayName FROM user WHERE userUUID = ?";

            return pool.preparedQuery(query)
                .execute(Tuple.of(userId))
                .map(rows -> {
                    if (rows.size() > 0) {
                        String displayName = rows.iterator().next().getString("displayName");
                        return displayName;
                    } else {
                        return "User";
                    }
                })
                .onFailure().recoverWithItem(error -> "User");
        }
    }
    
    private boolean shouldAutoEncode(String content) {
        // Auto-encode if content contains binary data or special characters
        if (content == null) return false;

        // Check for binary content (ISO control characters except newline, carriage return, tab)
        // These characters indicate binary data that should be Base64 encoded
        for (char c : content.toCharArray()) {
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                return true;
            }
        }

        // Note: We do NOT auto-encode based on URL presence or message length
        // URLs are text and don't need Base64 encoding
        // If users need encoding for other reasons, they can explicitly request it

        return false;
    }

    private boolean isMessageEditable(Message message) {
        // Check if message is within edit time limit
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime created = LocalDateTime.parse(message.getCreatedAt());
        Duration timeSinceCreation = Duration.between(created, now);
        
        return timeSinceCreation.toMinutes() <= EDIT_TIME_LIMIT_MINUTES;
    }
    
    private Uni<Void> saveEditHistory(String messageId, String previousContent, String newContent, String userId) {

        String editId = UUID.randomUUID().toString();

        String insertQuery = """
            INSERT INTO message_edit (
                editId, messageId, previousContent, newContent, editedAt, editedBy
            ) VALUES (?, ?, ?, ?, ?, ?)
            """;

        return pool.preparedQuery(insertQuery)
            .execute(Tuple.of(
                editId,
                messageId,
                previousContent,
                newContent,
                LocalDateTime.now(ZoneOffset.UTC),
                userId
            ))
            .replaceWithVoid();
    }
    
    private void updateConversationTimestamp(String conversationId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String updateQuery = """
            UPDATE conversation
            SET lastModified = ?, updatedAt = ?
            WHERE conversationGUID = ?
            """;

        pool.preparedQuery(updateQuery)
            .execute(Tuple.of(now, now, conversationId))
            .subscribe().with(
                result -> {},
                error -> log.error("Failed to update conversation timestamp: {}", conversationId, error));
    }
    
    /**
     * Trigger LLM response with error handling (Issue #8 fix).
     * Uses request() with timeout instead of fire-and-forget send() to detect failures.
     * On failure, publishes an error event so clients can be notified.
     * Note: userName is resolved centrally by LLMVerticle using userId.
     */
    private void triggerLLMResponse(String conversationId, String userId, Message userMessage) {
        // Send request to LLM verticle with error detection
        JsonObject llmRequest = new JsonObject()
            .put("conversationId", conversationId)
            .put("userId", userId)
            .put("messageId", userMessage.getId().toString())
            .put("content", userMessage.getContent());

        // Idle-based timeout is enforced inside LLMBridge (chatllm.llm.idle-timeout).
        // The originator's reply window must exceed the backend's absolute cap
        // (chatllm.llm.request-timeout) so it never gives up first while the LLM
        // is still actively streaming tokens.
        long replyTimeoutMs = appConfig.getLlmRequestTimeout() + 30_000L;
        DeliveryOptions options = new DeliveryOptions().setSendTimeout(replyTimeoutMs);

        vertx.eventBus().<JsonObject>request("llm.generate", llmRequest, options)
            .subscribe().with(
                reply -> {
                    log.debug("LLM generation initiated successfully for message: {} in conversation: {}",
                        userMessage.getId(), conversationId);
                },
                error -> {
                    log.error("LLM generation failed for message: {} in conversation: {}. Error: {}",
                        userMessage.getId(), conversationId, error.getMessage());

                    // Publish error event so clients can be notified via WebSocket subscription
                    publishMessageEvent("llm_generation_failed", new JsonObject()
                        .put("messageId", userMessage.getId().toString())
                        .put("conversationId", conversationId)
                        .put("userId", userId)
                        .put("error", error.getMessage())
                        .put("timestamp", LocalDateTime.now(ZoneOffset.UTC).toString()));

                    // Also send metrics for monitoring
                    vertx.eventBus().send("metrics.llm.failure", new JsonObject()
                        .put("messageId", userMessage.getId().toString())
                        .put("conversationId", conversationId)
                        .put("error", error.getMessage())
                        .put("timestamp", LocalDateTime.now(ZoneOffset.UTC).toString()));
                });
    }
    
    private Message mapRowToMessage(Row row) {
        Message message = new Message();
        message.setId(UUID.fromString(row.getString("messageUUID")));
        message.setSeq(row.getLong("seq"));
        message.setConversationId(UUID.fromString(row.getString("conversationId")));

        // ownerId can be null for assistant messages
        String ownerIdStr = row.getString("ownerId");
        if (ownerIdStr != null && !ownerIdStr.isEmpty()) {
            message.setOwnerId(UUID.fromString(ownerIdStr));
        }

        // Decode content if Base64 using Base64Helper
        String content = row.getString("content");
        Boolean isBase64 = row.getBoolean("isBase64Encoded");

        if (Boolean.TRUE.equals(isBase64) && content != null) {
            content = Base64Helper.decodeIfBase64(content);
        }

        message.setContent(content);
        message.setRole(MessageRole.fromString(row.getString("role")));
        message.setDisplayName(row.getString("displayName"));
        message.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        message.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        message.setIsBase64Encoded(Boolean.TRUE.equals(isBase64));
        message.setIsEdited(row.getBoolean("isEdited"));
        message.setIsDeleted(row.getBoolean("isDeleted"));
        message.setIsStreaming(row.getBoolean("isStreaming"));
        message.setIsInterjection(row.getBoolean("isInterjection"));
        message.setEditCount(row.getInteger("editCount"));

        // Character attribution (group chat)
        String charId = row.getString("characterId");
        if (charId != null) {
            message.setCharacterId(UUID.fromString(charId));
        }

        // Generation stats
        message.setModel(row.getString("model"));
        message.setPromptTokens(row.getInteger("promptTokens"));
        message.setCompletionTokens(row.getInteger("completionTokens"));
        message.setPromptEvalMs(row.getInteger("promptEvalMs"));
        message.setGenerationMs(row.getInteger("generationMs"));
        message.setTotalTimeMs(row.getInteger("totalTimeMs"));
        message.setTokensPerSecond(row.getDouble("tokensPerSecond"));

        // Roleplay fields
        String sceneId = row.getString("sceneId");
        if (sceneId != null) {
            message.setSceneId(UUID.fromString(sceneId));
        }
        message.setMessageType(MessageType.fromString(row.getString("messageType")));
        message.setVisibilityOverride(ServiceHelper.safeGetJsonObject(row, "visibilityOverride"));
        message.setAgentMetadata(ServiceHelper.safeGetJsonObject(row, "agentMetadata"));

        return message;
    }
    
    /**
     * Get a cached message - delegates to CacheHelper.
     */
    private Uni<Message> getCachedMessage(String cacheKey) {
        return cacheHelper.get(cacheKey);
    }

    /**
     * Cache a message - delegates to CacheHelper.
     */
    private void cacheMessage(String cacheKey, Message message) {
        cacheHelper.set(cacheKey, message);
    }
    
    /**
     * Invalidate message cache with confirmation (Issue #4 fix).
     * Uses request() instead of fire-and-forget send() to ensure cache invalidation succeeds.
     * @return Future that completes when cache is invalidated or fails if invalidation times out
     */
    private Uni<Void> invalidateMessageCacheConfirmed(String conversationId) {

        DeliveryOptions options = new DeliveryOptions().setSendTimeout(5000);

        // Invalidate all message caches for this conversation
        String pattern = "message:*";
        Uni<io.vertx.mutiny.core.eventbus.Message<JsonObject>> future1 = vertx.eventBus().<JsonObject>request(
            "redis.cache.invalidate",
            new JsonObject().put("pattern", pattern).put("conversationId", conversationId),
            options
        );

        // Also invalidate the specific conversation message list cache
        String listPattern = "messages:" + conversationId + ":*";
        Uni<io.vertx.mutiny.core.eventbus.Message<JsonObject>> future2 = vertx.eventBus().<JsonObject>request(
            "redis.cache.invalidate",
            new JsonObject().put("pattern", listPattern),
            options
        );

        // Wait for both invalidations to complete
        return Uni.combine().all().unis(future1, future2).asTuple()
            .invoke(v -> {
                log.debug("Cache invalidated successfully for conversation: {}", conversationId);
            })
            .replaceWithVoid()
            .onFailure().recoverWithUni(error -> {
                log.error("Cache invalidation failed for conversation: {}. Error: {}",
                    conversationId, error.getMessage());
                // Still complete (don't fail the main operation due to cache issues)
                // but log the error for monitoring
                return Uni.createFrom().voidItem();
            });
    }

    /**
     * @deprecated Use {@link #invalidateMessageCacheConfirmed(String)} for confirmed invalidation.
     * This method is kept for backward compatibility but uses fire-and-forget semantics.
     */
    @Deprecated
    private void invalidateMessageCache(String conversationId) {
        invalidateMessageCacheConfirmed(conversationId)
            .subscribe().with(
                v -> {},
                err -> log.warn("Cache invalidation failed for conversation: {}", conversationId, err)
            );
    }
    
    /**
     * Publish message event - delegates to ServiceHelper.
     */
    private void publishMessageEvent(String eventType, Object data) {
        serviceHelper.publishEvent("message:events", eventType, data);
    }

    // ============================================================
    // Message Retention Policy Methods
    // ============================================================

    /**
     * Schedule soft-deleted messages for permanent deletion after retention period.
     * This should be called periodically (e.g., daily) by a scheduled job.
     *
     * @param retentionDays Number of days to retain soft-deleted messages (default: 30)
     * @return Future with count of messages scheduled for deletion
     */
    public Uni<Integer> scheduleDeletedMessagesForCleanup(int retentionDays) {

        String updateQuery = """
            UPDATE message
            SET scheduledDeleteAt = DATE_ADD(deletedAt, INTERVAL ? DAY)
            WHERE isDeleted = true
              AND deletedAt IS NOT NULL
              AND scheduledDeleteAt IS NULL
            """;

        return pool.preparedQuery(updateQuery)
            .execute(Tuple.of(retentionDays))
            .map(result -> {
                int scheduled = result.rowCount();
                log.info("Scheduled {} soft-deleted messages for cleanup after {} days", scheduled, retentionDays);
                return scheduled;
            })
            .onFailure().invoke(error -> {
                log.error("Failed to schedule messages for cleanup", error);
            });
    }

    /**
     * Permanently delete messages that have reached their scheduled deletion time.
     * This removes the messages from the database entirely.
     * This should be called periodically (e.g., daily) by a scheduled job.
     *
     * @return Future with count of messages permanently deleted
     */
    public Uni<Integer> cleanupExpiredMessages() {

        // First, get the messages to be deleted (for logging/metrics)
        String selectQuery = """
            SELECT messageUUID, conversationId
            FROM message
            WHERE scheduledDeleteAt IS NOT NULL
              AND scheduledDeleteAt < NOW()
            LIMIT 1000
            """;

        return pool.preparedQuery(selectQuery)
            .execute()
            .chain(rows -> {
                if (rows.size() == 0) {
                    return Uni.createFrom().item(0);
                }

                // Delete the messages (CASCADE will delete reactions, edits, etc.)
                String deleteQuery = """
                    DELETE FROM message
                    WHERE scheduledDeleteAt IS NOT NULL
                      AND scheduledDeleteAt < NOW()
                    LIMIT 1000
                    """;

                return pool.preparedQuery(deleteQuery)
                    .execute()
                    .map(result -> {
                        int deleted = result.rowCount();
                        log.info("Permanently deleted {} expired messages", deleted);

                        // Invalidate cache for affected conversations
                        for (Row row : rows) {
                            String conversationId = row.getString("conversationId");
                            invalidateMessageCache(conversationId);
                        }

                        return deleted;
                    });
            })
            
            .onFailure().invoke(error -> {
                log.error("Failed to cleanup expired messages", error);
            });
    }

    /**
     * Cancel scheduled deletion for a message (e.g., if user wants to restore it).
     *
     * @param messageId The message UUID
     * @return Uni<Boolean> true if cancellation successful
     */
    public Uni<Boolean> cancelScheduledDeletion(String messageId) {

        String updateQuery = """
            UPDATE message
            SET scheduledDeleteAt = NULL
            WHERE messageUUID = ?
            """;

        return pool.preparedQuery(updateQuery)
            .execute(Tuple.of(messageId))
            .map(result -> {
                boolean cancelled = result.rowCount() > 0;
                if (cancelled) {
                    log.info("Cancelled scheduled deletion for message {}", messageId);
                }
                return cancelled;
            });
    }

    // ================================================================
    // Typing Indicators (moved from MessageServiceVerticle)
    // ================================================================

    /**
     * Get the list of users currently typing in a conversation.
     * Moved from MessageServiceVerticle to centralize business logic.
     *
     * @param conversationId The conversation ID
     * @return Uni<JsonArray> array of user IDs currently typing
     */
    public Uni<JsonArray> getTypingUsers(String conversationId) {
        String key = "typing:" + conversationId;

        return vertx.eventBus().<JsonObject>request("redis.get", new JsonObject().put("key", key))
            .map(reply -> {
                JsonObject result = reply.body();
                String value = result.getString("value");

                if (value != null) {
                    try {
                        JsonObject typingData = new JsonObject(value);
                        return typingData.getJsonArray("users", new JsonArray());
                    } catch (Exception e) {
                        return new JsonArray();
                    }
                } else {
                    return new JsonArray();
                }
            })
            .onFailure().recoverWithItem(error -> {
                log.warn("Failed to get typing users for conversation {}", conversationId, error);
                return new JsonArray();
            });
    }

    /**
     * Set or clear a user's typing status in a conversation.
     * Moved from MessageServiceVerticle to centralize business logic.
     *
     * @param conversationId The conversation ID
     * @param userId The user ID
     * @param isTyping true to set typing, false to clear
     * @return Uni<Boolean> true if successful
     */
    public Uni<Boolean> setUserTypingStatus(String conversationId, String userId, boolean isTyping) {
        String key = "typing:" + conversationId;

        if (isTyping) {
            // Add user to typing list
            return vertx.eventBus().<JsonObject>request("redis.get", new JsonObject().put("key", key))
                .chain(reply -> {
                    JsonObject result = reply.body();
                    String value = result.getString("value");

                    JsonObject typingData;
                    try {
                        typingData = value != null ? new JsonObject(value) : new JsonObject();
                    } catch (Exception e) {
                        typingData = new JsonObject();
                    }

                    JsonArray users = typingData.getJsonArray("users", new JsonArray());
                    if (!users.contains(userId)) {
                        users.add(userId);
                    }
                    typingData.put("users", users);
                    typingData.put("timestamp", System.currentTimeMillis());

                    // Store with 10 second TTL
                    return vertx.eventBus().<JsonObject>request("redis.set", new JsonObject()
                        .put("key", key)
                        .put("value", typingData.encode())
                        .put("ttl", 10));
                })
                .invoke(v -> {
                    // Publish typing event
                    // Channel must match SubscriptionResolver.typingIndicator
                    // consumer ("typing.indicator.<conversationId>"); earlier this
                    // published to "conversation:<id>:typing" which nothing consumed.
                    vertx.eventBus().publish("typing.indicator." + conversationId,
                        new JsonObject()
                            .put("userId", userId)
                            .put("isTyping", true));
                })
                .replaceWith(true)
                .onFailure().recoverWithItem(error -> {
                    log.error("Failed to set typing status for user {} in conversation {}",
                        userId, conversationId, error);
                    return false;
                });
        } else {
            // Remove user from typing list
            return vertx.eventBus().<JsonObject>request("redis.get", new JsonObject().put("key", key))
                .chain(reply -> {
                    JsonObject result = reply.body();
                    String value = result.getString("value");

                    if (value != null) {
                        try {
                            JsonObject typingData = new JsonObject(value);
                            JsonArray users = typingData.getJsonArray("users", new JsonArray());
                            users.remove(userId);

                            if (users.isEmpty()) {
                                // Delete key if no users typing
                                return vertx.eventBus().<JsonObject>request("redis.delete",
                                    new JsonObject().put("key", key));
                            } else {
                                typingData.put("users", users);
                                return vertx.eventBus().<JsonObject>request("redis.set", new JsonObject()
                                    .put("key", key)
                                    .put("value", typingData.encode())
                                    .put("ttl", 10));
                            }
                        } catch (Exception e) {
                            return Uni.createFrom().nullItem();
                        }
                    }
                    return Uni.createFrom().nullItem();
                })
                .invoke(v -> {
                    // Publish typing event
                    vertx.eventBus().publish("typing.indicator." + conversationId,
                        new JsonObject()
                            .put("userId", userId)
                            .put("isTyping", false));
                })
                .replaceWith(true)
                .onFailure().recoverWithItem(error -> {
                    // Don't fail if clearing typing status fails
                    log.warn("Failed to clear typing status for user {} in conversation {}",
                        userId, conversationId, error);
                    return true;
                });
        }
    }
}