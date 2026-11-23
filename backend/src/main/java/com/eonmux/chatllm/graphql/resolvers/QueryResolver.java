package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.utils.Base64Helper;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.models.CharacterPagination;
import com.eonmux.chatllm.models.Conversation;
import com.eonmux.chatllm.models.ConversationPagination;
import com.eonmux.chatllm.models.ExportData;
import com.eonmux.chatllm.models.LLMConfig;
import com.eonmux.chatllm.models.LLMStatus;
import com.eonmux.chatllm.models.Message;
import com.eonmux.chatllm.models.MessagePagination;
import com.eonmux.chatllm.models.MessageRole;
import com.eonmux.chatllm.models.MessageSearchPagination;
import com.eonmux.chatllm.models.MessageSearchResult;
import com.eonmux.chatllm.models.MessageType;
import com.eonmux.chatllm.models.SessionInfo;
import com.eonmux.chatllm.models.Settings;
import com.eonmux.chatllm.models.StreamingMessage;
import com.eonmux.chatllm.models.SystemStats;
import com.eonmux.chatllm.models.User;
import com.eonmux.chatllm.models.UserStatistics;
import com.eonmux.chatllm.models.inputs.*;
import com.eonmux.chatllm.services.DataExportService;
import com.eonmux.chatllm.services.SettingsService;
import com.eonmux.chatllm.services.StreamingMessageBuffer;
import com.eonmux.chatllm.services.auth.AuthContext;
import com.eonmux.chatllm.utils.ServiceHelper;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Redis;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Description;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GraphQL Query resolver using SmallRye GraphQL annotations.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class QueryResolver {

    @Inject
    Vertx vertx;

    @Inject
    Pool mysqlPool;

    @Inject
    Redis redisClient;

    @Inject
    AppConfig config;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    SettingsService settingsService;

    @Inject
    StreamingMessageBuffer streamingMessageBuffer;

    @Inject
    DataExportService dataExportService;

    @Inject
    com.eonmux.chatllm.utils.ServiceHelper serviceHelper;

    // ==========================================
    // QUERY METHODS
    // ==========================================

    /**
     * Get current authenticated user.
     */
    @Query
    @Description("Get current authenticated user")
    public Uni<User> me() {
        AuthContext auth = graphQLAuthContext.requireAuth();


            String query = "SELECT * FROM user WHERE userUUID = ?";
            return mysqlPool.preparedQuery(query)
                .execute(Tuple.of(auth.getUserId().toString()))
                .chain(rows -> {
                    if (rows.size() > 0) {
                        User user = mapRowToUser(rows.iterator().next());
                        return Uni.createFrom().item(user);
                    } else {
                        log.warn("User not found for userId: {} - possibly deleted or database was reset", auth.getUserId());
                        return Uni.createFrom().nullItem();
                    }
                });

    }

    /**
     * Get user by ID.
     */
    @Query
    @Description("Get user by ID")
    public Uni<User> user(@Name("id") String id) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        UUID userId = UUID.fromString(id);

        // Non-admins can only look up their own profile
        if (!auth.isAdmin() && !auth.getUserId().toString().equals(id)) {
            return Uni.createFrom().failure(new RuntimeException("Access denied"));
        }

            String query = "SELECT * FROM user WHERE userUUID = ?";
            return mysqlPool.preparedQuery(query)
                .execute(Tuple.of(userId.toString()))
                .map(rows -> {
                    if (rows.size() > 0) {
                        return mapRowToUser(rows.iterator().next());
                    } else {
                        return null;
                    }
                });

    }

    /**
     * Get all users (admin only).
     */
    @Query
    @Description("Get all users (admin only)")
    public Uni<List<User>> users(@Name("pagination") PaginationInput pagination) {
        AuthContext auth = graphQLAuthContext.requireAdmin();

        int limit = pagination != null && pagination.getLimit() != null
            ? pagination.getLimit() : 20;
        int offset = pagination != null && pagination.getOffset() != null
            ? pagination.getOffset() : 0;


            String query = "SELECT * FROM user LIMIT ? OFFSET ?";
            return mysqlPool.preparedQuery(query)
                .execute(Tuple.of(limit, offset))
                .map(rows -> {
                    List<User> users = new ArrayList<>();
                    rows.forEach(row -> users.add(mapRowToUser(row)));
                    return users;
                });

    }

    /**
     * Check if username is available.
     */
    @Query
    @Description("Check if username is available")
    public Uni<Boolean> checkUsername(@Name("username") String username) {
        graphQLAuthContext.requireAuth();

            String query = "SELECT COUNT(*) as count FROM user WHERE username = ?";
            return mysqlPool.preparedQuery(query)
                .execute(Tuple.of(username))
                .map(rows -> {
                    Long count = rows.iterator().next().getLong("count");
                    return count == 0;
                });

    }

    /**
     * Check if debug/auth-bypass mode is enabled.
     */
    @Query
    @Description("Check if debug mode is enabled")
    public Uni<Boolean> debugMode() {
        return Uni.createFrom().item(config.isAuthBypassEnabled());
    }

    /**
     * Get user settings.
     */
    @Query
    @Description("Get user settings")
    public Uni<Settings> settings() {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return settingsService.getUserSettings(userId)
                .onFailure().invoke(error ->
                    log.error("Failed to get settings for user {}: {}", userId, error.getMessage()));
    }

    /**
     * Get LLM configuration.
     */
    @Query
    @Description("Get LLM configuration")
    public Uni<LLMConfig> llmConfig() {
        graphQLAuthContext.requireAuth();

            return vertx.eventBus().<JsonObject>request("llm.status", new JsonObject())
                .map(reply -> {
                    JsonObject llmStatus = reply.body();
                    return LLMConfig.builder()
                        .providerType(llmStatus.getString("providerType", "local"))
                        .modelPath(llmStatus.getString("modelPath", "/models/default.gguf"))
                        .gpuLayers(llmStatus.getInteger("gpuLayers", 0))
                        .contextSize(llmStatus.getInteger("contextSize", 4096))
                        .batchSize(llmStatus.getInteger("batchSize", 512))
                        .threads(llmStatus.getInteger("threads", Runtime.getRuntime().availableProcessors()))
                        .temperature(llmStatus.getDouble("temperature", 0.7))
                        .topP(llmStatus.getDouble("topP", 0.9))
                        .topK(llmStatus.getInteger("topK", 40))
                        .templates(List.of("CHATML", "LLAMA3", "MISTRAL", "ALPACA"))
                        .currentTemplate(llmStatus.getString("currentTemplate", "CHATML"))
                        .externalBaseUrl(llmStatus.getString("externalBaseUrl"))
                        .externalModel(llmStatus.getString("externalModel"))
                        .build();
                })
                .onFailure().recoverWithItem(err -> {
                    log.warn("Failed to get LLM config from verticle, using defaults: {}", err.getMessage());
                    return LLMConfig.builder()
                        .providerType("local")
                        .modelPath("/models/default.gguf")
                        .gpuLayers(0)
                        .contextSize(4096)
                        .batchSize(512)
                        .threads(Runtime.getRuntime().availableProcessors())
                        .temperature(0.7)
                        .topP(0.9)
                        .topK(40)
                        .templates(List.of("CHATML", "LLAMA3", "MISTRAL", "ALPACA"))
                        .currentTemplate("CHATML")
                        .build();
                });

    }

    /**
     * Get LLM status.
     */
    @Query
    @Description("Get LLM status")
    public Uni<LLMStatus> llmStatus() {
        graphQLAuthContext.requireAuth();

            return vertx.eventBus().<JsonObject>request("llm.status", new JsonObject())
                .map(reply -> {
                    JsonObject llmStatusJson = reply.body();
                    int activeServers = llmStatusJson.getInteger("activeServers", 0);
                    return LLMStatus.builder()
                        .providerType(llmStatusJson.getString("providerType", "local"))
                        .isRunning(activeServers > 0)
                        .modelLoaded(activeServers > 0)
                        .currentModel(llmStatusJson.getString("modelPath", "default"))
                        .availableMemory(Runtime.getRuntime().freeMemory())
                        .usedMemory(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                        .build();
                })
                .onFailure().recoverWithItem(err -> {
                    log.warn("Failed to get LLM status from verticle: {}", err.getMessage());
                    return LLMStatus.builder()
                        .providerType("local")
                        .isRunning(false)
                        .modelLoaded(false)
                        .availableMemory(Runtime.getRuntime().freeMemory())
                        .usedMemory(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                        .build();
                });

    }

    /**
     * Get available templates.
     */
    @Query
    @Description("Get available chat templates")
    public Uni<List<String>> availableTemplates() {
        return vertx.eventBus().<JsonObject>request("llm.status", new JsonObject())
                .map(reply -> List.of("CHATML", "LLAMA3", "MISTRAL", "ALPACA", "VICUNA", "ZEPHYR"))
                .onFailure().recoverWithItem(List.of("CHATML", "LLAMA3", "MISTRAL", "ALPACA"));
    }

    /**
     * Get current session info.
     */
    @Query
    @Description("Get current session info")
    public Uni<SessionInfo> currentSession() {
        AuthContext auth = graphQLAuthContext.getAuthContext();
        if (auth == null || !auth.isAuthenticated()) {
            return Uni.createFrom().nullItem();
        }

            String userId = auth.getUserId().toString();

            String query = """
                SELECT sessionId, userId, expiresAt, lastActivity, ipAddress
                FROM session
                WHERE userId = ? AND expiresAt > NOW()
                ORDER BY lastActivity DESC
                LIMIT 1
                """;

            return mysqlPool.preparedQuery(query)
                .execute(Tuple.of(userId))
                .map(rows -> {
                    if (rows.size() > 0) {
                        var row = rows.iterator().next();
                        return SessionInfo.builder()
                            .id(row.getString("sessionId"))
                            .userId(row.getString("userId"))
                            .expiresAt(row.getLocalDateTime("expiresAt") != null ?
                                    Time4JHelper.localDateTimeToString(row.getLocalDateTime("expiresAt")) : null)
                            .lastActivity(row.getLocalDateTime("lastActivity") != null ?
                                    Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastActivity")) : null)
                            .ipAddress(row.getString("ipAddress"))
                            .build();
                    } else {
                        return null;
                    }
                })
                .onFailure().recoverWithItem(err -> {
                    log.error("Failed to get current session: {}", err.getMessage());
                    return null;
                });

    }

    /**
     * Get all active sessions for current user.
     */
    @Query
    @Description("Get all active sessions")
    public Uni<List<SessionInfo>> activeSessions() {
        AuthContext auth = graphQLAuthContext.getAuthContext();
        if (auth == null || !auth.isAuthenticated()) {
            return Uni.createFrom().item(List.of());
        }

            String userId = auth.getUserId().toString();

            String query = """
                SELECT sessionId, userId, expiresAt, lastActivity, ipAddress
                FROM session
                WHERE userId = ? AND expiresAt > NOW()
                ORDER BY lastActivity DESC
                """;

            return mysqlPool.preparedQuery(query)
                .execute(Tuple.of(userId))
                .map(rows -> {
                    List<SessionInfo> sessions = new ArrayList<>();
                    rows.forEach(row -> {
                        sessions.add(SessionInfo.builder()
                            .id(row.getString("sessionId"))
                            .userId(row.getString("userId"))
                            .expiresAt(row.getLocalDateTime("expiresAt") != null ?
                                    Time4JHelper.localDateTimeToString(row.getLocalDateTime("expiresAt")) : null)
                            .lastActivity(row.getLocalDateTime("lastActivity") != null ?
                                    Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastActivity")) : null)
                            .ipAddress(row.getString("ipAddress"))
                            .build());
                    });
                    return sessions;
                })
                .onFailure().recoverWithItem(err -> {
                    log.error("Failed to get active sessions: {}", err.getMessage());
                    return List.of();
                });

    }

    /**
     * Get user statistics.
     */
    @Query
    @Description("Get user statistics")
    public Uni<UserStatistics> userStatistics() {
        AuthContext auth = graphQLAuthContext.requireAuth();

            String userId = auth.getUserId().toString();

            String statsQuery = """
                SELECT
                    (SELECT COUNT(*) FROM conversation WHERE ownerId = ?) as totalConversations,
                    (SELECT COUNT(*) FROM message m
                        JOIN conversation c ON m.conversationId = c.conversationGUID
                        WHERE c.ownerId = ?) as totalMessages,
                    (SELECT COUNT(*) FROM `character` WHERE ownerId = ?) as totalCharacters,
                    (SELECT COALESCE(SUM(COALESCE(m.promptTokens, 0) + COALESCE(m.completionTokens, 0)), 0)
                        FROM message m
                        JOIN conversation c ON m.conversationId = c.conversationGUID
                        WHERE c.ownerId = ?) as totalTokens,
                    (SELECT COALESCE(SUM(CHAR_LENGTH(m.content)), 0)
                        FROM message m
                        JOIN conversation c ON m.conversationId = c.conversationGUID
                        WHERE c.ownerId = ?) as storageUsed,
                    (SELECT COUNT(*) FROM message m
                        JOIN conversation c ON m.conversationId = c.conversationGUID
                        WHERE c.ownerId = ? AND m.role = 'assistant'
                        AND m.createdAt > DATE_SUB(NOW(), INTERVAL 24 HOUR)) as apiCallsToday,
                    (SELECT COUNT(*) FROM message m
                        JOIN conversation c ON m.conversationId = c.conversationGUID
                        WHERE c.ownerId = ?
                        AND m.createdAt > DATE_SUB(NOW(), INTERVAL 24 HOUR)) as messagesToday,
                    (SELECT COALESCE(AVG(m.tokensPerSecond), 0.0)
                        FROM message m
                        JOIN conversation c ON m.conversationId = c.conversationGUID
                        WHERE c.ownerId = ? AND m.tokensPerSecond IS NOT NULL AND m.tokensPerSecond > 0) as avgTokensPerSecond
                """;

            return mysqlPool.preparedQuery(statsQuery)
                .execute(Tuple.tuple()
                    .addValue(userId)
                    .addValue(userId)
                    .addValue(userId)
                    .addValue(userId)
                    .addValue(userId)
                    .addValue(userId)
                    .addValue(userId)
                    .addValue(userId))
                .map(rows -> {
                    if (rows.size() > 0) {
                        var row = rows.iterator().next();
                        return UserStatistics.builder()
                            .totalConversations(row.getLong("totalConversations") != null ? row.getLong("totalConversations").intValue() : 0)
                            .totalMessages(row.getLong("totalMessages") != null ? row.getLong("totalMessages").intValue() : 0)
                            .totalCharacters(row.getLong("totalCharacters") != null ? row.getLong("totalCharacters").intValue() : 0)
                            .totalTokens(row.getLong("totalTokens") != null ? row.getLong("totalTokens").intValue() : 0)
                            .storageUsed(row.getLong("storageUsed") != null ? row.getLong("storageUsed") : 0L)
                            .apiCallsToday(row.getLong("apiCallsToday") != null ? row.getLong("apiCallsToday").intValue() : 0)
                            .messagesToday(row.getLong("messagesToday") != null ? row.getLong("messagesToday").intValue() : 0)
                            .avgTokensPerSecond(row.getDouble("avgTokensPerSecond") != null ? row.getDouble("avgTokensPerSecond") : 0.0)
                            .build();
                    }
                    return UserStatistics.builder().build();
                })
                .onFailure().recoverWithItem(err -> {
                    log.error("Failed to get user statistics for user {}: {}", userId, err.getMessage());
                    return UserStatistics.builder().build();
                });

    }

    /**
     * Get user's conversations.
     */
    @Query
    @Description("Get user conversations with pagination")
    public Uni<ConversationPagination> conversations(
            @Name("filter") ConversationFilter filter,
            @Name("limit") Integer limitArg,
            @Name("offset") Integer offsetArg) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        int limit = limitArg != null ? limitArg : 20;
        int offset = offsetArg != null ? offsetArg : 0;


            String userId = auth.getUserId().toString();
            StringBuilder whereClause = new StringBuilder("WHERE (c.ownerId = ? OR EXISTS (SELECT 1 FROM conversation_participant cp WHERE cp.conversation_id = c.conversationGUID AND cp.user_id = ? AND cp.is_active = true))");
            List<Object> params = new ArrayList<>();
            params.add(userId);
            params.add(userId);

            if (filter != null) {
                if (filter.getIsArchived() != null) {
                    whereClause.append(" AND c.isArchived = ?");
                    params.add(filter.getIsArchived() ? 1 : 0);
                }
                if (filter.getIsPinned() != null) {
                    whereClause.append(" AND c.isPinned = ?");
                    params.add(filter.getIsPinned() ? 1 : 0);
                }
                if (filter.getCategory() != null) {
                    whereClause.append(" AND c.category = ?");
                    params.add(filter.getCategory());
                }
                if (filter.getCharacterId() != null) {
                    whereClause.append(" AND c.characterId = ?");
                    params.add(filter.getCharacterId());
                }
            }

            String countQuery = "SELECT COUNT(*) as total FROM conversation c " + whereClause;
            Tuple countTuple = Tuple.from(params);

            return mysqlPool.preparedQuery(countQuery)
                .execute(countTuple)
                .chain(countRows -> {
                    int totalCount = countRows.iterator().next().getInteger("total");

                    String query = "SELECT c.* FROM conversation c " + whereClause + " ORDER BY c.isPinned DESC, c.updatedAt DESC LIMIT ? OFFSET ?";
                    List<Object> queryParams = new ArrayList<>(params);
                    queryParams.add(limit);
                    queryParams.add(offset);
                    Tuple queryTuple = Tuple.from(queryParams);

                    return mysqlPool.preparedQuery(query)
                        .execute(queryTuple)
                        .map(rows -> {
                            List<Conversation> conversations = new ArrayList<>();
                            rows.forEach(row -> conversations.add(mapRowToConversation(row)));

                            return ConversationPagination.builder()
                                .items(conversations)
                                .totalCount(totalCount)
                                .hasMore(offset + conversations.size() < totalCount)
                                .build();
                        });
                });

    }

    /**
     * Get conversation by ID.
     */
    @Query
    @Description("Get conversation by ID")
    public Uni<Conversation> conversation(@Name("id") String id) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        UUID conversationId = UUID.fromString(id);


            String userId = auth.getUserId().toString();
            String query = "SELECT c.* FROM conversation c LEFT JOIN conversation_participant cp ON c.conversationGUID = cp.conversation_id AND cp.user_id = ? WHERE c.conversationGUID = ? AND (c.ownerId = ? OR cp.is_active = true)";
            return mysqlPool.preparedQuery(query)
                .execute(Tuple.of(userId, conversationId.toString(), userId))
                .map(rows -> {
                    if (rows.size() > 0) {
                        return mapRowToConversation(rows.iterator().next());
                    } else {
                        return null;
                    }
                });

    }

    /**
     * Get messages for a conversation.
     */
    @Query
    @Description("Get messages for a conversation with pagination")
    public Uni<MessagePagination> messages(
            @Name("conversationId") String conversationId,
            @Name("limit") Integer limitArg,
            @Name("before") String before) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        int limit = limitArg != null ? limitArg : 50;


            String userId = auth.getUserId().toString();
            String verifyQuery = "SELECT COUNT(*) as count FROM conversation c LEFT JOIN conversation_participant cp ON c.conversationGUID = cp.conversation_id AND cp.user_id = ? WHERE c.conversationGUID = ? AND (c.ownerId = ? OR cp.is_active = true)";
            return mysqlPool.preparedQuery(verifyQuery)
                .execute(Tuple.of(userId, conversationId, userId))
                .chain(verifyRows -> {
                    Long count = verifyRows.iterator().next().getLong("count");
                    if (count == 0) {
                        log.debug("Conversation {} not found or unauthorized for user {}", conversationId, auth.getUserId());
                        return Uni.createFrom().item(MessagePagination.builder()
                            .items(Collections.emptyList())
                            .hasMore(false)
                            .build());
                    }

                    String query;
                    Tuple params;
                    if (before != null && !before.isEmpty()) {
                        // Cursor is the seq of the last-returned message (opaque
                        // to the client). Ordering is by the monotonic seq column,
                        // which is the exact insertion order — independent of the
                        // second-resolution createdAt and the random messageUUID.
                        long beforeSeq;
                        try {
                            beforeSeq = Long.parseLong(before.trim());
                        } catch (NumberFormatException e) {
                            // Legacy "createdAt,messageUUID" cursor — bail safely.
                            return Uni.createFrom().item(MessagePagination.builder()
                                .items(Collections.emptyList()).hasMore(false).build());
                        }
                        query = "SELECT * FROM message WHERE conversationId = ? AND seq < ? AND isDeleted = FALSE ORDER BY seq DESC LIMIT ?";
                        params = Tuple.of(conversationId, beforeSeq, limit + 1);
                    } else {
                        query = "SELECT * FROM message WHERE conversationId = ? AND isDeleted = FALSE ORDER BY seq DESC LIMIT ?";
                        params = Tuple.of(conversationId, limit + 1);
                    }
                    return mysqlPool.preparedQuery(query)
                        .execute(params)
                        .map(rows -> {
                            List<Message> allMessages = new ArrayList<>();
                            rows.forEach(row -> allMessages.add(mapRowToMessage(row)));

                            boolean hasMore = allMessages.size() > limit;
                            List<Message> messageList = hasMore
                                ? allMessages.subList(0, limit)
                                : allMessages;

                            String cursor = null;
                            if (!messageList.isEmpty()) {
                                Message oldest = messageList.get(messageList.size() - 1);
                                if (oldest.getSeq() != null) {
                                    cursor = String.valueOf(oldest.getSeq());
                                }
                            }

                            return MessagePagination.builder()
                                .items(messageList)
                                .hasMore(hasMore)
                                .cursor(cursor)
                                .build();
                        });
                });

    }

    /**
     * Get message by ID.
     */
    @Query
    @Description("Get message by ID")
    public Uni<Message> message(@Name("id") String id) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        UUID messageId = UUID.fromString(id);


            String userId = auth.getUserId().toString();
            String query = """
                SELECT m.* FROM message m
                JOIN conversation c ON m.conversationId = c.conversationGUID
                LEFT JOIN conversation_participant cp ON c.conversationGUID = cp.conversation_id AND cp.user_id = ?
                WHERE m.messageUUID = ? AND (c.ownerId = ? OR cp.is_active = true)
                """;
            return mysqlPool.preparedQuery(query)
                .execute(Tuple.of(userId, messageId.toString(), userId))
                .map(rows -> {
                    if (rows.size() > 0) {
                        return mapRowToMessage(rows.iterator().next());
                    } else {
                        return null;
                    }
                });

    }

    /**
     * Search messages by content.
     */
    @Query
    @Description("Search messages by content")
    public Uni<MessageSearchPagination> searchMessages(@Name("input") MessageSearchInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String searchQuery = inputObj != null ? inputObj.getQuery() : null;
        String conversationId = inputObj != null ? inputObj.getConversationId() : null;
        Integer limitArg = inputObj != null ? inputObj.getLimit() : null;
        Integer offsetArg = inputObj != null ? inputObj.getOffset() : null;

        int limit = limitArg != null ? limitArg : 20;
        int offset = offsetArg != null ? offsetArg : 0;

        if (searchQuery == null || searchQuery.trim().isEmpty()) {
            return Uni.createFrom().failure(new RuntimeException("Search query is required"));
        }

        String escapedQuery = searchQuery.replace("%", "\\%").replace("_", "\\_");
        String likePattern = "%" + escapedQuery + "%";


            String userId = auth.getUserId().toString();
            String participantJoin = "LEFT JOIN conversation_participant cp ON c.conversationGUID = cp.conversation_id AND cp.user_id = ?";
            StringBuilder whereClause = new StringBuilder();
            whereClause.append("WHERE (c.ownerId = ? OR cp.is_active = true) AND m.content LIKE ? AND (m.isDeleted = FALSE OR m.isDeleted IS NULL)");
            List<Object> params = new ArrayList<>();
            params.add(userId);
            params.add(userId);
            params.add(likePattern);

            if (conversationId != null && !conversationId.isEmpty()) {
                whereClause.append(" AND m.conversationId = ?");
                params.add(conversationId);
            }

            String countSql = """
                SELECT COUNT(*) as total FROM message m
                JOIN conversation c ON m.conversationId = c.conversationGUID
                """ + participantJoin + " " + whereClause;

            Tuple countTuple = Tuple.from(params);

            return mysqlPool.preparedQuery(countSql)
                .execute(countTuple)
                .chain(countRows -> {
                    int totalCount = countRows.iterator().next().getInteger("total");

                    String searchSql = """
                        SELECT m.*, c.conversationGUID as conv_id, c.title as conv_title, c.ownerId as conv_ownerId
                        FROM message m
                        JOIN conversation c ON m.conversationId = c.conversationGUID
                        """ + participantJoin + " " + whereClause + " ORDER BY m.seq DESC LIMIT ? OFFSET ?";

                    List<Object> searchParams = new ArrayList<>(params);
                    searchParams.add(limit);
                    searchParams.add(offset);
                    Tuple searchTuple = Tuple.from(searchParams);

                    return mysqlPool.preparedQuery(searchSql)
                        .execute(searchTuple)
                        .map(rows -> {
                            List<MessageSearchResult> results = new ArrayList<>();
                            rows.forEach(row -> {
                                String content = row.getString("content");
                                String highlighted = null;
                                if (content != null) {
                                    highlighted = content.replaceAll(
                                        "(?i)(" + java.util.regex.Pattern.quote(searchQuery) + ")",
                                        "**$1**"
                                    );
                                }

                                Conversation conv = new Conversation();
                                conv.setId(UUID.fromString(row.getString("conv_id")));
                                conv.setTitle(row.getString("conv_title"));
                                String ownerId = row.getString("conv_ownerId");
                                if (ownerId != null) {
                                    conv.setOwnerId(UUID.fromString(ownerId));
                                }

                                String role = row.getString("role");
                                results.add(MessageSearchResult.builder()
                                    .id(UUID.fromString(row.getString("messageUUID")))
                                    .content(content)
                                    .role(role != null ? role.toUpperCase() : "USER")
                                    .displayName(row.getString("displayName"))
                                    .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                                    .highlightedContent(highlighted)
                                    .conversation(conv)
                                    .build());
                            });

                            return MessageSearchPagination.builder()
                                .items(results)
                                .totalCount(totalCount)
                                .hasMore(offset + results.size() < totalCount)
                                .build();
                        });
                });

    }

    /**
     * Get user's characters.
     */
    @Query
    @Description("Get user characters with pagination")
    public Uni<CharacterPagination> characters(
            @Name("filter") CharacterFilter filter,
            @Name("limit") Integer limitArg,
            @Name("offset") Integer offsetArg) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        int limit = limitArg != null ? limitArg : 50;
        int offset = offsetArg != null ? offsetArg : 0;
        String userId = auth.getUserId().toString();


            String countQuery = "SELECT COUNT(*) as total FROM `character` WHERE ownerId = ? OR isPublic = 1";
            return mysqlPool.preparedQuery(countQuery)
                .execute(Tuple.of(userId))
                .chain(countRows -> {
                    int totalCount = countRows.iterator().next().getInteger("total");

                    String query = """
                        SELECT c.*,
                               CASE WHEN cf.id IS NOT NULL THEN TRUE ELSE FALSE END AS isFavorite
                        FROM `character` c
                        LEFT JOIN character_favorite cf ON c.characterUUID = cf.character_id AND cf.user_id = ?
                        WHERE c.ownerId = ? OR c.isPublic = 1
                        ORDER BY c.name
                        LIMIT ? OFFSET ?
                        """;
                    return mysqlPool.preparedQuery(query)
                        .execute(Tuple.of(userId, userId, limit, offset))
                        .map(rows -> {
                            List<Character> characters = new ArrayList<>();
                            rows.forEach(row -> {
                                Character character = mapRowToCharacter(row);
                                character.setIsFavorite(row.getBoolean("isFavorite"));
                                characters.add(character);
                            });

                            return CharacterPagination.builder()
                                .items(characters)
                                .totalCount(totalCount)
                                .hasMore(offset + characters.size() < totalCount)
                                .build();
                        });
                });

    }

    /**
     * Get character by ID.
     */
    @Query
    @Description("Get character by ID")
    public Uni<Character> character(@Name("id") String id) {
        AuthContext auth = graphQLAuthContext.getAuthContext();
        UUID characterId = UUID.fromString(id);
        String userId = auth != null && auth.isAuthenticated() ? auth.getUserId().toString() : null;


            String query = """
                SELECT c.*,
                       CASE WHEN cf.id IS NOT NULL THEN TRUE ELSE FALSE END AS isFavorite
                FROM `character` c
                LEFT JOIN character_favorite cf ON c.characterUUID = cf.character_id AND cf.user_id = ?
                WHERE c.characterUUID = ?
                """;
            return mysqlPool.preparedQuery(query)
                .execute(Tuple.of(userId, characterId.toString()))
                .map(rows -> {
                    if (rows.size() > 0) {
                        io.vertx.mutiny.sqlclient.Row row = rows.iterator().next();
                        Character character = mapRowToCharacter(row);
                        character.setIsFavorite(row.getBoolean("isFavorite"));
                        return character;
                    } else {
                        return null;
                    }
                });

    }

    /**
     * Get default character.
     */
    @Query
    @Description("Get default character")
    public Uni<Character> defaultCharacter() {
        Character assistant = new Character();
        assistant.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assistant.setName("Assistant");
        assistant.setDescription("Default AI assistant");
        assistant.setIsDefault(true);
        assistant.setIsFavorite(false);

        return Uni.createFrom().item(assistant);
    }

    /**
     * Get system statistics (admin only).
     */
    @Query
    @Description("Get system statistics (admin only)")
    public Uni<SystemStats> systemStats() {
        AuthContext auth = graphQLAuthContext.requireAuth();
        if (!auth.hasRole("ADMIN")) {
            return Uni.createFrom().failure(new RuntimeException("Admin role required"));
        }

            String statsQuery = """
                SELECT
                    (SELECT COUNT(*) FROM user) as totalUsers,
                    (SELECT COUNT(*) FROM user WHERE lastLogin > DATE_SUB(NOW(), INTERVAL 24 HOUR)) as activeUsers,
                    (SELECT COUNT(*) FROM conversation) as totalConversations,
                    (SELECT COUNT(*) FROM message) as totalMessages,
                    (SELECT COUNT(*) FROM `character`) as totalCharacters,
                    (SELECT COUNT(*) FROM message WHERE createdAt > DATE_SUB(NOW(), INTERVAL 24 HOUR)) as messagesLast24h,
                    (SELECT COUNT(*) FROM user WHERE createdAt > DATE_SUB(NOW(), INTERVAL 24 HOUR)) as newUsersLast24h
                """;

            Runtime runtime = Runtime.getRuntime();
            long uptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();

            return mysqlPool.query(statsQuery)
                .execute()
                .chain(rows -> {
                    SystemStats.SystemStatsBuilder builder = SystemStats.builder()
                        .memoryUsed(runtime.totalMemory() - runtime.freeMemory())
                        .memoryTotal(runtime.totalMemory())
                        .serverUptime(uptimeMs);

                    if (rows.size() > 0) {
                        var row = rows.iterator().next();
                        builder
                            .totalUsers(row.getLong("totalUsers").intValue())
                            .activeUsers(row.getLong("activeUsers").intValue())
                            .totalConversations(row.getLong("totalConversations").intValue())
                            .totalMessages(row.getLong("totalMessages").intValue())
                            .totalCharacters(row.getLong("totalCharacters").intValue())
                            .messagesLast24h(row.getLong("messagesLast24h").intValue())
                            .newUsersLast24h(row.getLong("newUsersLast24h").intValue());
                    }

                    String dbSizeQuery = """
                        SELECT COALESCE(SUM(data_length + index_length), 0) as databaseSize
                        FROM information_schema.tables
                        WHERE table_schema = DATABASE()
                        """;

                    SystemStats.SystemStatsBuilder finalBuilder = builder;
                    return mysqlPool.query(dbSizeQuery)
                        .execute()
                        .map(dbRows -> {
                            if (dbRows.size() > 0) {
                                Long dbSize = dbRows.iterator().next().getLong("databaseSize");
                                finalBuilder.databaseSize(dbSize != null ? dbSize : 0L);
                            }
                            return finalBuilder.build();
                        })
                        .onFailure().recoverWithItem(dbErr -> finalBuilder.build());
                })
                .onFailure().invoke(err ->
                    log.error("Failed to get system stats", err)
                );

    }

    /**
     * Health check.
     */
    @Query
    @Description("Health check")
    public String health() {
        return "healthy";
    }

    /**
     * Get pending streaming message.
     */
    @Query
    @Description("Get pending streaming message for recovery")
    public Uni<StreamingMessage> pendingStreamingMessage(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.getAuthContext();
        if (auth == null || !auth.isAuthenticated()) {
            return Uni.createFrom().nullItem();
        }

        if (conversationId == null || conversationId.isEmpty()) {
            return Uni.createFrom().nullItem();
        }

        // Verify the caller owns or participates in this conversation
        return serviceHelper.verifyConversationAccess(conversationId, auth.getUserId().toString())
            .map(hasAccess -> {
                if (!hasAccess) {
                    return null;
                }

                // getAndClearPendingMessage atomically consumes the buffer once
                // the stream is complete, so concurrent pollers can't both see
                // the same message (or clear it under another participant).
                JsonObject pending = streamingMessageBuffer.getAndClearPendingMessage(conversationId);
                if (pending == null) {
                    return null;
                }

                StreamingMessage result = StreamingMessage.builder()
                    .id(pending.getString("id"))
                    .content(pending.getString("content"))
                    .streamingContent(pending.getString("streamingContent"))
                    .role(pending.getString("role"))
                    .isComplete(pending.getBoolean("isComplete", false))
                    .tokenCount(pending.getInteger("tokenCount"))
                    .error(pending.getString("error"))
                    .createdAt(pending.getString("createdAt"))
                    .build();

                return result;
            });
    }

    /**
     * Export user data.
     */
    @Query
    @Description("Export user data")
    public Uni<ExportData> exportData(@Name("filter") ExportFilter filter) {
        AuthContext auth = graphQLAuthContext.requireAuth();

            String userId = auth.getUserId().toString();

            boolean includeConversations = filter == null || filter.getConversations() == null || filter.getConversations();
            boolean includeMessages = filter == null || filter.getMessages() == null || filter.getMessages();
            boolean includeCharacters = filter == null || filter.getCharacters() == null || filter.getCharacters();
            boolean includeSettings = filter == null || filter.getSettings() == null || filter.getSettings();

            List<Conversation> conversationsList = new ArrayList<>();
            List<Message> messagesList = new ArrayList<>();
            List<Character> charactersList = new ArrayList<>();
            Settings[] exportSettings = {null};

            Uni<Void> exportChain = Uni.createFrom().voidItem();

            if (includeConversations) {
                String convQuery = "SELECT * FROM conversation WHERE ownerId = ? ORDER BY createdAt DESC";
                exportChain = exportChain.chain(v -> mysqlPool.preparedQuery(convQuery)
                    .execute(Tuple.of(userId))
                    .invoke(convRows -> convRows.forEach(row -> conversationsList.add(mapRowToConversation(row))))
                    .replaceWithVoid());
            }

            if (includeMessages && includeConversations) {
                String msgQuery = """
                    SELECT m.* FROM message m
                    JOIN conversation c ON m.conversationId = c.conversationGUID
                    WHERE c.ownerId = ?
                    ORDER BY m.seq ASC
                    """;
                exportChain = exportChain.chain(v -> mysqlPool.preparedQuery(msgQuery)
                    .execute(Tuple.of(userId))
                    .invoke(msgRows -> msgRows.forEach(row -> messagesList.add(mapRowToMessage(row))))
                    .replaceWithVoid());
            }

            if (includeCharacters) {
                String charQuery = "SELECT * FROM `character` WHERE ownerId = ?";
                exportChain = exportChain.chain(v -> mysqlPool.preparedQuery(charQuery)
                    .execute(Tuple.of(userId))
                    .invoke(charRows -> charRows.forEach(row -> charactersList.add(mapRowToCharacter(row))))
                    .replaceWithVoid());
            }

            if (includeSettings) {
                exportChain = exportChain.chain(v -> settingsService.getUserSettings(userId)
                    .invoke(s -> exportSettings[0] = s)
                    .onFailure().invoke(err -> log.warn("Failed to load settings for export: {}", err.getMessage()))
                    .onFailure().recoverWithItem(Settings.createDefault(userId))
                    .invoke(s -> exportSettings[0] = s)
                    .replaceWithVoid());
            }

            return exportChain.map(v -> ExportData.builder()
                .conversations(conversationsList)
                .messages(messagesList)
                .characters(charactersList)
                .settings(exportSettings[0])
                .exportDate(LocalDateTime.now(ZoneOffset.UTC).toString())
                .version("1.0")
                .build());

    }

    /**
     * Export user data in a specific format (csv, markdown, txt).
     */
    @Query
    @Description("Export user data in the specified format (csv, markdown, txt)")
    public Uni<String> exportFormattedData(@Name("format") String format,
                                            @Name("filter") ExportFilter filter) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String userId = auth.getUserId().toString();

        boolean includeConversations = filter == null || filter.getConversations() == null || filter.getConversations();
        boolean includeMessages = filter == null || filter.getMessages() == null || filter.getMessages();
        boolean includeCharacters = filter == null || filter.getCharacters() == null || filter.getCharacters();
        boolean includeSettings = filter == null || filter.getSettings() == null || filter.getSettings();

        List<Conversation> conversationsList = new ArrayList<>();
        List<Message> messagesList = new ArrayList<>();
        List<Character> charactersList = new ArrayList<>();
        Settings[] exportSettings = {null};

        Uni<Void> exportChain = Uni.createFrom().voidItem();
        String fmt = format != null ? format.toLowerCase() : "json";
        if (!Set.of("csv", "markdown", "txt", "json").contains(fmt)) {
            fmt = "json";
        }
        String finalFmt = fmt;

        if (includeConversations) {
            String convQuery = "SELECT * FROM conversation WHERE ownerId = ? ORDER BY createdAt DESC";
            exportChain = exportChain.chain(v -> mysqlPool.preparedQuery(convQuery)
                .execute(Tuple.of(userId))
                .invoke(convRows -> convRows.forEach(row -> conversationsList.add(mapRowToConversation(row))))
                .replaceWithVoid());
        }

        if (includeMessages && includeConversations) {
            String msgQuery = """
                SELECT m.* FROM message m
                JOIN conversation c ON m.conversationId = c.conversationGUID
                WHERE c.ownerId = ?
                ORDER BY m.createdAt, m.messageUUID
                """;
            exportChain = exportChain.chain(v -> mysqlPool.preparedQuery(msgQuery)
                .execute(Tuple.of(userId))
                .invoke(msgRows -> msgRows.forEach(row -> messagesList.add(mapRowToMessage(row))))
                .replaceWithVoid());
        }

        if (includeCharacters) {
            String charQuery = "SELECT * FROM `character` WHERE ownerId = ?";
            exportChain = exportChain.chain(v -> mysqlPool.preparedQuery(charQuery)
                .execute(Tuple.of(userId))
                .invoke(charRows -> charRows.forEach(row -> charactersList.add(mapRowToCharacter(row))))
                .replaceWithVoid());
        }

        if (includeSettings) {
            exportChain = exportChain.chain(v -> settingsService.getUserSettings(userId)
                .invoke(s -> exportSettings[0] = s)
                .onFailure().invoke(err -> log.warn("Failed to load settings for export: {}", err.getMessage()))
                .onFailure().recoverWithItem(Settings.createDefault(userId))
                .invoke(s -> exportSettings[0] = s)
                .replaceWithVoid());
        }

        return exportChain.map(v -> {
            ExportData data = ExportData.builder()
                .conversations(conversationsList)
                .messages(messagesList)
                .characters(charactersList)
                .settings(exportSettings[0])
                .exportDate(LocalDateTime.now(ZoneOffset.UTC).toString())
                .version("1.0")
                .build();
            return dataExportService.format(data, finalFmt);
        });
    }

    // ==========================================
    // HELPER METHODS (row mappers preserved from original)
    // ==========================================

    private User mapRowToUser(io.vertx.mutiny.sqlclient.Row row) {
        User user = new User();
        user.setId(UUID.fromString(row.getString("userUUID")));
        user.setUsername(row.getString("username"));
        user.setEmail(row.getString("email"));
        user.setDisplayName(row.getString("displayName"));
        String role = row.getString("role");
        user.setRole(role != null ? role : "USER");
        user.setAssistantName(row.getString("assistantName"));
        user.setLanguage(row.getString("language"));
        user.setNotifications(row.getBoolean("notifications"));
        user.setSounds(row.getBoolean("sounds"));
        user.setFontSize(row.getString("fontSize"));
        user.setCodeTheme(row.getString("codeTheme"));
        user.setAnalytics(row.getBoolean("analytics"));
        user.setLastLogin(Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastLogin")));
        user.setEmailVerified(row.getBoolean("emailVerified"));
        user.setProfilePicture(row.getString("profilePicture"));
        user.setBio(row.getString("bio"));
        user.setTheme(row.getString("theme"));
        user.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        user.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return user;
    }

    private Conversation mapRowToConversation(io.vertx.mutiny.sqlclient.Row row) {
        Conversation conversation = new Conversation();
        conversation.setId(UUID.fromString(row.getString("conversationGUID")));
        String ownerId = row.getString("ownerId");
        if (ownerId != null) {
            conversation.setOwnerId(UUID.fromString(ownerId));
        }
        String characterId = row.getString("characterId");
        if (characterId != null) {
            conversation.setCharacterId(UUID.fromString(characterId));
        }
        conversation.setTitle(row.getString("title"));
        conversation.setDescription(row.getString("description"));
        conversation.setActive(row.getBoolean("active"));
        conversation.setIsPinned(row.getBoolean("isPinned"));
        conversation.setIsArchived(row.getBoolean("isArchived"));
        conversation.setMessageCount(row.getInteger("messageCount"));
        conversation.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        conversation.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));

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

    private Message mapRowToMessage(io.vertx.mutiny.sqlclient.Row row) {
        Message message = new Message();
        message.setId(UUID.fromString(row.getString("messageUUID")));
        message.setSeq(row.getLong("seq"));
        String convId = row.getString("conversationId");
        if (convId != null) {
            message.setConversationId(UUID.fromString(convId));
        }
        String ownerId = row.getString("ownerId");
        if (ownerId != null) {
            message.setOwnerId(UUID.fromString(ownerId));
        }
        String charId = row.getString("characterId");
        if (charId != null) {
            message.setCharacterId(UUID.fromString(charId));
        }
        // Decode Base64 content the same way MessageService.mapRowToMessage
        // does, so a message is never surfaced raw-encoded through one query
        // path and decoded through another.
        String content = row.getString("content");
        Boolean isBase64 = row.getBoolean("isBase64Encoded");
        if (Boolean.TRUE.equals(isBase64) && content != null) {
            content = Base64Helper.decodeIfBase64(content);
        }
        message.setContent(content);
        message.setRole(MessageRole.fromString(row.getString("role")));
        message.setDisplayName(row.getString("displayName"));
        message.setIsBase64Encoded(Boolean.TRUE.equals(isBase64));
        message.setIsEdited(row.getBoolean("isEdited"));
        message.setIsDeleted(row.getBoolean("isDeleted"));
        message.setEditCount(row.getInteger("editCount"));
        message.setModel(row.getString("model"));
        message.setPromptTokens(row.getInteger("promptTokens"));
        message.setCompletionTokens(row.getInteger("completionTokens"));
        message.setPromptEvalMs(row.getInteger("promptEvalMs"));
        message.setGenerationMs(row.getInteger("generationMs"));
        message.setTotalTimeMs(row.getInteger("totalTimeMs"));
        message.setTokensPerSecond(row.getDouble("tokensPerSecond"));
        message.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        message.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        String sceneId = row.getString("sceneId");
        if (sceneId != null) {
            message.setSceneId(UUID.fromString(sceneId));
        }
        message.setMessageType(MessageType.fromString(row.getString("messageType")));
        message.setIsStreaming(row.getBoolean("isStreaming"));
        message.setIsInterjection(row.getBoolean("isInterjection"));
        message.setVisibilityOverride(ServiceHelper.safeGetJsonObject(row, "visibilityOverride"));
        message.setAgentMetadata(ServiceHelper.safeGetJsonObject(row, "agentMetadata"));
        return message;
    }

    private Character mapRowToCharacter(io.vertx.mutiny.sqlclient.Row row) {
        Character character = new Character();
        character.setId(UUID.fromString(row.getString("characterUUID")));
        character.setName(row.getString("name"));
        character.setDescription(row.getString("description"));
        character.setSystemPrompt(row.getString("systemPrompt"));
        character.setAppearance(row.getString("appearance"));
        character.setPersonality(row.getString("personality"));
        character.setGender(row.getString("gender"));
        character.setPronouns(row.getString("pronouns"));
        character.setAgeYears(row.getInteger("ageYears"));
        character.setHeight(row.getString("height"));
        character.setBuild(row.getString("build"));
        character.setHair(row.getString("hair"));
        character.setEyes(row.getString("eyes"));
        character.setStrength(row.getInteger("strength"));
        character.setDexterity(row.getInteger("dexterity"));
        character.setConstitution(row.getInteger("constitution"));
        character.setIntelligence(row.getInteger("intelligence"));
        character.setWisdom(row.getInteger("wisdom"));
        character.setCharisma(row.getInteger("charisma"));
        character.setLikes(row.getString("likes"));
        character.setDislikes(row.getString("dislikes"));
        character.setBackstory(row.getString("backstory"));
        character.setGoals(row.getString("goals"));
        character.setExamples(row.getString("examples"));
        character.setAvatar(row.getString("avatar"));
        character.setGreeting(row.getString("greeting"));
        character.setCategory(row.getString("category"));
        character.setInitialAffinityToUser(row.getInteger("initialAffinityToUser"));
        character.setInitialAffinityFromUser(row.getInteger("initialAffinityFromUser"));
        character.setIsDefault(row.getBoolean("isDefault"));
        character.setIsPublic(row.getBoolean("isPublic"));
        character.setIsFavorite(false);
        character.setTemperature(row.getDouble("temperature"));
        character.setMaxTokens(row.getInteger("maxTokens"));
        character.setTopP(row.getDouble("topP"));
        character.setTopK(row.getInteger("topK"));
        character.setRepeatPenalty(row.getDouble("repeatPenalty"));
        character.setRating(row.getDouble("rating"));
        character.setUsageCount(row.getInteger("usageCount"));
        String ownerId = row.getString("ownerId");
        if (ownerId != null) {
            character.setOwnerId(UUID.fromString(ownerId));
        }
        String tagsStr = row.getString("tags");
        if (tagsStr != null && !tagsStr.isEmpty()) {
            character.setTagsFromString(tagsStr);
        }
        character.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        character.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return character;
    }

}
