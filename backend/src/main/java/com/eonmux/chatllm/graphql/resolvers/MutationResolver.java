package com.eonmux.chatllm.graphql.resolvers;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.utils.DisplayNameFormatter;
import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.models.*;
import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.models.inputs.*;
import com.eonmux.chatllm.services.auth.AuthContext;
import com.eonmux.chatllm.services.auth.JWTAuthProvider;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import io.smallrye.graphql.api.ErrorCode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * GraphQL Mutation resolver.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class MutationResolver {

    @Inject
    Vertx vertx;
    @Inject
    Pool mysqlPool;
    @Inject
    Redis redisClient;
    @Inject
    JWTAuthProvider jwtAuthProvider;
    @Inject
    AppConfig config;
    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    jakarta.enterprise.inject.Instance<io.smallrye.graphql.api.Context> graphqlInputContext;
    @Inject
    com.eonmux.chatllm.services.SettingsService settingsService;
    @Inject
    com.eonmux.chatllm.services.UserPresenceService userPresenceService;
    @Inject
    com.eonmux.chatllm.utils.ServiceHelper serviceHelper;
    @Inject
    com.eonmux.chatllm.services.GroupChatService groupChatService;

    private static final int DEFAULT_BCRYPT_ROUNDS = 12;

    private int bcryptRounds() {
        int rounds = config.getBcryptRounds();
        return rounds > 0 ? rounds : DEFAULT_BCRYPT_ROUNDS;
    }

    /**
     * Record a login attempt in the rate limiter.
     * Uses Redis sorted set with timestamp as score for sliding window.
     */
    private void recordLoginAttempt(String rateLimitKey, long currentTime) {
        redisClient.connect()
            .onSuccess(connection -> {
                // Add the attempt timestamp and set expiry
                connection.send(Request.cmd(Command.ZADD)
                        .arg(rateLimitKey)
                        .arg(String.valueOf(currentTime))
                        .arg(String.valueOf(currentTime)))
                    .compose(v -> connection.send(Request.cmd(Command.EXPIRE)
                        .arg(rateLimitKey)
                        .arg("120"))) // 2 minute expiry
                    .onComplete(ar -> connection.close());
            })
            .onFailure(error -> log.warn("Failed to record login attempt in rate limiter", error));
    }

    /**
     * Check message rate limit for a user using Redis sliding window algorithm.
     * Returns a Future with the remaining request count, or fails if rate limit exceeded.
     *
     * Transaction order:
     * 0. ZREMRANGEBYSCORE - Remove old entries outside the 1-minute window
     * 1. ZADD - Add current request timestamp
     * 2. ZCARD - Count total requests in window (including current)
     * 3. EXPIRE - Set key expiration
     */
    private Future<Integer> checkMessageRateLimit(String userId) {
        String key = "message_rate_limit:" + userId;
        long currentTime = System.currentTimeMillis();
        long windowStart = currentTime - TimeUnit.MINUTES.toMillis(1); // 1-minute window
        int maxRequests = config.getMessageRateLimitMaxRequests() != null ? config.getMessageRateLimitMaxRequests() : 10;

        // MULTI/EXEC must run on a single connection — pooled .send() calls can
        // land on different connections, which causes queued commands to execute
        // outside the transaction.
        return redisClient.connect()
            .compose(connection -> connection.send(Request.cmd(Command.MULTI))
                .compose(v -> connection.send(Request.cmd(Command.ZREMRANGEBYSCORE)
                    .arg(key)
                    .arg("0")
                    .arg(String.valueOf(windowStart))))
                .compose(v -> connection.send(Request.cmd(Command.ZADD)
                    .arg(key)
                    .arg(String.valueOf(currentTime))
                    .arg(String.valueOf(currentTime))))
                .compose(v -> connection.send(Request.cmd(Command.ZCARD)
                    .arg(key)))
                .compose(v -> connection.send(Request.cmd(Command.EXPIRE)
                    .arg(key)
                    .arg("120")))
                .compose(v -> connection.send(Request.cmd(Command.EXEC)))
                .onComplete(ar -> connection.close()))
            .compose(response -> {
                try {
                    // Check the count from ZCARD result (3rd command in transaction, index 2)
                    if (response != null && response.size() > 2 && response.get(2) != null) {
                        Long count = response.get(2).toLong();

                        if (count != null && count > maxRequests) {
                            // Rate limit exceeded - count > maxRequests because we already added the current request
                            log.warn("Message rate limit exceeded for user: {}, count: {}, max: {}", userId, count, maxRequests);
                            return Future.failedFuture(new RateLimitExceededException(
                                "Message rate limit exceeded. Maximum " + maxRequests + " messages per minute allowed.",
                                60, // Retry-After in seconds
                                maxRequests,
                                0 // No remaining
                            ));
                        }

                        int remaining = Math.max(0, maxRequests - count.intValue());
                        log.debug("Message rate limit check passed for user: {}, count: {}, remaining: {}", userId, count, remaining);
                        return Future.succeededFuture(remaining);
                    } else {
                        // If Redis response is unexpected, allow request but log warning
                        log.warn("Unexpected Redis response format for message rate limiting (size: {}), allowing request",
                            response != null ? response.size() : "null");
                        return Future.succeededFuture(maxRequests);
                    }
                } catch (Exception e) {
                    log.error("Error processing message rate limit response: {}", e.getMessage());
                    return Future.succeededFuture(maxRequests);
                }
            })
            .recover(error -> {
                // If it's our rate limit exception, propagate it (don't recover)
                if (error instanceof RateLimitExceededException) {
                    return Future.failedFuture(error);
                }
                // If Redis fails, allow request but log error (fail-open for availability)
                log.error("Message rate limiting check failed, allowing request", error);
                return Future.succeededFuture(maxRequests);
            });
    }

    /**
     * Custom exception for rate limit exceeded.
     */
    @ErrorCode("RATE_LIMITED")
    public static class RateLimitExceededException extends RuntimeException {
        private final int retryAfterSeconds;
        private final int limit;
        private final int remaining;

        public RateLimitExceededException(String message, int retryAfterSeconds, int limit, int remaining) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
            this.limit = limit;
            this.remaining = remaining;
        }

        public int getRetryAfterSeconds() { return retryAfterSeconds; }
        public int getLimit() { return limit; }
        public int getRemaining() { return remaining; }
    }

    /**
     * Register a new user.
     */
    @Mutation
    public CompletableFuture<AuthResponse> register(@Name("username") String username, @Name("email") String email, @Name("password") String password) {
        String displayName = DisplayNameFormatter.normalize(username);

        if (password == null || password.length() < 8) {
            return CompletableFuture.failedFuture(new GraphQLException("Password must be at least 8 characters long"));
        }

        if (email == null || !email.matches("^[A-Za-z0-9+_.-]+@(.+)$")) {
            return CompletableFuture.failedFuture(new GraphQLException("Invalid email format"));
        }

        CompletableFuture<AuthResponse> future = new CompletableFuture<>();

        // Check if username already exists
        String checkUserQuery = "SELECT COUNT(*) as count FROM user WHERE username = ?";
        String checkEmailQuery = "SELECT COUNT(*) as count FROM user WHERE email = ?";
        mysqlPool.preparedQuery(checkUserQuery)
            .execute(Tuple.of(username))
            .compose(rows -> {
                Long count = rows.iterator().next().getLong("count");
                if (count > 0) {
                    return Future.failedFuture(new GraphQLException("Username already exists"));
                }
                return mysqlPool.preparedQuery(checkEmailQuery).execute(Tuple.of(email));
            })
            .compose(rows -> {
                Long count = rows.iterator().next().getLong("count");
                if (count > 0) {
                    return Future.failedFuture(new GraphQLException("Email already exists"));
                }

                // Hash password on worker thread (BCrypt is CPU-bound, ~250ms)
                return vertx.<String>executeBlocking(promise ->
                    promise.complete(BCrypt.withDefaults().hashToString(bcryptRounds(), password.toCharArray()))
                ).compose(passwordHash -> {
                // Create user
                UUID userId = UUID.randomUUID();
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                String insertQuery = """
                    INSERT INTO user (userUUID, username, passwordHash, email, displayName,
                                     language, notifications, sounds, fontSize, codeTheme,
                                     analytics, improvement, apiLimits, chatLimits, role, createdAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;

                return mysqlPool.preparedQuery(insertQuery)
                    .execute(io.vertx.sqlclient.Tuple.tuple()
                        .addValue(userId.toString())
                        .addValue(username)
                        .addValue(passwordHash)
                        .addValue(email)
                        .addValue(displayName != null ? displayName : username)
                        .addValue("en")
                        .addValue(false)
                        .addValue(false)
                        .addValue("medium")
                        .addValue("prism")
                        .addValue(false)
                        .addValue(false)
                        .addValue(-1L)
                        .addValue(-1L)
                        .addValue("USER")
                        .addValue(now))
                    .map(v -> userId);
                });
            })
            .onSuccess(userId -> {
                // Create JWT tokens
                Set<String> roles = new HashSet<>();
                roles.add("USER");
                String effectiveDisplayName = displayName != null ? displayName : username;

                JsonObject tokensJson = jwtAuthProvider.generateTokensResponse(userId, username, effectiveDisplayName, roles);

                User userObj = User.builder()
                    .id(userId)
                    .username(username)
                    .email(email)
                    .displayName(effectiveDisplayName)
                    .role("USER")
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                    .build();
                AuthResponse authResponse = AuthResponse.builder()
                    .accessToken(tokensJson.getString("accessToken"))
                    .refreshToken(tokensJson.getString("refreshToken"))
                    .expiresIn(tokensJson.getInteger("expiresIn"))
                    .user(userObj)
                    .build();

                // Persist the refresh token in Redis so refreshToken() can
                // validate it later. login() does this; register() previously
                // omitted it, making subsequent refreshes impossible.
                String refreshToken = tokensJson.getString("refreshToken");
                String redisKey = "refresh_token:" + userId;
                redisClient.connect()
                    .compose(connection ->
                        io.vertx.redis.client.RedisAPI.api(connection)
                            .setex(redisKey, "604800", refreshToken)
                            .onComplete(ar -> connection.close()))
                    .onSuccess(v -> {
                        userPresenceService.updatePresence(userId.toString(), "online");
                        future.complete(authResponse);
                    })
                    .onFailure(err -> {
                        log.warn("Failed to store refresh token on register", err);
                        userPresenceService.updatePresence(userId.toString(), "online");
                        future.complete(authResponse); // still succeed
                    });
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Login user with rate limiting.
     * Rate limits login attempts per IP to prevent brute force attacks.
     */
    @Mutation
    public CompletableFuture<AuthResponse> login(@Name("username") String username, @Name("password") String password) {

        CompletableFuture<AuthResponse> future = new CompletableFuture<>();

        // Get client IP for rate limiting
        String clientIp = graphQLAuthContext.getClientIp();
        if (clientIp == null) {
            clientIp = "unknown";
        }
        final String rateLimitKey = "login_rate:" + clientIp;
        long currentTime = System.currentTimeMillis();
        long windowStart = currentTime - TimeUnit.MINUTES.toMillis(1);
        int maxLoginAttempts = config.getLoginRateLimitMaxRequests() != null ? config.getLoginRateLimitMaxRequests() : 5;

        // Check rate limit using Redis sliding window
        final String finalClientIp = clientIp;
        redisClient.connect()
            .onSuccess(connection -> {
                // Use MULTI/EXEC for atomic rate limit check + attempt recording
                connection.send(Request.cmd(Command.MULTI))
                    .compose(v -> connection.send(Request.cmd(Command.ZREMRANGEBYSCORE)
                        .arg(rateLimitKey).arg("0").arg(String.valueOf(windowStart))))
                    .compose(v -> connection.send(Request.cmd(Command.ZADD)
                        .arg(rateLimitKey)
                        .arg(String.valueOf(currentTime))
                        .arg(String.valueOf(currentTime))))
                    .compose(v -> connection.send(Request.cmd(Command.ZCARD).arg(rateLimitKey)))
                    .compose(v -> connection.send(Request.cmd(Command.EXPIRE)
                        .arg(rateLimitKey)
                        .arg("120")))
                    .compose(v -> connection.send(Request.cmd(Command.EXEC)))
                    .onComplete(ar -> {
                        connection.close();

                        if (ar.succeeded()) {
                            Response response = ar.result();
                            // EXEC returns the result of each queued command in order.
                            // Queued commands: 0=ZREMRANGEBYSCORE, 1=ZADD, 2=ZCARD, 3=EXPIRE.
                            // (The leading MULTI "OK" is NOT included in the EXEC array.)
                            // ZCARD is therefore at index 2. This mirrors checkMessageRateLimit().
                            Long count = (response != null && response.size() > 2 && response.get(2) != null)
                                ? response.get(2).toLong() : 0L;

                            if (count != null && count > maxLoginAttempts) {
                                log.warn("Login rate limit exceeded for IP: {}", finalClientIp);
                                future.completeExceptionally(new GraphQLException(
                                    "Too many login attempts. Please wait a minute before trying again."));
                                return;
                            }

                            // Proceed with login (attempt already counted)
                            performLogin(username, password, future, null, currentTime);
                        } else {
                            // Redis error - proceed with login anyway (fail open for availability)
                            log.warn("Redis error during rate limit check, proceeding with login", ar.cause());
                            performLogin(username, password, future, null, currentTime);
                        }
                    });
            })
            .onFailure(error -> {
                // Redis connection error - proceed with login (fail open)
                log.warn("Redis connection error, proceeding with login", error);
                performLogin(username, password, future, null, currentTime);
            });

        return future;
    }

    /**
     * Perform the actual login after rate limit check.
     */
    private void performLogin(String username, String password,
                              CompletableFuture<AuthResponse> future,
                              String rateLimitKey, long currentTime) {
        // Dummy hash for timing-safe comparison when user not found.
        // This prevents username enumeration via timing attacks.
        final String DUMMY_HASH = "$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/X4.VTtYw.VNjGsWKe";

        // Support login by username OR email
        String query = "SELECT * FROM user WHERE username = ? OR email = ?";
        mysqlPool.preparedQuery(query)
            .execute(Tuple.of(username, username))
            .compose(rows -> {
                String storedHash;
                Row row = null;
                boolean userExists = rows.size() > 0;

                if (userExists) {
                    row = rows.iterator().next();
                    storedHash = row.getString("passwordHash");
                } else {
                    storedHash = DUMMY_HASH;
                }

                final String hashToVerify = storedHash;
                final Row userRow = row;
                final boolean userValid = userExists;

                // BCrypt verification is CPU-bound (~250ms) — offload to worker thread
                return vertx.<BCrypt.Result>executeBlocking(promise ->
                    promise.complete(BCrypt.verifyer().verify(password.toCharArray(), hashToVerify))
                ).compose(bcryptResult -> {
                    // Record this login attempt in rate limiter
                    if (rateLimitKey != null) {
                        recordLoginAttempt(rateLimitKey, currentTime);
                    }

                    // Now check if user exists and password is correct
                    if (!userValid || !bcryptResult.verified) {
                        return Future.failedFuture(new GraphQLException("Invalid credentials"));
                    }

                    // Check if user is banned
                    Boolean isBanned = userRow.getBoolean("isBanned");
                    if (Boolean.TRUE.equals(isBanned)) {
                        String bannedReason = userRow.getString("bannedReason");
                        String errorMessage = "Your account has been banned";
                        if (bannedReason != null && !bannedReason.isEmpty()) {
                            errorMessage += ": " + bannedReason;
                        }
                        return Future.failedFuture(new GraphQLException(errorMessage));
                    }

                    UUID userId = UUID.fromString(userRow.getString("userUUID"));
                    String actualUsername = userRow.getString("username");
                    String role = userRow.getString("role");
                    String displayName = userRow.getString("displayName");
                    Set<String> roles = new HashSet<>();
                    roles.add("USER");
                    if ("ADMIN".equals(role)) {
                        roles.add("ADMIN");
                    }

                    JsonObject tokensJson = jwtAuthProvider.generateTokensResponse(userId, actualUsername, displayName, roles);

                    LocalDateTime createdAtLocal = userRow.getLocalDateTime("createdAt");
                    User userObj = User.builder()
                        .id(userId)
                        .username(actualUsername)
                        .email(userRow.getString("email"))
                        .displayName(displayName)
                        .role(role != null ? role : "USER")
                        .createdAt(createdAtLocal != null ? createdAtLocal.toString() : LocalDateTime.now(ZoneOffset.UTC).toString())
                        .build();
                    AuthResponse authResponse = AuthResponse.builder()
                        .accessToken(tokensJson.getString("accessToken"))
                        .refreshToken(tokensJson.getString("refreshToken"))
                        .expiresIn(tokensJson.getInteger("expiresIn"))
                        .user(userObj)
                        .build();

                    // Store refresh token in Redis
                    String refreshToken = tokensJson.getString("refreshToken");
                    String redisKey = "refresh_token:" + userId;
                    return redisClient.connect()
                        .compose(connection ->
                            io.vertx.redis.client.RedisAPI.api(connection)
                                .setex(redisKey, "604800", refreshToken)
                                .onComplete(ar -> connection.close()))
                        .map(v -> {
                            userPresenceService.updatePresence(userId.toString(), "online");
                            return authResponse;
                        });
                });
            })
            .onSuccess(future::complete)
            .onFailure(future::completeExceptionally);
    }

    /**
     * Logout user.
     */
    @Mutation
    public CompletableFuture<Boolean> logout() {
        AuthContext auth = graphQLAuthContext.getAuthContext();
        if (auth == null || !auth.isAuthenticated()) {
            return CompletableFuture.failedFuture(new RuntimeException("Not authenticated"));
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Blacklist the current access token so it stops being valid before its
        // natural expiry (4h). Without this a logged-out session remains usable.
        jwtAuthProvider.revokeAccessToken(auth).subscribe().with(
            v -> {},
            err -> log.warn("Access token blacklisting failed during logout: {}", err.getMessage()));

        // Remove refresh token from Redis
        String redisKey = "refresh_token:" + auth.getUserId();
        final String logoutUserId = auth.getUserId().toString();
        redisClient.connect()
            .compose(connection ->
                io.vertx.redis.client.RedisAPI.api(connection)
                    .del(List.of(redisKey))
                    .onComplete(ar -> connection.close()))
            .onSuccess(v -> {
                userPresenceService.updatePresence(logoutUserId, "offline");
                future.complete(true);
            })
            .onFailure(error -> {
                log.warn("Failed to remove refresh token", error);
                userPresenceService.updatePresence(logoutUserId, "offline");
                future.complete(true); // Still return success
            });

        return future;
    }

    /**
     * Refresh access token.
     */
    @Mutation
    public CompletableFuture<AuthResponse> refreshToken(@Name("refreshToken") String refreshToken) {

        CompletableFuture<AuthResponse> future = new CompletableFuture<>();

        jwtAuthProvider.verifyToken(refreshToken).subscribe().with(jwt -> {
                if (!"refresh".equals(jwt.getClaim("type").asString())) {
                    future.completeExceptionally(new RuntimeException("Invalid refresh token"));
                    return;
                }

                UUID userId = UUID.fromString(jwt.getSubject());

                // Verify refresh token in Redis
                String redisKey = "refresh_token:" + userId;
                redisClient.connect()
                    .compose(connection ->
                        io.vertx.redis.client.RedisAPI.api(connection)
                            .get(redisKey)
                            .onComplete(ar -> connection.close()))
                    .compose(storedToken -> {
                        if (storedToken == null || !refreshToken.equals(storedToken.toString())) {
                            return Future.failedFuture(new RuntimeException("Invalid refresh token"));
                        }

                        // Get user info including role
                        String query = "SELECT username, displayName, role, isBanned FROM user WHERE userUUID = ?";
                        return mysqlPool.preparedQuery(query)
                            .execute(Tuple.of(userId.toString()))
                            .map(rows -> {
                                if (rows.size() == 0) {
                                    throw new RuntimeException("User not found");
                                }

                                Row row = rows.iterator().next();

                                // Check if user is banned
                                if (Boolean.TRUE.equals(row.getBoolean("isBanned"))) {
                                    throw new RuntimeException("Account has been banned");
                                }

                                String username = row.getString("username");
                                String displayName = row.getString("displayName");
                                String role = row.getString("role");
                                Set<String> roles = new HashSet<>();
                                roles.add("USER");
                                // Check for ADMIN role from database
                                if ("ADMIN".equals(role)) {
                                    roles.add("ADMIN");
                                }

                                // Generate new access token with current roles and displayName
                                String accessToken = jwtAuthProvider.generateAccessToken(userId, username, displayName, roles);

                                AuthResponse response = AuthResponse.builder()
                                    .accessToken(accessToken)
                                    .refreshToken(refreshToken)
                                    .expiresIn((int) (config.getJwtAccessTokenExpiry() / 1000))
                                    .build();
                                return response;
                            });
                    })
                    .onSuccess(response -> {
                        userPresenceService.updatePresence(userId.toString(), "online");
                        future.complete(response);
                    })
                    .onFailure(future::completeExceptionally);
            },
            future::completeExceptionally);

        return future;
    }

    /**
     * Create a new conversation.
     */
    @Mutation
    public CompletableFuture<Conversation> createConversation(@Name("input") CreateConversationInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String title = input != null ? (String) input.get("title") : null;
        String characterIdStr = input != null ? (String) input.get("characterId") : null;
        UUID characterId = characterIdStr != null ? UUID.fromString(characterIdStr) : null;

        CompletableFuture<Conversation> future = new CompletableFuture<>();

        UUID conversationId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        String insertQuery = """
            INSERT INTO conversation (conversationGUID, ownerId, characterId, title, createdAt, updatedAt, lastModified, active)
            VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)
            """;

        mysqlPool.preparedQuery(insertQuery)
            .execute(io.vertx.sqlclient.Tuple.tuple()
                .addValue(conversationId.toString())
                .addValue(auth.getUserId().toString())
                .addValue(characterId != null ? characterId.toString() : null)
                .addValue(title)
                .addValue(now)
                .addValue(now)
                .addValue(now))
            .compose(v -> {
                // Update user's current conversation
                String updateQuery = "UPDATE user SET currentConversationId = ? WHERE userUUID = ?";
                return mysqlPool.preparedQuery(updateQuery)
                    .execute(Tuple.of(conversationId.toString(), auth.getUserId().toString()));
            })
            .compose(v -> {
                // Send initial greeting if character exists, wait for it to complete
                if (characterId != null) {
                    return sendCharacterGreeting(conversationId, characterId, auth.getUserId());
                }
                return Future.succeededFuture();
            })
            .onSuccess(v -> {
                Conversation conversation = Conversation.builder()
                    .id(conversationId)
                    .ownerId(auth.getUserId())
                    .characterId(characterId)
                    .title(title)
                    .createdAt(now.toString())
                    .updatedAt(now.toString())
                    .messages(new ArrayList<>())
                    .messageCount(characterId != null ? 1 : 0) // Include greeting in count if character was set
                    .build();

                // Publish conversation created event for subscription
                JsonObject updateEvent = new JsonObject()
                    .put("id", conversationId.toString())
                    .put("ownerId", auth.getUserId().toString())
                    .put("characterId", characterId != null ? characterId.toString() : null)
                    .put("title", title)
                    .put("messageCount", characterId != null ? 1 : 0)
                    .put("createdAt", now.toString())
                    .put("updatedAt", now.toString())
                    .put("eventType", "created");
                vertx.eventBus().publish("conversation:" + conversationId + ":updated", updateEvent);
                log.debug("Published conversation created event for: {}", conversationId);

                future.complete(conversation);
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Send a message in a conversation.
     *
     * CRITICAL FIX (Issue #1): Message insert and messageCount update are now atomic
     * using a database transaction. This prevents race conditions where messageCount
     * could become out of sync with actual message count.
     *
     * CRITICAL FIX (Issue #7): Added Base64 validation when isBase64Encoded flag is set.
     */
    @Mutation
    public CompletableFuture<Message> sendMessage(@Name("input") SendMessageInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        UUID conversationId = UUID.fromString((String) input.get("conversationId"));
        String content = (String) input.get("content");
        Boolean isBase64Encoded = (Boolean) input.get("isBase64Encoded");

        // Extract attachments if provided
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachmentsInput = (List<Map<String, Object>>) input.get("attachments");

        CompletableFuture<Message> future = new CompletableFuture<>();

        // Issue #7 Fix: Validate that content is actually Base64 if flag is set
        if (Boolean.TRUE.equals(isBase64Encoded)) {
            try {
                // Validate that the content is valid Base64
                java.util.Base64.getDecoder().decode(content);
            } catch (IllegalArgumentException e) {
                return CompletableFuture.failedFuture(new RuntimeException(
                    "isBase64Encoded=true but content is not valid Base64: " + e.getMessage()));
            }
        }

        // Check message rate limit first
        checkMessageRateLimit(auth.getUserId().toString())
            .compose(remaining -> {
                // Rate limit check passed, verify conversation access (owner OR active
                // participant). Owner-only here blocked participants in shared/group
                // conversations from replying even though they can read the history.
                String verifyQuery = """
                    SELECT COUNT(*) as count FROM conversation c
                    LEFT JOIN conversation_participant cp
                        ON c.conversationGUID = cp.conversation_id AND cp.user_id = ? AND cp.is_active = true
                    WHERE c.conversationGUID = ? AND (c.ownerId = ? OR cp.user_id = ?)
                    """;
                String uid = auth.getUserId().toString();
                return mysqlPool.preparedQuery(verifyQuery)
                    .execute(Tuple.of(uid, conversationId.toString(), uid, uid));
            })
            .compose(rows -> {
                Long count = rows.iterator().next().getLong("count");
                if (count == 0) {
                    return Future.failedFuture(new RuntimeException("Conversation not found or unauthorized"));
                }

                // Issue #1 Fix: Use transaction for atomic message insert + messageCount update
                UUID messageId = UUID.randomUUID();
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

                return mysqlPool.withTransaction(client -> {
                    // Step 1: Insert message within transaction
                    String insertQuery = """
                        INSERT INTO message (messageUUID, conversationId, content, role, displayName,
                                           isBase64Encoded, ownerId, createdAt)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """;

                    return client.preparedQuery(insertQuery)
                        .execute(io.vertx.sqlclient.Tuple.tuple()
                            .addValue(messageId.toString())
                            .addValue(conversationId.toString())
                            .addValue(content)
                            .addValue(Message.ROLE_USER)
                            .addValue(auth.getUsername())
                            .addValue(isBase64Encoded != null ? isBase64Encoded : false)
                            .addValue(auth.getUserId().toString())
                            .addValue(now))
                        .compose(insertResult -> {
                            // Step 2: Update conversation timestamp and messageCount atomically
                            // A user message starts a fresh character-turn budget.
                            String updateQuery = "UPDATE conversation SET updatedAt = ?, consecutiveResponseCount = 0, messageCount = IFNULL(messageCount, 0) + 1 WHERE conversationGUID = ?";
                            return client.preparedQuery(updateQuery)
                                .execute(Tuple.of(now, conversationId.toString()));
                        })
                        .compose(v -> {
                            // Step 3: Insert attachments if provided
                            if (attachmentsInput != null && !attachmentsInput.isEmpty()) {
                                List<Future<Void>> attachmentFutures = new ArrayList<>();

                                for (int i = 0; i < attachmentsInput.size(); i++) {
                                    Map<String, Object> att = attachmentsInput.get(i);
                                    String attachmentId = UUID.randomUUID().toString();
                                    String filename = (String) att.get("filename");
                                    String mimeType = (String) att.get("mimeType");
                                    Integer size = (Integer) att.get("size");
                                    String url = (String) att.get("url");
                                    final int displayOrder = i;

                                    // Insert into attachment table
                                    String attachmentInsert = """
                                        INSERT INTO attachment (attachmentId, messageId, filename, mimeType, size, url, created)
                                        VALUES (?, ?, ?, ?, ?, ?, ?)
                                        """;

                                    Future<Void> insertFuture = client.preparedQuery(attachmentInsert)
                                        .execute(io.vertx.sqlclient.Tuple.tuple()
                                            .addValue(attachmentId)
                                            .addValue(messageId.toString())
                                            .addValue(filename)
                                            .addValue(mimeType)
                                            .addValue(size)
                                            .addValue(url)
                                            .addValue(now))
                                        .compose(r -> {
                                            // Insert into message_attachment junction table
                                            String junctionInsert = """
                                                INSERT INTO message_attachment (message_id, attachment_id, display_order)
                                                VALUES (?, ?, ?)
                                                """;
                                            return client.preparedQuery(junctionInsert)
                                                .execute(Tuple.of(messageId.toString(), attachmentId, displayOrder))
                                                .mapEmpty();
                                        });

                                    attachmentFutures.add(insertFuture);
                                }

                                return Future.all(attachmentFutures).mapEmpty();
                            }
                            return Future.succeededFuture();
                        })
                        .map(v -> Message.builder()
                            .id(messageId)
                            .conversationId(conversationId)
                            .ownerId(auth.getUserId())
                            .content(content)
                            .role(MessageRole.USER)
                            .displayName(auth.getUsername())
                            .isBase64Encoded(isBase64Encoded != null ? isBase64Encoded : false)
                            .createdAt(now.toString())
                            .updatedAt(now.toString())
                            .editCount(0)
                            .build());
                });
            })
            .compose(message -> {
                // Trigger LLM response via EventBus
                // (This happens AFTER transaction commits to ensure message exists)
                String userId = auth.getUserId().toString();

                // Check if this is a roleplay conversation - if so, route through
                // the character response path instead of generic LLM generation
                return mysqlPool.preparedQuery(
                        "SELECT isRoleplay FROM conversation WHERE conversationGUID = ?")
                    .execute(Tuple.of(conversationId.toString()))
                    .compose(convRows -> {
                        boolean isRoleplay = false;
                        if (convRows.size() > 0) {
                            isRoleplay = Boolean.TRUE.equals(
                                convRows.iterator().next().getBoolean("isRoleplay"));
                        }

                        if (isRoleplay) {
                            // Roleplay: trigger character response with pipeline support
                            triggerRoleplayCharacterResponse(conversationId.toString(), userId);
                            return Future.succeededFuture(message);
                        }

                        // Regular chat: fetch settings and trigger LLM response
                        String settingsQuery = """
                            SELECT s.llmTemperature, s.llmMaxTokens, s.llmTopP, s.llmTopK, s.llmContextSize
                            FROM user_settings s WHERE s.fk_userUUID = ?
                            """;
                        return mysqlPool.preparedQuery(settingsQuery)
                            .execute(Tuple.of(userId))
                            .map(rows -> {
                                // Default LLM settings
                                double temperature = 0.7;
                                int maxTokens = -1;  // -1 means unlimited (LLMVerticle converts to 1024000)
                                double topP = 0.95;
                                int topK = 40;
                                int contextSize = 8192;

                                if (rows.size() > 0) {
                                    var row = rows.iterator().next();
                                    if (row.getFloat("llmTemperature") != null) {
                                        temperature = row.getFloat("llmTemperature");
                                    }
                                    if (row.getInteger("llmMaxTokens") != null) {
                                        maxTokens = row.getInteger("llmMaxTokens");
                                    }
                                    if (row.getFloat("llmTopP") != null) {
                                        topP = row.getFloat("llmTopP");
                                    }
                                    if (row.getInteger("llmTopK") != null) {
                                        topK = row.getInteger("llmTopK");
                                    }
                                    if (row.getInteger("llmContextSize") != null) {
                                        contextSize = row.getInteger("llmContextSize");
                                    }
                                }

                                // Issue #8 Fix: Use request() with error handling instead of fire-and-forget send()
                                // Note: userName is resolved centrally by LLMVerticle using userId
                                JsonObject request = new JsonObject()
                                    .put("conversationId", conversationId.toString())
                                    .put("messageId", message.getId().toString())
                                    .put("userId", userId)
                                    .put("content", message.getDecodedContent())
                                    .put("temperature", temperature)
                                    .put("maxTokens", maxTokens)
                                    .put("topP", topP)
                                    .put("topK", topK)
                                    .put("contextSize", contextSize);

                                // Reply-await deadline must exceed the backend's absolute cap
                                // (chatllm.llm.request-timeout) so the originator never gives
                                // up before LLMBridge's idle watchdog or absolute-timeout
                                // fires first. Otherwise long-but-still-streaming generations
                                // get killed at the sender side mid-flight.
                                long replyTimeoutMs = config.getLlmRequestTimeout() + 30_000L;
                                DeliveryOptions options = new DeliveryOptions().setSendTimeout(replyTimeoutMs);
                                vertx.eventBus().request("llm.generate", request, options)
                                    .onSuccess(reply -> log.debug("LLM generation initiated for message: {}", message.getId()))
                                    .onFailure(error -> {
                                        log.error("LLM generation failed for message: {}. Error: {}", message.getId(), error.getMessage());
                                        // Client notification is handled by the streaming error frame
                                        // published to conversation:{id}:message-stream by LLMBridge.
                                        // (The previous publish to "message:updates" had no consumer.)
                                    });

                                return message;
                            });
                    });
            })
            .onSuccess(future::complete)
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Trigger a character response for a roleplay conversation that was reached
     * via the regular sendMessage path (backward compat for existing single-character
     * roleplay conversations with isGroupChat=FALSE).
     * Looks up the character from conversation_character and fires group.character.respond.
     */
    private void triggerRoleplayCharacterResponse(String conversationId, String userId) {
        vertx.runOnContext(v -> groupChatService.startCharacterTurn(conversationId, userId)
                .subscribe().with(
                        character -> {
                            if (character == null) {
                                log.debug("No character turn started for roleplay conversation {}: conversation busy",
                                        conversationId);
                            } else {
                                log.debug("Triggered roleplay character response for conversation: {}, character: {}",
                                        conversationId, character.getName());
                            }
                        },
                        err -> log.error("Failed to trigger roleplay character response for conversation {}: {}",
                                conversationId, err.getMessage())));
    }

    /**
     * Edit a message.
     *
     * For GROUP CHATS: If editing a user message, also deletes all subsequent
     * character messages and triggers them to regenerate based on the new content.
     *
     * CRITICAL FIX (Issue #2): All edit operations including cascade delete are now atomic
     * using a database transaction with FOR UPDATE lock. This prevents:
     * - Concurrent reads seeing partial state during cascade delete
     * - messageCount becoming out of sync with actual message count
     * - Race conditions between SELECT and DELETE operations
     */
    @Mutation
    public CompletableFuture<Message> editMessage(@Name("id") String id, @Name("content") String newContent) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        // Schema defines: editMessage(id: ID!, content: String!)
        UUID messageId = UUID.fromString(id);

        CompletableFuture<Message> future = new CompletableFuture<>();

        // Check rate limit first (editing in group chats can trigger LLM regeneration)
        checkMessageRateLimit(auth.getUserId().toString())
            .compose(remaining -> {
                // First, get the conversation ID to cancel any active generation
                // This prevents race conditions where edit happens during streaming
                String getConvQuery = "SELECT conversationId FROM message WHERE messageUUID = ?";
                return mysqlPool.preparedQuery(getConvQuery)
                    .execute(Tuple.of(messageId.toString()))
                    .compose(convRows -> {
                        if (convRows.iterator().hasNext()) {
                            String convId = convRows.iterator().next().getString("conversationId");
                            // Cancel any active LLM generation for this conversation
                            JsonObject stopRequest = new JsonObject()
                                .put("conversationId", convId)
                                .put("userId", auth.getUserId().toString())
                                .put("action", "stop")
                                .put("reason", "edit_in_progress");
                            vertx.eventBus().send("llm.stop", stopRequest);
                            log.debug("Cancelled any active generation for conversation {} before edit", convId);
                        }
                        return Future.succeededFuture();
                    });
            })
            .compose(v -> {
                // Issue #2 Fix: Use transaction with FOR UPDATE lock for all edit operations
                return mysqlPool.withTransaction(client -> {
                    // Step 1: Get existing message with FOR UPDATE lock to prevent concurrent modifications
                    String lockQuery = """
                        SELECT m.*, c.ownerId, c.isGroupChat, c.groupState FROM message m
                        JOIN conversation c ON m.conversationId = c.conversationGUID
                        WHERE m.messageUUID = ? AND (c.ownerId = ? OR m.ownerId = ?)
                        FOR UPDATE
                        """;

                    return client.preparedQuery(lockQuery)
                        .execute(Tuple.of(messageId.toString(), auth.getUserId().toString(), auth.getUserId().toString()))
                        .compose(rows -> {
                            if (rows.size() == 0) {
                                return Future.failedFuture(new RuntimeException("Message not found or unauthorized"));
                            }

                            Row row = rows.iterator().next();
                            LocalDateTime created = row.getLocalDateTime("createdAt");
                            Integer editCount = row.getInteger("editCount");
                            String role = row.getString("role");
                            Boolean isGroupChat = row.getBoolean("isGroupChat");
                            UUID conversationId = UUID.fromString(row.getString("conversationId"));

                            // Check edit constraints
                            if (editCount != null && editCount >= Message.MAX_EDIT_COUNT) {
                                return Future.failedFuture(new RuntimeException("Maximum edit count reached"));
                            }

                            // Time-window gate. Regular chats keep the short edit
                            // window. Group chats allow historical editing, but a
                            // message is locked once a DIFFERENT player has sent a
                            // message after it: editing would retroactively change
                            // the context their reply (and the replies to it) was
                            // generated from. A single-player group chat has no
                            // other player, so its whole history stays editable.
                            if (!Boolean.TRUE.equals(isGroupChat)) {
                                LocalDateTime editDeadline = created.plusMinutes(Message.MAX_EDIT_TIME_MINUTES);
                                if (LocalDateTime.now(ZoneOffset.UTC).isAfter(editDeadline)) {
                                    return Future.failedFuture(new RuntimeException("Edit time window expired"));
                                }
                            }

                            Future<Row> afterLock = Boolean.TRUE.equals(isGroupChat)
                                ? client.preparedQuery("""
                                    SELECT COUNT(*) as cnt FROM message
                                    WHERE conversationId = ?
                                      AND role = 'user'
                                      AND ownerId IS NOT NULL
                                      AND ownerId <> ?
                                      AND (createdAt > ? OR (createdAt = ? AND messageUUID <> ?))
                                      AND isDeleted = FALSE
                                    """)
                                    .execute(Tuple.of(conversationId.toString(),
                                        auth.getUserId().toString(), created, created, messageId.toString()))
                                    .compose(foreignRows -> {
                                        int foreignCount = foreignRows.iterator().next().getInteger("cnt");
                                        if (foreignCount > 0) {
                                            return Future.failedFuture(new RuntimeException(
                                                "This message is locked: another player has already replied after it. "
                                                    + "Only your messages sent after their last reply can be edited."));
                                        }
                                        return Future.succeededFuture(row);
                                    })
                                : Future.succeededFuture(row);

                            return afterLock.compose(lockedRow -> {
                                String previousContent = lockedRow.getString("content");

                            // Step 2: Store edit history within transaction
                            UUID editId = UUID.randomUUID();
                            String editHistoryQuery = """
                                INSERT INTO message_edit (editId, messageId, previousContent, newContent, editedAt, editedBy)
                                VALUES (?, ?, ?, ?, ?, ?)
                                """;

                            return client.preparedQuery(editHistoryQuery)
                                .execute(Tuple.of(
                                    editId.toString(),
                                    messageId.toString(),
                                    previousContent,
                                    newContent,
                                    LocalDateTime.now(ZoneOffset.UTC),
                                    auth.getUserId().toString()
                                ))
                                .compose(editHistoryResult -> {
                                    // Step 3: Update message within transaction
                                    String updateQuery = """
                                        UPDATE message SET content = ?, editCount = IFNULL(editCount, 0) + 1, isEdited = TRUE
                                        WHERE messageUUID = ?
                                        """;
                                    return client.preparedQuery(updateQuery)
                                        .execute(Tuple.of(newContent, messageId.toString()));
                                })
                                .compose(updateResult -> {
                                    // Step 4: For GROUP CHATS - cascade delete within same transaction
                                    if (Boolean.TRUE.equals(isGroupChat)) {
                                        log.debug("Editing message in group chat: deleting ALL subsequent messages (user and assistant)");

                                        // MySQL DATETIME only has second precision, so messages within same second
                                        // would have identical timestamps. Use > OR (= AND != UUID) to include
                                        // those at the same second but exclude the current message being edited.
                                        String countQuery = """
                                            SELECT COUNT(*) as cnt FROM message
                                            WHERE conversationId = ?
                                            AND (createdAt > ? OR (createdAt = ? AND messageUUID != ?))
                                            AND isDeleted = FALSE
                                            """;

                                        return client.preparedQuery(countQuery)
                                            .execute(Tuple.of(conversationId.toString(), created, created, messageId.toString()))
                                            .compose(countRows -> {
                                                int deleteCount = countRows.iterator().next().getInteger("cnt");
                                                log.debug("Found {} messages to delete after edited message (group chat)", deleteCount);

                                                if (deleteCount == 0) {
                                                    return Future.succeededFuture(new JsonObject()
                                                        .put("conversationId", conversationId.toString())
                                                        .put("isGroupChat", true)
                                                        .put("shouldTriggerResponses", "user".equalsIgnoreCase(role)));
                                                }

                                                // Delete messages within transaction (using same logic)
                                                String deleteQuery = """
                                                    DELETE FROM message
                                                    WHERE conversationId = ?
                                                    AND (createdAt > ? OR (createdAt = ? AND messageUUID != ?))
                                                    AND isDeleted = FALSE
                                                    """;
                                                return client.preparedQuery(deleteQuery)
                                                    .execute(Tuple.of(conversationId.toString(), created, created, messageId.toString()))
                                                    .compose(del -> {
                                                        // Update messageCount atomically within same transaction
                                                        String updateCountQuery = """
                                                            UPDATE conversation
                                                            SET messageCount = GREATEST(IFNULL(messageCount, 0) - ?, 0),
                                                                groupState = 'WAITING_FOR_USER'
                                                            WHERE conversationGUID = ?
                                                            """;
                                                        return client.preparedQuery(updateCountQuery)
                                                            .execute(Tuple.of(deleteCount, conversationId.toString()));
                                                    })
                                                    .map(del -> new JsonObject()
                                                        .put("conversationId", conversationId.toString())
                                                        .put("isGroupChat", true)
                                                        .put("shouldTriggerResponses", "user".equalsIgnoreCase(role))
                                                        .put("deletedCount", deleteCount));
                                            });
                                    } else {
                                        // REGULAR CHAT: Delete ALL subsequent messages (matching group chat behavior)
                                        log.debug("Editing message in regular chat: deleting ALL subsequent messages (user and assistant)");

                                        // FIX: Use >= and exclude current message by ID
                                        // MySQL DATETIME only has second precision, so messages within same second
                                        // would have identical timestamps. We need to include those but exclude
                                        // the current message being edited.
                                        String countQuery = """
                                            SELECT COUNT(*) as cnt FROM message
                                            WHERE conversationId = ?
                                            AND (createdAt > ? OR (createdAt = ? AND messageUUID != ?))
                                            AND isDeleted = FALSE
                                            """;

                                        return client.preparedQuery(countQuery)
                                            .execute(Tuple.of(conversationId.toString(), created, created, messageId.toString()))
                                            .compose(countRows -> {
                                                int deleteCount = countRows.iterator().next().getInteger("cnt");
                                                log.debug("Found {} messages to delete after edited message", deleteCount);

                                                if (deleteCount == 0) {
                                                    // No subsequent messages - nothing to delete
                                                    // But still trigger regeneration if editing a user message
                                                    return Future.succeededFuture(new JsonObject()
                                                        .put("conversationId", conversationId.toString())
                                                        .put("isGroupChat", false)
                                                        .put("shouldTriggerResponses", "user".equalsIgnoreCase(role)));
                                                }

                                                // Delete ALL messages after edited message (using same logic)
                                                String deleteQuery = """
                                                    DELETE FROM message
                                                    WHERE conversationId = ?
                                                    AND (createdAt > ? OR (createdAt = ? AND messageUUID != ?))
                                                    AND isDeleted = FALSE
                                                    """;
                                                return client.preparedQuery(deleteQuery)
                                                    .execute(Tuple.of(conversationId.toString(), created, created, messageId.toString()))
                                                    .compose(del -> {
                                                        // Update messageCount atomically within same transaction
                                                        String updateCountQuery = """
                                                            UPDATE conversation
                                                            SET messageCount = GREATEST(IFNULL(messageCount, 0) - ?, 0)
                                                            WHERE conversationGUID = ?
                                                            """;
                                                        return client.preparedQuery(updateCountQuery)
                                                            .execute(Tuple.of(deleteCount, conversationId.toString()));
                                                    })
                                                    .map(del -> new JsonObject()
                                                        .put("conversationId", conversationId.toString())
                                                        .put("isGroupChat", false)
                                                        .put("shouldTriggerResponses", "user".equalsIgnoreCase(role))
                                                        .put("deletedCount", deleteCount));
                                            });
                                    }
                                });
                            });
                        });
                });
            })
            .compose(context -> {
                // Get updated message (outside transaction, after commit)
                String getQuery = "SELECT * FROM message WHERE messageUUID = ?";
                return mysqlPool.preparedQuery(getQuery)
                    .execute(Tuple.of(messageId.toString()))
                    .map(rows -> {
                        if (rows.size() == 0) {
                            throw new RuntimeException("Message not found after update");
                        }
                        Message message = mapRowToMessage(rows.iterator().next());
                        String conversationId = message.getConversationId().toString();

                        // Invalidate cache for this conversation
                        vertx.eventBus().send("redis.cache.invalidate",
                            new JsonObject()
                                .put("pattern", "message:*")
                                .put("conversationId", conversationId));

                        // Also invalidate conversation message list cache
                        vertx.eventBus().send("redis.cache.invalidate",
                            new JsonObject()
                                .put("pattern", "messages:" + conversationId + ":*"));

                        // Publish message edit event for real-time updates
                        JsonObject editEvent = new JsonObject()
                            .put("type", "edited")
                            .put("timestamp", java.time.LocalDateTime.now(ZoneOffset.UTC).toString())
                            .put("data", new JsonObject()
                                .put("messageId", message.getId().toString())
                                .put("conversationId", conversationId)
                                .put("content", message.getContent())
                                .put("editCount", message.getEditCount()));
                        vertx.eventBus().publish("message.events", editEvent);

                        // Also publish to the per-conversation channel that the
                        // messageEdited GraphQL subscription consumes. Without
                        // this, the flat "message.events" channel has no
                        // corresponding resolver and edit events never surface
                        // to subscribers.
                        vertx.eventBus().publish(
                            "conversation:" + conversationId + ":message:edited",
                            new JsonObject()
                                .put("id", message.getId().toString())
                                .put("content", message.getContent())
                                .put("editCount", message.getEditCount() != null ? message.getEditCount() : 0));

                        // Trigger responses after transaction commits
                        if (context.getBoolean("shouldTriggerResponses", false)) {
                            boolean isGroupChat = context.getBoolean("isGroupChat", false);

                            if (isGroupChat) {
                                // Group chat: trigger character cascade
                                triggerGroupChatResponsesForEdit(conversationId, auth.getUserId().toString());
                            } else {
                                // Regular chat: trigger LLM regeneration
                                triggerRegularChatRegeneration(conversationId, auth.getUserId().toString(), message.getContent());
                            }
                        }

                        return message;
                    });
            })
            .onSuccess(future::complete)
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Helper method to trigger group chat character responses after a user message edit.
     */
    private void triggerGroupChatResponsesForEdit(String conversationId, String userId) {
        // Delay so the edit commits before the turn reads history.
        vertx.setTimer(100, id -> groupChatService.startCharacterTurn(conversationId, userId)
                .subscribe().with(
                        character -> {
                            if (character == null) {
                                log.debug("Skipping character response after edit - conversation busy");
                            } else {
                                log.debug("Triggered character response after edit: {} in conversation: {}",
                                        character.getName(), conversationId);
                            }
                        },
                        err -> log.error("Failed to trigger character response after edit: {}", err.getMessage())));
    }

    /**
     * Helper method to trigger LLM regeneration after a user message edit in regular chats.
     * This deletes the assistant's reply and triggers a new LLM response with the edited context.
     * Note: userName is resolved centrally by LLMVerticle using userId.
     */
    private void triggerRegularChatRegeneration(String conversationId, String userId, String editedContent) {
        // Small delay to ensure the edit is committed
        vertx.setTimer(100, id -> {
            UUID assistantMessageId = UUID.randomUUID();

            JsonObject request = new JsonObject()
                .put("conversationId", conversationId)
                .put("userId", userId)
                .put("content", editedContent)
                .put("assistantMessageId", assistantMessageId.toString())
                .put("regenerate", true);

            vertx.eventBus().send("llm.generate", request);
            log.debug("Triggered LLM regeneration after edit in conversation: {}", conversationId);
        });
    }

    /**
     * Validates a starting relationship value. Both directions default to neutral.
     */
    private static int initialAffinity(Object value, String direction) {
        if (value == null) return 0;
        int points = ((Number) value).intValue();
        if (points < -100 || points > 100)
            throw new IllegalArgumentException(
                "Starting relationship points must be between -100 and 100.");
        return points;
    }

    /** Age is optional, but a supplied age must be a plausible year count. */
    private static Integer optionalAge(Object value) {
        if (value == null) return null;
        int years = ((Number) value).intValue();
        if (years < 0 || years > 1_000_000)
            throw new IllegalArgumentException("Age must be between 0 and 1,000,000 years.");
        return years;
    }

    /** Base attributes use the same 1-20 scale the character card displays. */
    private static Integer optionalAttribute(Object value, String label) {
        if (value == null) return null;
        int score = ((Number) value).intValue();
        if (score < 1 || score > 20)
            throw new IllegalArgumentException(label + " must be between 1 and 20.");
        return score;
    }

    /**
     * Create a character.
     */
    @Mutation
    public CompletableFuture<Character> createCharacter(@Name("input") CreateCharacterInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);

        CompletableFuture<Character> future = new CompletableFuture<>();

        UUID characterId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Extract LLM parameters with defaults
        Double temperature = input.get("temperature") != null ? ((Number) input.get("temperature")).doubleValue() : 0.7;
        Integer maxTokens = input.get("maxTokens") != null ? ((Number) input.get("maxTokens")).intValue() : 2000;
        Double topP = input.get("topP") != null ? ((Number) input.get("topP")).doubleValue() : 0.9;
        Integer topK = input.get("topK") != null ? ((Number) input.get("topK")).intValue() : 40;
        Double repeatPenalty = input.get("repeatPenalty") != null ? ((Number) input.get("repeatPenalty")).doubleValue() : 1.0;
        Boolean isPublic = input.get("isPublic") != null ? (Boolean) input.get("isPublic") : false;

        String category = input.get("category") != null ? (String) input.get("category") : "general";

        // Optional starting alignment. Validated before the row is written.
        Integer lawfulness = input.get("lawfulness") != null ? ((Number) input.get("lawfulness")).intValue() : null;
        Integer goodness = input.get("goodness") != null ? ((Number) input.get("goodness")).intValue() : null;
        if (lawfulness != null) com.eonmux.chatllm.utils.AlignmentRules.validatePoints(lawfulness);
        if (goodness != null) com.eonmux.chatllm.utils.AlignmentRules.validatePoints(goodness);

        Object tagsVal = input.get("tags");
        String tagsValue = tagsVal == null ? null
            : (tagsVal instanceof List<?> ? new io.vertx.core.json.JsonArray((List<?>) tagsVal).encode()
            : tagsVal.toString());

        Integer ageYears = optionalAge(input.get("ageYears"));
        Integer strength = optionalAttribute(input.get("strength"), "Strength");
        Integer dexterity = optionalAttribute(input.get("dexterity"), "Dexterity");
        Integer constitution = optionalAttribute(input.get("constitution"), "Constitution");
        Integer intelligence = optionalAttribute(input.get("intelligence"), "Intelligence");
        Integer wisdom = optionalAttribute(input.get("wisdom"), "Wisdom");
        Integer charisma = optionalAttribute(input.get("charisma"), "Charisma");

        String insertQuery = """
            INSERT INTO `character` (characterUUID, name, description, systemPrompt, appearance,
                                      personality, gender, pronouns, ageYears, height, build, hair, eyes,
                                      strength, dexterity, constitution, intelligence, wisdom, charisma,
                                      likes, dislikes, backstory, goals,
                                      greeting, examples, avatar, isDefault, isPublic,
                                      temperature, maxTokens, topP, topK, repeatPenalty,
                                      category, tags, ownerId, createdAt, updatedAt,
                                      initialAffinityToUser, initialAffinityFromUser)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        // Use greeting if provided, otherwise fall back to initialPrompt
        String greetingValue = input.get("greeting") != null ? (String) input.get("greeting") : (String) input.get("initialPrompt");

        mysqlPool.preparedQuery(insertQuery)
            .execute(io.vertx.sqlclient.Tuple.tuple()
                .addValue(characterId.toString())
                .addValue(input.get("name"))
                .addValue(input.get("description"))
                .addValue(input.get("systemPrompt"))
                .addValue(input.get("appearance"))
                .addValue(input.get("personality"))
                .addValue(input.get("gender"))
                .addValue(input.get("pronouns"))
                .addValue(ageYears)
                .addValue(input.get("height"))
                .addValue(input.get("build"))
                .addValue(input.get("hair"))
                .addValue(input.get("eyes"))
                .addValue(strength)
                .addValue(dexterity)
                .addValue(constitution)
                .addValue(intelligence)
                .addValue(wisdom)
                .addValue(charisma)
                .addValue(input.get("likes"))
                .addValue(input.get("dislikes"))
                .addValue(input.get("backstory"))
                .addValue(input.get("goals"))
                .addValue(greetingValue)
                .addValue(input.get("examples"))
                .addValue(input.get("avatar"))
                .addValue(false)
                .addValue(isPublic)
                .addValue(temperature)
                .addValue(maxTokens)
                .addValue(topP)
                .addValue(topK)
                .addValue(repeatPenalty)
                .addValue(category)
                .addValue(tagsValue)
                .addValue(auth.getUserId().toString())
                .addValue(now)
                .addValue(now)
                .addValue(initialAffinity(input.get("initialAffinityToUser"), "character"))
                .addValue(initialAffinity(input.get("initialAffinityFromUser"), "user")))
            .onSuccess(v -> {
                Character character = Character.builder()
                    .id(characterId)
                    .name((String) input.get("name"))
                    .description((String) input.get("description"))
                    .systemPrompt((String) input.get("systemPrompt"))
                    .appearance((String) input.get("appearance"))
                    .personality((String) input.get("personality"))
                    .gender((String) input.get("gender"))
                    .pronouns((String) input.get("pronouns"))
                    .ageYears(ageYears)
                    .height((String) input.get("height"))
                    .build((String) input.get("build"))
                    .hair((String) input.get("hair"))
                    .eyes((String) input.get("eyes"))
                    .strength(strength)
                    .dexterity(dexterity)
                    .constitution(constitution)
                    .intelligence(intelligence)
                    .wisdom(wisdom)
                    .charisma(charisma)
                    .likes((String) input.get("likes"))
                    .dislikes((String) input.get("dislikes"))
                    .backstory((String) input.get("backstory"))
                    .goals((String) input.get("goals"))
                    .examples((String) input.get("examples"))
                    .avatar((String) input.get("avatar"))
                    .greeting(input.get("greeting") != null ? (String) input.get("greeting") : (String) input.get("initialPrompt"))
                    .isDefault(false)
                    .isPublic(isPublic)
                    .isFavorite(false)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .topP(topP)
                    .topK(topK)
                    .repeatPenalty(repeatPenalty)
                    .category(category)
                    .ownerId(auth.getUserId())
                    .initialAffinityToUser(initialAffinity(input.get("initialAffinityToUser"), "character"))
                    .initialAffinityFromUser(initialAffinity(input.get("initialAffinityFromUser"), "user"))
                    .createdAt(now.toString())
                    .updatedAt(now.toString())
                    .build();

                Runnable saved = () -> {
                    vertx.eventBus().publish(
                        "user:" + auth.getUserId().toString() + ":characters:updated",
                        new JsonObject()
                            .put("id", characterId.toString())
                            .put("name", input.get("name"))
                            .put("description", input.get("description") != null ? input.get("description") : "")
                            .put("systemPrompt", input.get("systemPrompt") != null ? input.get("systemPrompt") : ""));
                    future.complete(character);
                };
                if (lawfulness == null && goodness == null) {
                    saved.run();
                    return;
                }
                mysqlPool.preparedQuery("""
                        INSERT INTO character_alignment
                          (alignmentId, ownerId, characterId, actor, lawfulness, goodness)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE lawfulness = VALUES(lawfulness), goodness = VALUES(goodness)
                        """)
                    .execute(Tuple.of(UUID.randomUUID().toString(), auth.getUserId().toString(),
                            characterId.toString(), characterId.toString(),
                            lawfulness == null ? 0 : lawfulness, goodness == null ? 0 : goodness))
                    .onSuccess(alignment -> saved.run())
                    .onFailure(future::completeExceptionally);
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Generate character using AI.
     * Schema: generateCharacter(prompt: String!): Character!
     *
     * NOTE: This method does NOT save the character to the database.
     * It only returns the AI-generated character data for the frontend to display.
     * The user must explicitly call createCharacter to persist the character.
     */
    @Mutation
    public CompletableFuture<CharacterDraft> generateCharacter(@Name("prompt") String prompt) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Float temperature = 0.7f;

        CompletableFuture<CharacterDraft> future = new CompletableFuture<>();

        // Send request to LLM service
        JsonObject request = new JsonObject()
            .put("userId", auth.getUserId().toString())
            .put("prompt", prompt)
            .put("temperature", temperature != null ? temperature : 0.7f)
            .put("type", "character_generation");

        // Character generation can take longer than default 30s timeout, use 5 minutes
        DeliveryOptions deliveryOptions = new DeliveryOptions().setSendTimeout(300000);

        vertx.eventBus().<JsonObject>request("llm.generate.character", request, deliveryOptions)
            .onSuccess(reply -> {
                JsonObject characterData = reply.body();

                // Extract greeting with fallback to initialPrompt for backwards compatibility
                String greeting = characterData.getString("greeting");
                if (greeting == null || greeting.isEmpty()) {
                    greeting = characterData.getString("initialPrompt");
                }

                // Build Character object with generated data (NOT persisted to database)
                // The frontend will display this data and the user can modify it before saving
                // Note: We provide placeholder id/timestamps to satisfy GraphQL non-null requirements
                // These will be replaced with real values when the user saves the character
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                Character character = Character.builder()
                    .id(UUID.randomUUID()) // Temporary ID - will be replaced on actual save
                    .name(characterData.getString("name", "Generated Character"))
                    .description(characterData.getString("description", ""))
                    .systemPrompt(characterData.getString("systemPrompt", ""))
                    .personality(characterData.getString("personality", ""))
                    .appearance(characterData.getString("appearance", ""))
                    .gender(characterData.getString("gender", ""))
                    .pronouns(characterData.getString("pronouns", ""))
                    .ageYears(characterData.getInteger("ageYears"))
                    .height(characterData.getString("height", ""))
                    .build(characterData.getString("build", ""))
                    .hair(characterData.getString("hair", ""))
                    .eyes(characterData.getString("eyes", ""))
                    .strength(characterData.getInteger("strength", 10))
                    .dexterity(characterData.getInteger("dexterity", 10))
                    .constitution(characterData.getInteger("constitution", 10))
                    .intelligence(characterData.getInteger("intelligence", 10))
                    .wisdom(characterData.getInteger("wisdom", 10))
                    .charisma(characterData.getInteger("charisma", 10))
                    .backstory(characterData.getString("backstory", ""))
                    .goals(characterData.getString("goals", ""))
                    .likes(characterData.getString("likes", ""))
                    .dislikes(characterData.getString("dislikes", ""))
                    .greeting(greeting)
                    .category("generated")
                    .temperature(0.7)
                    .maxTokens(-1)  // -1 means unlimited
                    .topP(0.9)
                    .topK(40)
                    .repeatPenalty(1.0)
                    .isDefault(false)
                    .isPublic(false)
                    .isFavorite(false)
                    .createdAt(now.toString()) // Placeholder timestamp
                    .updatedAt(now.toString()) // Placeholder timestamp
                    .build();

                String alignment = com.eonmux.chatllm.utils.AlignmentRules.named(characterData.getString("alignment"));
                if (alignment == null) alignment = "TRUE_NEUTRAL";
                int[] points = com.eonmux.chatllm.utils.AlignmentRules.pointsFor(alignment);
                CharacterDraft draft = new CharacterDraft();
                draft.setCharacter(character);
                draft.setAlignment(alignment);
                draft.setLawfulness(points[0]);
                draft.setGoodness(points[1]);
                draft.setRelationships(java.util.List.of());
                future.complete(draft);
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Update user settings.
     * Returns Settings object matching the GraphQL schema with nested llm, ui, notifications, privacy, keyboard.
     */
    @SuppressWarnings("unchecked")
    /**
     * Update user settings.
     * Now uses SettingsService for all settings operations (DRY principle).
     */
    @Mutation
    public CompletableFuture<Settings> updateSettings(@Name("input") UpdateSettingsInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String userId = auth.getUserId().toString();
        CompletableFuture<Settings> future = new CompletableFuture<>();

        // Create SettingsService instance
        // Using injected settingsService

        // Convert input Map to JsonObject and update settings
        JsonObject updates = new JsonObject(input);

        settingsService.updateSettings(userId, updates).subscribe().with(
            settings -> {
                future.complete(settings);
            },
            error -> {
                log.error("Failed to update settings for user {}: {}", userId, error.getMessage());
                future.completeExceptionally(error);
            });

        return future;
    }


    /**
     * Update user profile.
     */
    @Mutation
    public CompletableFuture<User> updateUser(@Name("input") UpdateUserInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        CompletableFuture<User> future = new CompletableFuture<>();

        // Build update query
        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (input.containsKey("displayName")) {
            setClauses.add("displayName = ?");
            params.add(DisplayNameFormatter.normalize((String) input.get("displayName")));
        }
        if (input.containsKey("email")) {
            setClauses.add("email = ?");
            params.add(input.get("email"));
        }

        if (setClauses.isEmpty()) {
            // Nothing to update
            CompletableFuture<User> noUpdateFuture = new CompletableFuture<>();
            getUserById(auth.getUserId())
                .onSuccess(noUpdateFuture::complete)
                .onFailure(noUpdateFuture::completeExceptionally);
            return noUpdateFuture;
        }

        params.add(auth.getUserId().toString());
        String updateQuery = "UPDATE user SET " + String.join(", ", setClauses) + " WHERE userUUID = ?";

        mysqlPool.preparedQuery(updateQuery)
            .execute(Tuple.wrap(params))
            .compose(v -> getUserById(auth.getUserId()))
            .onSuccess(user -> future.complete(user))
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Delete user account.
     */
    @Mutation
    public CompletableFuture<Boolean> deleteUser() {
        AuthContext auth = graphQLAuthContext.requireAuth();

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Delete user (cascades to related data per schema)
        String deleteQuery = "DELETE FROM user WHERE userUUID = ?";
        mysqlPool.preparedQuery(deleteQuery)
            .execute(Tuple.of(auth.getUserId().toString()))
            .onSuccess(v -> future.complete(true))
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Delete conversation.
     * Schema: deleteConversation(id: ID!): Boolean!
     */
    @Mutation
    public CompletableFuture<Boolean> deleteConversation(@Name("id") String id) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        // Schema uses 'id' parameter
        UUID conversationId = UUID.fromString(id);
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Verify ownership and delete
        String deleteQuery = "DELETE FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
        String userIdStr = auth.getUserId().toString();
        mysqlPool.preparedQuery(deleteQuery)
            .execute(Tuple.of(conversationId.toString(), userIdStr))
            .onSuccess(result -> {
                boolean deleted = result.rowCount() > 0;
                if (deleted) {
                    vertx.eventBus().publish("conversation.deleted." + conversationId, new JsonObject());
                    // Notify the conversationDeleted subscription consumer
                    // (SubscriptionResolver registers on "user:<userId>:conversations:deleted"
                    // and reads the body as a raw UUID string).
                    vertx.eventBus().publish(
                        "user:" + userIdStr + ":conversations:deleted",
                        conversationId.toString());
                }
                future.complete(deleted);
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Update conversation.
     * Schema: updateConversation(id: ID!, input: UpdateConversationInput!): Conversation!
     */
    @Mutation
    public CompletableFuture<Conversation> updateConversation(@Name("id") String id, @Name("input") UpdateConversationInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        UUID conversationId = UUID.fromString(id);
        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);

        CompletableFuture<Conversation> future = new CompletableFuture<>();

        // Build update query dynamically
        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (input.containsKey("title")) {
            setClauses.add("title = ?");
            params.add(input.get("title"));
        }
        if (input.containsKey("description")) {
            setClauses.add("description = ?");
            params.add(input.get("description"));
        }
        if (input.containsKey("category")) {
            setClauses.add("category = ?");
            params.add(input.get("category"));
        }
        if (input.containsKey("isPinned")) {
            setClauses.add("isPinned = ?");
            params.add(input.get("isPinned"));
        }
        if (input.containsKey("isArchived")) {
            setClauses.add("isArchived = ?");
            params.add(input.get("isArchived"));
        }
        if (input.containsKey("tags")) {
            setClauses.add("tags = ?");
            Object tagsVal = input.get("tags");
            String tagsJson = tagsVal == null ? null
                : (tagsVal instanceof List<?> ? new io.vertx.core.json.JsonArray((List<?>) tagsVal).encode()
                : tagsVal.toString());
            params.add(tagsJson);
        }

        if (setClauses.isEmpty()) {
            // No updates, fetch and return current
            String getQuery = "SELECT * FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
            mysqlPool.preparedQuery(getQuery)
                .execute(Tuple.of(conversationId.toString(), auth.getUserId().toString()))
                .onSuccess(rows -> {
                    if (rows.size() > 0) {
                        future.complete(mapRowToConversation(rows.iterator().next()));
                    } else {
                        future.completeExceptionally(new RuntimeException("Conversation not found"));
                    }
                })
                .onFailure(future::completeExceptionally);
            return future;
        }

        setClauses.add("updatedAt = ?");
        params.add(LocalDateTime.now(ZoneOffset.UTC));
        params.add(conversationId.toString());
        params.add(auth.getUserId().toString());

        String updateQuery = "UPDATE conversation SET " + String.join(", ", setClauses) +
                           " WHERE conversationGUID = ? AND ownerId = ?";

        mysqlPool.preparedQuery(updateQuery)
            .execute(Tuple.wrap(params))
            .compose(result -> {
                if (result.rowCount() == 0) {
                    return Future.failedFuture(new RuntimeException("Conversation not found or unauthorized"));
                }
                String getQuery = "SELECT * FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
                return mysqlPool.preparedQuery(getQuery)
                    .execute(Tuple.of(conversationId.toString(), auth.getUserId().toString()));
            })
            .onSuccess(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    Conversation conversation = mapRowToConversation(row);

                    // Publish conversation updated event for subscription
                    JsonObject updateEvent = new JsonObject()
                        .put("id", conversationId.toString())
                        .put("ownerId", auth.getUserId().toString())
                        .put("characterId", row.getString("characterId"))
                        .put("title", row.getString("title"))
                        .put("messageCount", row.getInteger("messageCount"))
                        .put("updatedAt", row.getLocalDateTime("updatedAt") != null ?
                            Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")) : null)
                        .put("eventType", "updated");
                    vertx.eventBus().publish("conversation:" + conversationId + ":updated", updateEvent);
                    log.debug("Published conversation updated event for: {}", conversationId);

                    future.complete(conversation);
                } else {
                    future.completeExceptionally(new RuntimeException("Conversation not found"));
                }
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Stop LLM generation.
     * Schema: stopGeneration(conversationId: ID!): Boolean!
     *
     * Note: This MUST always return a non-null Boolean. GraphQL schema declares Boolean!
     */
    @Mutation
    public CompletableFuture<Boolean> stopGeneration(@Name("conversationId") String conversationIdStr) {
        // requireAuth() throws if unauthenticated — let the exception propagate so
        // the client sees an auth error instead of a silent "success". Returning
        // TRUE on auth failure would allow an unauthenticated caller to probe the
        // llm.stop channel with any conversationId.
        AuthContext auth = graphQLAuthContext.requireAuth();
        if (conversationIdStr == null || conversationIdStr.isEmpty()) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        try {

            // Send stop request to LLM via EventBus
            JsonObject request = new JsonObject()
                .put("conversationId", conversationIdStr)
                .put("userId", auth.getUserId().toString())
                .put("action", "stop");

            CompletableFuture<Boolean> future = new CompletableFuture<>();

            try {
                vertx.eventBus().<JsonObject>request("llm.stop", request)
                    .onSuccess(reply -> {
                        try {
                            JsonObject response = reply.body();
                            Boolean success = (response != null) ? response.getBoolean("success", true) : true;
                            future.complete(success != null ? success : Boolean.TRUE);
                        } catch (Exception e) {
                            log.warn("Error parsing stop response: {}", e.getMessage());
                            future.complete(Boolean.TRUE);
                        }
                    })
                    .onFailure(error -> {
                        log.warn("Failed to stop generation: {}", error.getMessage());
                        future.complete(Boolean.TRUE);
                    });
            } catch (Exception e) {
                log.error("Exception sending stop request: {}", e.getMessage());
                future.complete(Boolean.TRUE);
            }

            return future;
        } catch (Exception e) {
            log.error("Exception in stopGeneration: {}", e.getMessage(), e);
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
    }

    /**
     * Delete message.
     * Schema: deleteMessage(id: ID!): Boolean!
     */
    @Mutation
    public CompletableFuture<Boolean> deleteMessage(@Name("id") String id) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        // Schema uses 'id' parameter
        UUID messageId = UUID.fromString(id);
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Fetch the conversationId first so we can publish a per-conversation
        // messageDeleted event after the soft-delete succeeds. Subscriptions
        // ({@code conversation:<id>:message:deleted}) otherwise never fire.
        String userId = auth.getUserId().toString();
        String lookupQuery = """
            SELECT m.conversationId FROM message m
            JOIN conversation c ON m.conversationId = c.conversationGUID
            WHERE m.messageUUID = ? AND (c.ownerId = ? OR m.ownerId = ?)
            """;

        String updateQuery = """
            UPDATE message m
            JOIN conversation c ON m.conversationId = c.conversationGUID
            SET m.content = '[Deleted]', m.isDeleted = true,
                c.messageCount = GREATEST(COALESCE(c.messageCount, 1) - 1, 0)
            WHERE m.messageUUID = ? AND (c.ownerId = ? OR m.ownerId = ?)
            """;

        mysqlPool.preparedQuery(lookupQuery)
            .execute(Tuple.of(messageId.toString(), userId, userId))
            .compose(rows -> {
                String conversationId = rows.iterator().hasNext()
                    ? rows.iterator().next().getString("conversationId") : null;
                return mysqlPool.preparedQuery(updateQuery)
                    .execute(Tuple.of(messageId.toString(), userId, userId))
                    .map(result -> {
                        boolean deleted = result.rowCount() > 0;
                        if (deleted && conversationId != null) {
                            vertx.eventBus().publish(
                                "conversation:" + conversationId + ":message:deleted",
                                messageId.toString());
                        }
                        return deleted;
                    });
            })
            .onSuccess(future::complete)
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * React to a message (add emoji reaction).
     * Schema: reactToMessage(messageId: ID!, reaction: String!): Reaction!
     */
    @Mutation
    public CompletableFuture<Reaction> reactToMessage(@Name("messageId") String messageId, @Name("reaction") String reaction) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();
        String reactionId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        CompletableFuture<Reaction> future = new CompletableFuture<>();

        // First verify the message exists and user has access
        String verifyQuery = """
            SELECT m.messageUUID FROM message m
            JOIN conversation c ON m.conversationId = c.conversationGUID
            LEFT JOIN conversation_participant cp ON c.conversationGUID = cp.conversation_id AND cp.is_active = true
            WHERE m.messageUUID = ? AND (c.ownerId = ? OR cp.user_id = ?)
            """;

        mysqlPool.preparedQuery(verifyQuery)
            .execute(Tuple.of(messageId, userId, userId))
            .compose(rows -> {
                if (rows.size() == 0) {
                    return Future.failedFuture(new RuntimeException("Message not found or access denied"));
                }

                // Upsert reaction
                String upsertQuery = """
                    INSERT INTO message_reaction (id, message_id, user_id, reaction_type, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE reaction_type = VALUES(reaction_type), created_at = VALUES(created_at), id = VALUES(id)
                    """;

                return mysqlPool.preparedQuery(upsertQuery)
                    .execute(Tuple.of(reactionId, messageId, userId, reaction, now));
            })
            .onSuccess(result -> {
                // Real-time reaction updates are surfaced through the message
                // re-fetch on the client; the previous publish to "message:updates"
                // had no consumer.

                // Return the reaction object
                Reaction reactionObj = Reaction.builder()
                    .id(UUID.fromString(reactionId))
                    .messageId(UUID.fromString(messageId))
                    .userId(UUID.fromString(userId))
                    .reactionType(reaction)
                    .createdAt(now.toString())
                    .build();
                future.complete(reactionObj);
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Remove a reaction from a message.
     * Schema: removeReaction(messageId: ID!, reaction: String!): Boolean!
     */
    @Mutation
    public CompletableFuture<Boolean> removeReaction(@Name("messageId") String messageId, @Name("reaction") String reaction) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        String deleteQuery = """
            DELETE FROM message_reaction
            WHERE message_id = ? AND user_id = ? AND reaction_type = ?
            """;

        mysqlPool.preparedQuery(deleteQuery)
            .execute(Tuple.of(messageId, userId, reaction))
            .onSuccess(result -> {
                boolean deleted = result.rowCount() > 0;
                if (deleted) {
                    // Real-time reaction updates are surfaced through the message
                    // re-fetch on the client; the previous publish to "message:updates"
                    // had no consumer.
                }
                future.complete(deleted);
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Update character.
     * Schema: updateCharacter(id: ID!, input: UpdateCharacterInput!): Character!
     */
    @Mutation
    public CompletableFuture<Character> updateCharacter(@Name("id") String id, @Name("input") UpdateCharacterInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        UUID characterId = UUID.fromString(id);
        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);

        CompletableFuture<Character> future = new CompletableFuture<>();

        // Build update query
        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (input.containsKey("name")) {
            setClauses.add("name = ?");
            params.add(input.get("name"));
        }
        if (input.containsKey("description")) {
            setClauses.add("description = ?");
            params.add(input.get("description"));
        }
        if (input.containsKey("systemPrompt")) {
            setClauses.add("systemPrompt = ?");
            params.add(input.get("systemPrompt"));
        }
        if (input.containsKey("greeting")) {
            setClauses.add("greeting = ?");
            params.add(input.get("greeting"));
        }
        if (input.containsKey("personality")) {
            setClauses.add("personality = ?");
            params.add(input.get("personality"));
        }
        if (input.containsKey("appearance")) {
            setClauses.add("appearance = ?");
            params.add(input.get("appearance"));
        }
        if (input.containsKey("gender")) {
            setClauses.add("gender = ?");
            params.add(input.get("gender"));
        }
        if (input.containsKey("pronouns")) {
            setClauses.add("pronouns = ?");
            params.add(input.get("pronouns"));
        }
        if (input.containsKey("ageYears")) {
            setClauses.add("ageYears = ?");
            params.add(optionalAge(input.get("ageYears")));
        }
        if (input.containsKey("height")) {
            setClauses.add("height = ?");
            params.add(input.get("height"));
        }
        if (input.containsKey("build")) {
            setClauses.add("build = ?");
            params.add(input.get("build"));
        }
        if (input.containsKey("hair")) {
            setClauses.add("hair = ?");
            params.add(input.get("hair"));
        }
        if (input.containsKey("eyes")) {
            setClauses.add("eyes = ?");
            params.add(input.get("eyes"));
        }
        for (String attribute : List.of("strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma")) {
            if (input.containsKey(attribute)) {
                setClauses.add(attribute + " = ?");
                params.add(optionalAttribute(input.get(attribute),
                        attribute.substring(0, 1).toUpperCase() + attribute.substring(1)));
            }
        }
        if (input.containsKey("likes")) {
            setClauses.add("likes = ?");
            params.add(input.get("likes"));
        }
        if (input.containsKey("dislikes")) {
            setClauses.add("dislikes = ?");
            params.add(input.get("dislikes"));
        }
        if (input.containsKey("backstory")) {
            setClauses.add("backstory = ?");
            params.add(input.get("backstory"));
        }
        if (input.containsKey("goals")) {
            setClauses.add("goals = ?");
            params.add(input.get("goals"));
        }
        if (input.containsKey("examples")) {
            setClauses.add("examples = ?");
            params.add(input.get("examples"));
        }
        if (input.containsKey("avatar")) {
            setClauses.add("avatar = ?");
            params.add(input.get("avatar"));
        }
        if (input.containsKey("temperature")) {
            setClauses.add("temperature = ?");
            params.add(input.get("temperature"));
        }
        if (input.containsKey("maxTokens")) {
            setClauses.add("maxTokens = ?");
            params.add(input.get("maxTokens"));
        }
        if (input.containsKey("topP")) {
            setClauses.add("topP = ?");
            params.add(input.get("topP"));
        }
        if (input.containsKey("topK")) {
            setClauses.add("topK = ?");
            params.add(input.get("topK"));
        }
        if (input.containsKey("repeatPenalty")) {
            setClauses.add("repeatPenalty = ?");
            params.add(input.get("repeatPenalty"));
        }
        if (input.containsKey("isPublic")) {
            setClauses.add("isPublic = ?");
            params.add(input.get("isPublic"));
        }
        if (input.containsKey("tags")) {
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) input.get("tags");
            setClauses.add("tags = ?");
            params.add(tags != null && !tags.isEmpty() ? new io.vertx.core.json.JsonArray(tags).encode() : null);
        }
        if (input.containsKey("category")) {
            setClauses.add("category = ?");
            params.add(input.get("category"));
        }
        if (input.containsKey("photoAlbum")) {
            @SuppressWarnings("unchecked")
            List<String> photoAlbum = (List<String>) input.get("photoAlbum");
            setClauses.add("photoAlbum = ?");
            params.add(photoAlbum != null ? new io.vertx.core.json.JsonArray(photoAlbum).encode() : null);
        }
        if (input.containsKey("chatBackgroundImage")) {
            setClauses.add("chatBackgroundImage = ?");
            params.add(input.get("chatBackgroundImage"));
        }
        if (input.containsKey("initialAffinityToUser")) {
            setClauses.add("initialAffinityToUser = ?");
            params.add(initialAffinity(input.get("initialAffinityToUser"), "character"));
        }
        if (input.containsKey("initialAffinityFromUser")) {
            setClauses.add("initialAffinityFromUser = ?");
            params.add(initialAffinity(input.get("initialAffinityFromUser"), "user"));
        }

        if (setClauses.isEmpty()) {
            CompletableFuture<Character> noUpdateFuture = new CompletableFuture<>();
            getCharacterById(characterId)
                .onSuccess(noUpdateFuture::complete)
                .onFailure(noUpdateFuture::completeExceptionally);
            return noUpdateFuture;
        }

        setClauses.add("updatedAt = ?");
        params.add(LocalDateTime.now(ZoneOffset.UTC));
        params.add(characterId.toString());
        params.add(auth.getUserId().toString());

        String updateQuery = "UPDATE `character` SET " + String.join(", ", setClauses) +
                           " WHERE characterUUID = ? AND ownerId = ?";

        mysqlPool.preparedQuery(updateQuery)
            .execute(Tuple.wrap(params))
            .compose(result -> {
                if (result.rowCount() == 0) {
                    return Future.failedFuture(new RuntimeException("Character not found or unauthorized"));
                }
                return getCharacterById(characterId);
            })
            .onSuccess(character -> {
                vertx.eventBus().publish(
                    "user:" + auth.getUserId().toString() + ":characters:updated",
                    new JsonObject()
                        .put("id", character.getId().toString())
                        .put("name", character.getName())
                        .put("description", character.getDescription() != null ? character.getDescription() : "")
                        .put("systemPrompt", character.getSystemPrompt() != null ? character.getSystemPrompt() : ""));
                future.complete(character);
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Delete character.
     * Schema: deleteCharacter(id: ID!): Boolean!
     */
    @Mutation
    public CompletableFuture<Boolean> deleteCharacter(@Name("id") String id) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        UUID characterId = UUID.fromString(id);
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        String deleteQuery = "DELETE FROM `character` WHERE characterUUID = ? AND ownerId = ?";
        mysqlPool.preparedQuery(deleteQuery)
            .execute(Tuple.of(characterId.toString(), auth.getUserId().toString()))
            .onSuccess(result -> {
                boolean deleted = result.rowCount() > 0;
                if (deleted) {
                    vertx.eventBus().publish(
                        "user:" + auth.getUserId().toString() + ":characters:deleted",
                        characterId.toString());
                }
                future.complete(deleted);
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Shutdown server (admin only).
     */
    @Mutation
    public CompletableFuture<Boolean> shutdownServer() {
        AuthContext auth = graphQLAuthContext.requireAdmin();

        // Schedule shutdown after 5 seconds
        vertx.setTimer(5000, id -> {
            log.info("Shutting down server as requested by admin");
            vertx.close();
        });

        return CompletableFuture.completedFuture(true);
    }

    /**
     * Update user role (admin only).
     * Valid roles: USER, MODERATOR, ADMIN
     */
    @Mutation
    public CompletableFuture<AdminActionResult> updateUserRole(@Name("userId") String targetUserId, @Name("role") String newRole) {
        AuthContext auth = graphQLAuthContext.requireAdmin();

        // Validate role
        Set<String> validRoles = Set.of("USER", "MODERATOR", "ADMIN");
        if (!validRoles.contains(newRole.toUpperCase())) {
            return CompletableFuture.failedFuture(
                new RuntimeException("Invalid role. Valid roles are: USER, MODERATOR, ADMIN"));
        }

        // Normalize role to uppercase
        String normalizedRole = newRole.toUpperCase();

        CompletableFuture<AdminActionResult> future = new CompletableFuture<>();

        // Prevent admin from demoting themselves
        if (targetUserId.equals(auth.getUserId().toString())) {
            future.complete(AdminActionResult.builder().success(false).message("Cannot change your own role").build());
            return future;
        }

        // Get current user info and role
        String selectQuery = "SELECT userUUID, username, email, displayName, role, createdAt, lastLogin FROM user WHERE userUUID = ?";
        mysqlPool.preparedQuery(selectQuery)
            .execute(Tuple.of(targetUserId))
            .onSuccess(rows -> {
                if (rows.size() == 0) {
                    future.complete(AdminActionResult.builder().success(false).message("User not found").build());
                    return;
                }

                Row row = rows.iterator().next();
                String previousRole = row.getString("role");

                // Update the role
                String updateQuery = "UPDATE user SET role = ?, updatedAt = NOW() WHERE userUUID = ?";
                mysqlPool.preparedQuery(updateQuery)
                    .execute(Tuple.of(normalizedRole, targetUserId))
                    .onSuccess(updateResult -> {
                        // Build user object for response
                        User userObj = new User();
                        userObj.setId(UUID.fromString(row.getString("userUUID")));
                        userObj.setUsername(row.getString("username"));
                        userObj.setEmail(row.getString("email"));
                        userObj.setDisplayName(row.getString("displayName"));
                        userObj.setRole(normalizedRole);
                        userObj.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                        userObj.setLastLogin(Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastLogin")));

                        log.info("Admin {} updated role for user {} from {} to {}",
                            auth.getUsername(), row.getString("username"), previousRole, normalizedRole);

                        future.complete(AdminActionResult.builder()
                            .success(true)
                            .user(userObj)
                            .previousRole(previousRole)
                            .newRole(normalizedRole)
                            .message("User role updated from " + previousRole + " to " + normalizedRole)
                            .build());
                    })
                    .onFailure(err -> {
                        log.error("Failed to update user role", err);
                        future.completeExceptionally(new RuntimeException("Failed to update user role: " + err.getMessage()));
                    });
            })
            .onFailure(err -> {
                log.error("Failed to find user", err);
                future.completeExceptionally(new RuntimeException("Failed to find user: " + err.getMessage()));
            });

        return future;
    }

    /**
     * Ban a user (admin only).
     * Prevents the user from logging in.
     */
    @Mutation
    public CompletableFuture<AdminActionResult> banUser(@Name("userId") String targetUserId, @Name("reason") String reason) {
        AuthContext auth = graphQLAuthContext.requireAdmin();

        CompletableFuture<AdminActionResult> future = new CompletableFuture<>();

        // Prevent admin from banning themselves
        if (targetUserId.equals(auth.getUserId().toString())) {
            future.complete(AdminActionResult.builder().success(false).message("Cannot ban yourself").build());
            return future;
        }

        // Get target user info
        String selectQuery = "SELECT userUUID, username, email, displayName, role, createdAt, lastLogin, isBanned FROM user WHERE userUUID = ?";
        mysqlPool.preparedQuery(selectQuery)
            .execute(Tuple.of(targetUserId))
            .onSuccess(rows -> {
                if (rows.size() == 0) {
                    future.complete(AdminActionResult.builder().success(false).message("User not found").build());
                    return;
                }

                Row row = rows.iterator().next();
                Boolean currentlyBanned = row.getBoolean("isBanned");

                if (Boolean.TRUE.equals(currentlyBanned)) {
                    future.complete(AdminActionResult.builder().success(false).message("User is already banned").build());
                    return;
                }

                // Update the user to banned status
                String updateQuery = "UPDATE user SET isBanned = TRUE, bannedAt = NOW(), bannedReason = ?, bannedBy = ?, updatedAt = NOW() WHERE userUUID = ?";
                mysqlPool.preparedQuery(updateQuery)
                    .execute(Tuple.of(reason, auth.getUserId().toString(), targetUserId))
                    .onSuccess(updateResult -> {
                        // Invalidate user's refresh token in Redis to force logout
                        String redisKey = "refresh_token:" + targetUserId;
                        redisClient.connect()
                            .compose(connection -> {
                                return io.vertx.redis.client.RedisAPI.api(connection)
                                    .del(List.of(redisKey));
                            })
                            .onComplete(redisResult -> {
                                // Build user object for response
                                LocalDateTime bannedAt = LocalDateTime.now(ZoneOffset.UTC);
                                User userObj = new User();
                                userObj.setId(UUID.fromString(row.getString("userUUID")));
                                userObj.setUsername(row.getString("username"));
                                userObj.setEmail(row.getString("email"));
                                userObj.setDisplayName(row.getString("displayName"));
                                userObj.setRole(row.getString("role"));
                                userObj.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                                userObj.setLastLogin(Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastLogin")));

                                log.info("Admin {} banned user {} for reason: {}",
                                    auth.getUsername(), row.getString("username"), reason);

                                future.complete(AdminActionResult.builder()
                                    .success(true)
                                    .user(userObj)
                                    .isBanned(true)
                                    .bannedAt(bannedAt.atOffset(ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                                    .bannedReason(reason)
                                    .message("User " + row.getString("username") + " has been banned")
                                    .build());
                            });
                    })
                    .onFailure(err -> {
                        log.error("Failed to ban user", err);
                        future.completeExceptionally(new RuntimeException("Failed to ban user: " + err.getMessage()));
                    });
            })
            .onFailure(err -> {
                log.error("Failed to find user", err);
                future.completeExceptionally(new RuntimeException("Failed to find user: " + err.getMessage()));
            });

        return future;
    }

    /**
     * Unban a user (admin only).
     * Restores the user's ability to login.
     */
    @Mutation
    public CompletableFuture<AdminActionResult> unbanUser(@Name("userId") String targetUserId) {
        AuthContext auth = graphQLAuthContext.requireAdmin();

        CompletableFuture<AdminActionResult> future = new CompletableFuture<>();

        // Get target user info
        String selectQuery = "SELECT userUUID, username, email, displayName, role, createdAt, lastLogin, isBanned FROM user WHERE userUUID = ?";
        mysqlPool.preparedQuery(selectQuery)
            .execute(Tuple.of(targetUserId))
            .onSuccess(rows -> {
                if (rows.size() == 0) {
                    future.complete(AdminActionResult.builder().success(false).message("User not found").build());
                    return;
                }

                Row row = rows.iterator().next();
                Boolean currentlyBanned = row.getBoolean("isBanned");

                if (!Boolean.TRUE.equals(currentlyBanned)) {
                    future.complete(AdminActionResult.builder().success(false).message("User is not banned").build());
                    return;
                }

                // Update the user to unbanned status
                String updateQuery = "UPDATE user SET isBanned = FALSE, bannedAt = NULL, bannedReason = NULL, bannedBy = NULL, updatedAt = NOW() WHERE userUUID = ?";
                mysqlPool.preparedQuery(updateQuery)
                    .execute(Tuple.of(targetUserId))
                    .onSuccess(updateResult -> {
                        // Build user object for response
                        User userObj = new User();
                        userObj.setId(UUID.fromString(row.getString("userUUID")));
                        userObj.setUsername(row.getString("username"));
                        userObj.setEmail(row.getString("email"));
                        userObj.setDisplayName(row.getString("displayName"));
                        userObj.setRole(row.getString("role"));
                        userObj.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                        userObj.setLastLogin(Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastLogin")));

                        log.info("Admin {} unbanned user {}",
                            auth.getUsername(), row.getString("username"));

                        future.complete(AdminActionResult.builder()
                            .success(true)
                            .user(userObj)
                            .isBanned(false)
                            .bannedAt(null)
                            .bannedReason(null)
                            .message("User " + row.getString("username") + " has been unbanned")
                            .build());
                    })
                    .onFailure(err -> {
                        log.error("Failed to unban user", err);
                        future.completeExceptionally(new RuntimeException("Failed to unban user: " + err.getMessage()));
                    });
            })
            .onFailure(err -> {
                log.error("Failed to find user", err);
                future.completeExceptionally(new RuntimeException("Failed to find user: " + err.getMessage()));
            });

        return future;
    }

    // Helper methods

    private Future<Void> sendCharacterGreeting(UUID conversationId, UUID characterId, UUID userId) {
        // Get character greeting
        String query = "SELECT greeting, name FROM `character` WHERE characterUUID = ?";
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(characterId.toString()))
            .compose(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    String greetingText = row.getString("greeting");
                    String name = row.getString("name");

                    if (greetingText == null || greetingText.isEmpty()) {
                        greetingText = "Hello! I'm " + name + ". How can I help you today?";
                    }

                    // Insert greeting message
                    UUID messageId = UUID.randomUUID();
                    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                    String insertQuery = """
                        INSERT INTO message (messageUUID, conversationId, content, role, displayName, createdAt)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """;

                    final String finalGreeting = greetingText;
                    return mysqlPool.preparedQuery(insertQuery)
                        .execute(Tuple.of(
                            messageId.toString(),
                            conversationId.toString(),
                            finalGreeting,
                            Message.ROLE_ASSISTANT,
                            name,
                            now
                        ))
                        .map(result -> {
                            // Publish greeting to subscription for real-time updates
                            JsonObject messageEvent = new JsonObject()
                                .put("messageId", messageId.toString())
                                .put("content", finalGreeting)
                                .put("streamingContent", finalGreeting)
                                .put("role", "ASSISTANT")
                                .put("isComplete", true)
                                .put("tokenCount", 0)
                                .put("createdAt", now.toString());

                            String channelName = "conversation:" + conversationId + ":message-stream";
                            vertx.eventBus().publish(channelName, messageEvent);
                            log.debug("Published character greeting to subscription channel: {}", channelName);

                            return (Void) null;
                        });
                }
                return Future.succeededFuture();
            })
            .recover(error -> {
                log.warn("Failed to send character greeting", error);
                return Future.succeededFuture(); // Don't fail the whole operation
            });
    }

    private Future<UserSettings> getSettings(UUID userId) {
        // For now, return default settings
        UserSettings settings = UserSettings.createDefaultSettings(userId);
        return Future.succeededFuture(settings);
    }

    private Future<User> getUserById(UUID userId) {
        String query = "SELECT * FROM user WHERE userUUID = ?";
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(userId.toString()))
            .map(rows -> {
                if (rows.size() == 0) {
                    throw new RuntimeException("User not found");
                }
                Row row = rows.iterator().next();
                User user = new User();
                user.setId(UUID.fromString(row.getString("userUUID")));
                user.setUsername(row.getString("username"));
                user.setEmail(row.getString("email"));
                user.setDisplayName(row.getString("displayName"));
                user.setBio(row.getString("bio"));
                user.setProfilePicture(row.getString("profilePicture"));
                user.setRole(row.getString("role"));
                user.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                user.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
                user.setLastLogin(Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastLogin")));
                return user;
            });
    }

    private Future<Character> getCharacterById(UUID characterId) {
        String query = "SELECT * FROM `character` WHERE characterUUID = ?";
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(characterId.toString()))
            .map(rows -> {
                if (rows.size() == 0) {
                    throw new RuntimeException("Character not found");
                }
                Row row = rows.iterator().next();
                return mapRowToCharacter(row);
            });
    }

    private Future<Character> getCharacterByIdForUser(UUID characterId, UUID userId) {
        String query = """
            SELECT c.*,
                   CASE WHEN cf.id IS NOT NULL THEN TRUE ELSE FALSE END AS isFavorite
            FROM `character` c
            LEFT JOIN character_favorite cf ON c.characterUUID = cf.character_id AND cf.user_id = ?
            WHERE c.characterUUID = ?
            """;
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(userId.toString(), characterId.toString()))
            .map(rows -> {
                if (rows.size() == 0) {
                    throw new RuntimeException("Character not found");
                }
                Row row = rows.iterator().next();
                Character character = mapRowToCharacter(row);
                character.setIsFavorite(row.getBoolean("isFavorite"));
                return character;
            });
    }

    private Character mapRowToCharacter(Row row) {
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
        character.setInitialAffinityToUser(row.getInteger("initialAffinityToUser"));
        character.setInitialAffinityFromUser(row.getInteger("initialAffinityFromUser"));
        character.setIsDefault(row.getBoolean("isDefault"));
        character.setIsPublic(row.getBoolean("isPublic"));
        character.setIsFavorite(false); // Default to false, caller should set if query includes favorite join
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
        character.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        character.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return character;
    }

    private Message mapRowToMessage(Row row) {
        // Extract characterId for group chat attribution
        String charId = row.getString("characterId");
        UUID characterId = charId != null ? UUID.fromString(charId) : null;

        String sceneId = row.getString("sceneId");

        return Message.builder()
            .id(UUID.fromString(row.getString("messageUUID")))
            .seq(row.getLong("seq"))
            .conversationId(UUID.fromString(row.getString("conversationId")))
            .content(row.getString("content"))
            .role(MessageRole.fromString(row.getString("role")))
            .displayName(row.getString("displayName"))
            .characterId(characterId)
            .isBase64Encoded(row.getBoolean("isBase64Encoded"))
            .isEdited(row.getBoolean("isEdited"))
            .isDeleted(row.getBoolean("isDeleted"))
            .isStreaming(row.getBoolean("isStreaming"))
            .isInterjection(row.getBoolean("isInterjection"))
            .editCount(row.getInteger("editCount"))
            .model(row.getString("model"))
            .completionTokens(row.getInteger("completionTokens"))
            .totalTimeMs(row.getInteger("totalTimeMs"))
            .sceneId(sceneId != null ? UUID.fromString(sceneId) : null)
            .messageType(MessageType.fromString(row.getString("messageType")))
            .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
            .updatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")))
            .build();
    }

    private Conversation mapRowToConversation(Row row) {
        Conversation conversation = new Conversation();
        conversation.setId(UUID.fromString(row.getString("conversationGUID")));
        conversation.setOwnerId(UUID.fromString(row.getString("ownerId")));
        conversation.setTitle(row.getString("title"));
        conversation.setDescription(row.getString("description"));
        conversation.setActive(row.getBoolean("active"));
        conversation.setIsPinned(row.getBoolean("isPinned"));
        conversation.setIsArchived(row.getBoolean("isArchived"));
        conversation.setMessageCount(row.getInteger("messageCount"));
        conversation.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        conversation.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));

        String characterIdStr = row.getString("characterId");
        if (characterIdStr != null && !characterIdStr.isEmpty()) {
            conversation.setCharacterId(UUID.fromString(characterIdStr));
        }

        // Group chat fields
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

    /**
     * Change user password.
     * Schema: changePassword(oldPassword: String!, newPassword: String!): PasswordChangeResponse!
     */
    @Mutation
    public CompletableFuture<OperationResult> changePassword(@Name("oldPassword") String oldPassword, @Name("newPassword") String newPassword) {
        AuthContext authContext = graphQLAuthContext.requireAuth();
        UUID userId = authContext.getUserId();

        CompletableFuture<OperationResult> future = new CompletableFuture<>();

        String query = "SELECT passwordHash FROM user WHERE userUUID = ?";
        mysqlPool.preparedQuery(query)
            .execute(Tuple.of(userId.toString()))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) {
                    return Future.failedFuture(new RuntimeException("User not found"));
                }

                String currentHash = rows.iterator().next().getString("passwordHash");
                final String oldPass = oldPassword;
                final String newPass = newPassword;

                // Offload both BCrypt verify and hash to worker thread (CPU-bound)
                return vertx.<String>executeBlocking(promise -> {
                    BCrypt.Result result = BCrypt.verifyer().verify(oldPass.toCharArray(), currentHash);
                    if (!result.verified) {
                        promise.fail(new RuntimeException("Incorrect old password"));
                        return;
                    }
                    promise.complete(BCrypt.withDefaults().hashToString(bcryptRounds(), newPass.toCharArray()));
                }).compose(newHash -> {
                    String updateQuery = "UPDATE user SET passwordHash = ? WHERE userUUID = ?";
                    return mysqlPool.preparedQuery(updateQuery)
                        .execute(Tuple.of(newHash, userId.toString()))
                        .map(v -> true);
                });
            })
            .onSuccess(result -> {
                future.complete(OperationResult.builder().success(true).message("Password changed successfully").build());
            })
            .onFailure(error -> {
                future.complete(OperationResult.builder().success(false).message(error.getMessage()).build());
            });

        return future;
    }

    /**
     * Switch to a different conversation.
     */
    @Mutation
    public CompletableFuture<Conversation> switchConversation(@Name("id") String id) {
        AuthContext authContext = graphQLAuthContext.requireAuth();
        UUID conversationId = UUID.fromString(id);
        UUID userId = authContext.getUserId();

        CompletableFuture<Conversation> future = new CompletableFuture<>();

        String query = """
            SELECT * FROM conversation
            WHERE conversationGUID = ? AND ownerId = ?
            """;

        mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversationId.toString(), userId.toString()))
            .onSuccess(rows -> {
                if (!rows.iterator().hasNext()) {
                    future.completeExceptionally(new RuntimeException("Conversation not found"));
                    return;
                }

                Row row = rows.iterator().next();
                Conversation conversation = mapRowToConversation(row);

                // Update last accessed time
                String updateQuery = "UPDATE conversation SET updatedAt = ? WHERE conversationGUID = ?";
                mysqlPool.preparedQuery(updateQuery)
                    .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC), conversationId.toString()))
                    .onSuccess(v -> future.complete(conversation))
                    .onFailure(future::completeExceptionally);
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Clear all messages from a conversation.
     */
    @Mutation
    public CompletableFuture<Boolean> clearConversation(@Name("id") String id) {
        AuthContext authContext = graphQLAuthContext.requireAuth();
        UUID conversationId = UUID.fromString(id);
        UUID userId = authContext.getUserId();

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Verify ownership
        String verifyQuery = "SELECT COUNT(*) as count FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
        mysqlPool.preparedQuery(verifyQuery)
            .execute(Tuple.of(conversationId.toString(), userId.toString()))
            .compose(rows -> {
                Long count = rows.iterator().next().getLong("count");
                if (count == 0) {
                    return Future.failedFuture(new RuntimeException("Conversation not found"));
                }

                // Delete all messages
                String deleteQuery = "DELETE FROM message WHERE conversationId = ?";
                return mysqlPool.preparedQuery(deleteQuery)
                    .execute(Tuple.of(conversationId.toString()))
                    .compose(deleteResult -> {
                        // Reset messageCount to 0
                        String updateQuery = "UPDATE conversation SET messageCount = 0, updatedAt = NOW() WHERE conversationGUID = ?";
                        return mysqlPool.preparedQuery(updateQuery)
                            .execute(Tuple.of(conversationId.toString()))
                            .map(updateResult -> true);
                    });
            })
            .onSuccess(future::complete)
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Regenerate the last assistant message.
     * Deletes the specified assistant message and generates a new response based on
     * the last user message in the conversation.
     *
     * For GROUP CHATS: Also deletes all subsequent character messages and triggers
     * the character cascade (the character that authored the message regenerates,
     * followed by other characters automatically).
     *
     * Schema: regenerateMessage(id: ID!): Message!
     */
    @Mutation
    public CompletableFuture<Message> regenerateMessage(@Name("id") String id) {
        AuthContext authContext = graphQLAuthContext.requireAuth();
        // Schema uses 'id' parameter
        UUID messageId = UUID.fromString(id);
        UUID userId = authContext.getUserId();

        CompletableFuture<Message> future = new CompletableFuture<>();

        // Check message rate limit first (regeneration also triggers LLM generation)
        checkMessageRateLimit(userId.toString())
            .compose(remaining -> {
                // Rate limit check passed, get the message, conversation, and group chat info
                String query = """
                    SELECT m.*, c.ownerId, c.isGroupChat, c.groupState, c.turnMode
                    FROM message m
                    JOIN conversation c ON m.conversationId = c.conversationGUID
                    WHERE m.messageUUID = ? AND (c.ownerId = ? OR m.ownerId = ?)
                    """;

                return mysqlPool.preparedQuery(query)
                    .execute(Tuple.of(messageId.toString(), userId.toString(), userId.toString()));
            })
            .compose(rows -> {
                if (!rows.iterator().hasNext()) {
                    return Future.failedFuture(new RuntimeException("Message not found"));
                }

                Row row = rows.iterator().next();
                String role = row.getString("role");

                // Only allow regenerating assistant messages
                if (!"assistant".equalsIgnoreCase(role)) {
                    return Future.failedFuture(new RuntimeException("Can only regenerate assistant messages"));
                }

                UUID conversationId = UUID.fromString(row.getString("conversationId"));
                Boolean isGroupChat = row.getBoolean("isGroupChat");
                String characterId = row.getString("characterId");
                LocalDateTime messageCreatedAt = row.getLocalDateTime("createdAt");

                // Regeneration deletes messages before it claims a turn, so
                // every reason it could fail is checked first. Otherwise a
                // paused conversation, a departed speaker or a legacy row with
                // no speaker loses the reply and gains nothing.
                if (Boolean.TRUE.equals(isGroupChat)) {
                    String groupState = row.getString("groupState");
                    boolean free = groupState == null
                            || "WAITING_FOR_USER".equals(groupState)
                            || "INTERJECTION_PENDING".equals(groupState);
                    if (!free) {
                        return Future.failedFuture(new RuntimeException(
                                "Cannot regenerate while the conversation is paused or another character is speaking"));
                    }
                    if (characterId == null) {
                        return Future.failedFuture(new RuntimeException(
                                "Cannot regenerate group message without character ID"));
                    }
                    return mysqlPool.preparedQuery("""
                            SELECT COUNT(*) AS count FROM conversation_character
                            WHERE conversationId = ? AND characterId = ? AND isActive = TRUE
                            """)
                        .execute(Tuple.of(conversationId.toString(), characterId))
                        .compose(member -> {
                            if (member.iterator().next().getLong("count") == 0) {
                                return Future.failedFuture(new RuntimeException(
                                        "Character is no longer in this group"));
                            }

                            // GROUP CHAT: Delete this message AND all subsequent
                            // messages so the reply is regenerated from a clean
                            // history.
                            log.debug("Regenerating group chat message: deleting message {} and all subsequent messages", messageId);

                            // Count messages to be deleted for messageCount update
                            String countQuery = """
                                SELECT COUNT(*) as cnt FROM message
                                WHERE conversationId = ? AND createdAt >= ?
                                AND isDeleted = FALSE
                                """;

                            return mysqlPool.preparedQuery(countQuery)
                                .execute(Tuple.of(conversationId.toString(), messageCreatedAt))
                                .compose(countRows -> {
                                    int deleteCount = countRows.iterator().next().getInteger("cnt");

                                    // Delete all messages from this point forward
                                    String deleteQuery = """
                                        DELETE FROM message WHERE conversationId = ? AND createdAt >= ? AND isDeleted = FALSE
                                        """;
                                    return mysqlPool.preparedQuery(deleteQuery)
                                        .execute(Tuple.of(conversationId.toString(), messageCreatedAt))
                                        .compose(v -> {
                                            // Update messageCount
                                            String updateCountQuery = """
                                                UPDATE conversation
                                                SET messageCount = GREATEST(IFNULL(messageCount, 0) - ?, 0),
                                                    groupState = 'WAITING_FOR_USER'
                                                WHERE conversationGUID = ?
                                                """;
                                            return mysqlPool.preparedQuery(updateCountQuery)
                                                .execute(Tuple.of(deleteCount, conversationId.toString()));
                                        })
                                        .map(v -> new JsonObject()
                                            .put("conversationId", conversationId.toString())
                                            .put("isGroupChat", true)
                                            .put("characterId", characterId));
                                });
                        });
                } else {
                    // REGULAR CHAT: Just delete the single message
                    String deleteQuery = "DELETE FROM message WHERE messageUUID = ? AND isDeleted = FALSE";
                    return mysqlPool.preparedQuery(deleteQuery)
                        .execute(Tuple.of(messageId.toString()))
                        .compose(v -> {
                            // Decrement messageCount since we deleted a message
                            String updateCountQuery = "UPDATE conversation SET messageCount = GREATEST(IFNULL(messageCount, 1) - 1, 0) WHERE conversationGUID = ?";
                            return mysqlPool.preparedQuery(updateCountQuery)
                                .execute(Tuple.of(conversationId.toString()));
                        })
                        .map(v -> new JsonObject()
                            .put("conversationId", conversationId.toString())
                            .put("isGroupChat", false)
                            .put("characterId", characterId));
                }
            })
            .compose(context -> {
                UUID conversationId = UUID.fromString(context.getString("conversationId"));
                boolean isGroupChat = context.getBoolean("isGroupChat", false);

                if (isGroupChat) {
                    // GROUP CHAT: Get the character info and trigger group response
                    String characterId = context.getString("characterId");

                    if (characterId == null) {
                        return Future.failedFuture(new RuntimeException("Cannot regenerate group message without character ID"));
                    }

                    // Get character info for the response
                    String charQuery = """
                        SELECT c.characterUUID, c.name, c.systemPrompt
                        FROM `character` c
                        WHERE c.characterUUID = ?
                        """;

                    return mysqlPool.preparedQuery(charQuery)
                        .execute(Tuple.of(characterId))
                        .map(rows -> {
                            if (!rows.iterator().hasNext()) {
                                throw new RuntimeException("Character not found");
                            }
                            Row charRow = rows.iterator().next();
                            return context
                                .put("characterName", charRow.getString("name"))
                                .put("systemPrompt", charRow.getString("systemPrompt"));
                        });
                } else {
                    // REGULAR CHAT: Get the last user message and character name
                    String charId = context.getString("characterId");
                    String lastUserQuery = """
                        SELECT content FROM message
                        WHERE conversationId = ? AND role = 'user'
                        AND isDeleted = FALSE
                        ORDER BY seq DESC LIMIT 1
                        """;
                    Future<String> userContentFuture = mysqlPool.preparedQuery(lastUserQuery)
                        .execute(Tuple.of(conversationId.toString()))
                        .map(rows -> {
                            if (rows.iterator().hasNext()) {
                                return rows.iterator().next().getString("content");
                            }
                            return "";
                        });
                    if (charId != null && !charId.isEmpty()) {
                        String charQuery = """
                            SELECT c.name FROM `character` c
                            WHERE c.characterUUID = ?
                            """;
                        Future<String> charNameFuture = mysqlPool.preparedQuery(charQuery)
                            .execute(Tuple.of(charId))
                            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next().getString("name") : null);
                        return userContentFuture.compose(userContent ->
                            charNameFuture.map(charName ->
                                context.put("characterName", charName).put("userContent", userContent)));
                    }
                    return userContentFuture.map(userContent -> context.put("userContent", userContent));
                }
            })
            .onSuccess(context -> {
                UUID conversationId = UUID.fromString(context.getString("conversationId"));
                boolean isGroupChat = context.getBoolean("isGroupChat", false);
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

                if (isGroupChat) {
                    // GROUP CHAT: Re-run the same character's turn. The claim
                    // rejects a paused or busy conversation instead of
                    // overwriting the speaker under an in-flight response.
                    String characterId = context.getString("characterId");
                    String characterName = context.getString("characterName");

                    groupChatService.startCharacterTurnFor(
                            conversationId.toString(), characterId, userId.toString())
                        .subscribe().with(
                            character -> {
                                if (character == null) {
                                    future.completeExceptionally(new RuntimeException(
                                        "Cannot regenerate while the conversation is paused or another character is speaking"));
                                    return;
                                }
                                log.debug("Triggered group chat regeneration for character: {} in conversation: {}",
                                        characterName, conversationId);
                                future.complete(Message.builder()
                                    .id(UUID.randomUUID())
                                    .conversationId(conversationId)
                                    .content("")
                                    .role(MessageRole.ASSISTANT)
                                    .displayName(characterName)
                                    .createdAt(now.toString())
                                    .updatedAt(now.toString())
                                    .editCount(0)
                                    .isEdited(false)
                                    .isDeleted(false)
                                    .build());
                            },
                            future::completeExceptionally);
                } else {
                    // REGULAR CHAT: Standard regeneration
                    // Note: userName is resolved centrally by LLMVerticle using userId
                    UUID assistantMessageId = UUID.randomUUID();

                    String charName = context.getString("characterName");
                    String displayName = (charName != null && !charName.isEmpty()) ? charName : "Assistant";

                    JsonObject request = new JsonObject()
                        .put("conversationId", conversationId.toString())
                        .put("userId", userId.toString())
                        .put("content", context.getString("userContent"))
                        .put("assistantMessageId", assistantMessageId.toString())
                        .put("regenerate", true);

                    vertx.eventBus().send("llm.generate", request);

                    Message message = Message.builder()
                        .id(assistantMessageId)
                        .conversationId(conversationId)
                        .content("")
                        .role(MessageRole.ASSISTANT)
                        .displayName(displayName)
                        .createdAt(now.toString())
                        .updatedAt(now.toString())
                        .editCount(0)
                        .isEdited(false)
                        .isDeleted(false)
                        .build();
                    future.complete(message);
                }
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Set a character as the default for the user.
     */
    @Mutation
    public CompletableFuture<Character> setDefaultCharacter(@Name("id") String id) {
        AuthContext authContext = graphQLAuthContext.requireAuth();
        UUID characterId = UUID.fromString(id);
        UUID userId = authContext.getUserId();

        CompletableFuture<Character> future = new CompletableFuture<>();

        // Verify the target character exists and belongs to this user BEFORE
        // clearing the current default. Otherwise a bad/missing characterId
        // would clear the user's default and then fail, leaving them with none.
        String verifyQuery = "SELECT 1 FROM `character` WHERE characterUUID = ? AND ownerId = ?";
        mysqlPool.preparedQuery(verifyQuery)
            .execute(Tuple.of(characterId.toString(), userId.toString()))
            .compose(verifyResult -> {
                if (verifyResult.rowCount() == 0) {
                    return Future.failedFuture(new RuntimeException("Character not found"));
                }
                // First, unset any existing default
                String unsetQuery = "UPDATE `character` SET isDefault = FALSE WHERE ownerId = ?";
                return mysqlPool.preparedQuery(unsetQuery)
                    .execute(Tuple.of(userId.toString()))
                    .compose(v -> {
                        // Set the new default
                        String setQuery = "UPDATE `character` SET isDefault = TRUE WHERE characterUUID = ? AND ownerId = ?";
                        return mysqlPool.preparedQuery(setQuery)
                            .execute(Tuple.of(characterId.toString(), userId.toString()));
                    });
            })
            .compose(result -> {
                if (result.rowCount() == 0) {
                    return Future.failedFuture(new RuntimeException("Character not found"));
                }

                // Fetch the updated character
                String fetchQuery = "SELECT * FROM `character` WHERE characterUUID = ?";
                return mysqlPool.preparedQuery(fetchQuery)
                    .execute(Tuple.of(characterId.toString()));
            })
            .onSuccess(rows -> {
                if (!rows.iterator().hasNext()) {
                    future.completeExceptionally(new RuntimeException("Character not found"));
                    return;
                }

                Character character = mapRowToCharacter(rows.iterator().next());
                future.complete(character);
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Reset user settings to defaults.
     * Now uses SettingsService for all settings operations (DRY principle).
     */
    @Mutation
    public CompletableFuture<Settings> resetSettings() {
        AuthContext authContext = graphQLAuthContext.requireAuth();

        String userId = authContext.getUserId().toString();
        CompletableFuture<Settings> future = new CompletableFuture<>();

        // Create SettingsService instance
        // Using injected settingsService

        settingsService.resetToDefaults(userId)
            .subscribe().with(
                settings -> {
                    future.complete(settings);
                },
                error -> {
                    log.error("Failed to reset settings for user {}: {}", userId, error.getMessage());
                    future.completeExceptionally(error);
                });

        return future;
    }

    /**
     * Reload the LLM model (admin only).
     */
    @Mutation
    public CompletableFuture<Boolean> reloadModel() {
        graphQLAuthContext.requireAdmin();

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Send reload request to LLM verticle (model loading can take time)
        DeliveryOptions reloadOptions = new DeliveryOptions().setSendTimeout(120000);
        vertx.eventBus().<JsonObject>request("llm.reload", new JsonObject(), reloadOptions)
            .onSuccess(response -> {
                future.complete(response.body().getBoolean("success", false));
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Update user profile.
     * Schema: updateProfile(input: UpdateProfileInput!): User!
     */
    @Mutation
    public CompletableFuture<User> updateProfile(@Name("input") UpdateProfileInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        CompletableFuture<User> future = new CompletableFuture<>();

        // Build update query
        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (input.containsKey("displayName")) {
            setClauses.add("displayName = ?");
            params.add(DisplayNameFormatter.normalize((String) input.get("displayName")));
        }
        if (input.containsKey("email")) {
            setClauses.add("email = ?");
            params.add(input.get("email"));
        }
        if (input.containsKey("bio")) {
            setClauses.add("bio = ?");
            params.add(input.get("bio"));
        }
        if (input.containsKey("profilePicture")) {
            setClauses.add("profilePicture = ?");
            params.add(input.get("profilePicture"));
        }
        if (input.containsKey("username")) {
            setClauses.add("username = ?");
            params.add(input.get("username"));
        }

        if (setClauses.isEmpty()) {
            return getUserById(auth.getUserId()).toCompletionStage().toCompletableFuture();
        }

        params.add(auth.getUserId().toString());
        String updateQuery = "UPDATE user SET " + String.join(", ", setClauses) + " WHERE userUUID = ?";

        mysqlPool.preparedQuery(updateQuery)
            .execute(Tuple.wrap(params))
            .compose(v -> getUserById(auth.getUserId()))
            .onSuccess(future::complete)
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Pin a conversation.
     * Schema: pinConversation(id: ID!): Conversation!
     */
    @Mutation
    public CompletableFuture<Conversation> pinConversation(@Name("id") String id) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        UUID conversationId = UUID.fromString(id);
        CompletableFuture<Conversation> future = new CompletableFuture<>();

        String updateQuery = "UPDATE conversation SET isPinned = NOT COALESCE(isPinned, FALSE), updatedAt = ? WHERE conversationGUID = ? AND ownerId = ?";
        mysqlPool.preparedQuery(updateQuery)
            .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC), conversationId.toString(), auth.getUserId().toString()))
            .compose(result -> {
                if (result.rowCount() == 0) {
                    return Future.failedFuture(new RuntimeException("Conversation not found"));
                }
                String getQuery = "SELECT * FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
                return mysqlPool.preparedQuery(getQuery)
                    .execute(Tuple.of(conversationId.toString(), auth.getUserId().toString()));
            })
            .onSuccess(rows -> {
                if (rows.size() > 0) {
                    future.complete(mapRowToConversation(rows.iterator().next()));
                } else {
                    future.completeExceptionally(new RuntimeException("Conversation not found"));
                }
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Archive a conversation.
     * Schema: archiveConversation(id: ID!): Conversation!
     */
    @Mutation
    public CompletableFuture<Conversation> archiveConversation(@Name("id") String id) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        UUID conversationId = UUID.fromString(id);
        CompletableFuture<Conversation> future = new CompletableFuture<>();

        String updateQuery = "UPDATE conversation SET isArchived = NOT COALESCE(isArchived, FALSE), updatedAt = ? WHERE conversationGUID = ? AND ownerId = ?";
        mysqlPool.preparedQuery(updateQuery)
            .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC), conversationId.toString(), auth.getUserId().toString()))
            .compose(result -> {
                if (result.rowCount() == 0) {
                    return Future.failedFuture(new RuntimeException("Conversation not found"));
                }
                String getQuery = "SELECT * FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
                return mysqlPool.preparedQuery(getQuery)
                    .execute(Tuple.of(conversationId.toString(), auth.getUserId().toString()));
            })
            .onSuccess(rows -> {
                if (rows.size() > 0) {
                    future.complete(mapRowToConversation(rows.iterator().next()));
                } else {
                    future.completeExceptionally(new RuntimeException("Conversation not found"));
                }
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Switch character for a conversation.
     * Schema: switchCharacter(conversationId: ID!, characterId: ID!): Conversation!
     */
    @Mutation
    public CompletableFuture<Conversation> switchCharacter(@Name("conversationId") String conversationIdStr, @Name("characterId") String characterIdStr) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        UUID conversationId = UUID.fromString(conversationIdStr);
        UUID characterId = UUID.fromString(characterIdStr);
        CompletableFuture<Conversation> future = new CompletableFuture<>();

        // Verify character exists
        String charQuery = "SELECT COUNT(*) as count FROM `character` WHERE characterUUID = ?";
        mysqlPool.preparedQuery(charQuery)
            .execute(Tuple.of(characterId.toString()))
            .compose(charRows -> {
                Long count = charRows.iterator().next().getLong("count");
                if (count == 0) {
                    return Future.failedFuture(new RuntimeException("Character not found"));
                }

                String updateQuery = "UPDATE conversation SET characterId = ?, updatedAt = ? WHERE conversationGUID = ? AND ownerId = ?";
                return mysqlPool.preparedQuery(updateQuery)
                    .execute(Tuple.of(characterId.toString(), LocalDateTime.now(ZoneOffset.UTC), conversationId.toString(), auth.getUserId().toString()));
            })
            .compose(result -> {
                if (result.rowCount() == 0) {
                    return Future.failedFuture(new RuntimeException("Conversation not found or unauthorized"));
                }
                // Fetch character fields the `characterSwitch` subscription
                // needs, then publish the event on the per-conversation channel
                // the consumer listens on. Shape must match
                // SubscriptionResolver.characterSwitch.
                String charFetchQuery = "SELECT name, description, avatar, systemPrompt, greeting " +
                        "FROM `character` WHERE characterUUID = ?";
                return mysqlPool.preparedQuery(charFetchQuery)
                    .execute(Tuple.of(characterId.toString()))
                    .onSuccess(charRows -> {
                        if (charRows.size() > 0) {
                            Row c = charRows.iterator().next();
                            JsonObject event = new JsonObject()
                                .put("newCharacterId", characterId.toString())
                                .put("characterName", c.getString("name") != null ? c.getString("name") : "")
                                .put("characterDescription", c.getString("description") != null ? c.getString("description") : "")
                                .put("characterAvatar", c.getString("avatar"))
                                .put("systemPrompt", c.getString("systemPrompt"))
                                .put("greeting", c.getString("greeting"));
                            vertx.eventBus().publish(
                                "conversation:" + conversationId + ":character-switched",
                                event);
                        }
                    })
                    .compose(v -> {
                        String getQuery = "SELECT * FROM conversation WHERE conversationGUID = ? AND ownerId = ?";
                        return mysqlPool.preparedQuery(getQuery)
                            .execute(Tuple.of(conversationId.toString(), auth.getUserId().toString()));
                    });
            })
            .compose(rows -> {
                if (rows.size() > 0) {
                    Conversation conv = mapRowToConversation(rows.iterator().next());
                    // Send greeting from new character, wait for completion before returning
                    return sendCharacterGreeting(conversationId, characterId, auth.getUserId())
                        .map(v -> conv);
                } else {
                    return Future.failedFuture(new RuntimeException("Conversation not found"));
                }
            })
            .onSuccess(future::complete)
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Clone a character.
     * Schema: cloneCharacter(id: ID!, name: String!): Character!
     */
    @Mutation
    public CompletableFuture<Character> cloneCharacter(@Name("id") String id, @Name("name") String newName) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        UUID sourceId = UUID.fromString(id);
        CompletableFuture<Character> future = new CompletableFuture<>();

        // Get source character
        String getQuery = "SELECT * FROM `character` WHERE characterUUID = ?";
        mysqlPool.preparedQuery(getQuery)
            .execute(Tuple.of(sourceId.toString()))
            .compose(rows -> {
                if (rows.size() == 0) {
                    return Future.failedFuture(new RuntimeException("Character not found"));
                }

                Row source = rows.iterator().next();
                UUID newId = UUID.randomUUID();
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

                String insertQuery = """
                    INSERT INTO `character` (characterUUID, name, description, systemPrompt, appearance,
                                              personality, gender, pronouns, ageYears, height, build, hair, eyes,
                                              strength, dexterity, constitution, intelligence, wisdom, charisma,
                                              likes, dislikes, backstory, goals,
                                              greeting, examples, avatar, isDefault, isPublic,
                                              temperature, maxTokens, topP, topK, repeatPenalty,
                                              ownerId, createdAt, updatedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;

                return mysqlPool.preparedQuery(insertQuery)
                    .execute(io.vertx.sqlclient.Tuple.tuple()
                        .addValue(newId.toString())
                        .addValue(newName)
                        .addValue(source.getString("description"))
                        .addValue(source.getString("systemPrompt"))
                        .addValue(source.getString("appearance"))
                        .addValue(source.getString("personality"))
                        .addValue(source.getString("gender"))
                        .addValue(source.getString("pronouns"))
                        .addValue(source.getInteger("ageYears"))
                        .addValue(source.getString("height"))
                        .addValue(source.getString("build"))
                        .addValue(source.getString("hair"))
                        .addValue(source.getString("eyes"))
                        .addValue(source.getInteger("strength"))
                        .addValue(source.getInteger("dexterity"))
                        .addValue(source.getInteger("constitution"))
                        .addValue(source.getInteger("intelligence"))
                        .addValue(source.getInteger("wisdom"))
                        .addValue(source.getInteger("charisma"))
                        .addValue(source.getString("likes"))
                        .addValue(source.getString("dislikes"))
                        .addValue(source.getString("backstory"))
                        .addValue(source.getString("goals"))
                        .addValue(source.getString("greeting"))
                        .addValue(source.getString("examples"))
                        .addValue(source.getString("avatar"))
                        .addValue(false)
                        .addValue(// not default
                        false)
                        .addValue(// not public (clone is private)
                        source.getDouble("temperature"))
                        .addValue(source.getInteger("maxTokens"))
                        .addValue(source.getDouble("topP"))
                        .addValue(source.getInteger("topK"))
                        .addValue(source.getDouble("repeatPenalty"))
                        .addValue(auth.getUserId().toString())
                        .addValue(now)
                        .addValue(now))
                    .map(v -> newId);
            })
            .compose(newId -> getCharacterById(newId))
            .onSuccess(character -> {
                vertx.eventBus().publish(
                    "user:" + auth.getUserId().toString() + ":characters:updated",
                    new JsonObject()
                        .put("id", character.getId().toString())
                        .put("name", character.getName())
                        .put("description", character.getDescription() != null ? character.getDescription() : "")
                        .put("systemPrompt", character.getSystemPrompt() != null ? character.getSystemPrompt() : ""));
                future.complete(character);
            })
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Rate a character.
     * Schema: rateCharacter(id: ID!, rating: Int!): Character!
     */
    @Mutation
    public CompletableFuture<Character> rateCharacter(@Name("id") String id, @Name("rating") Integer rating) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        UUID characterId = UUID.fromString(id);
        CompletableFuture<Character> future = new CompletableFuture<>();

        // Validate rating (1-5). Guard against null before unboxing (GraphQL
        // clients can send null even for non-null schema fields depending on
        // SmallRye binding version).
        if (rating == null || rating < 1 || rating > 5) {
            return CompletableFuture.failedFuture(new RuntimeException("Rating must be between 1 and 5"));
        }

        // Update rating (simple average approach, would need rating table for proper implementation)
        String updateQuery = """
            UPDATE `character` SET
                rating = COALESCE((COALESCE(rating, 0) * COALESCE(usageCount, 0) + ?) / (COALESCE(usageCount, 0) + 1), ?),
                usageCount = COALESCE(usageCount, 0) + 1,
                updatedAt = ?
            WHERE characterUUID = ?
            """;

        mysqlPool.preparedQuery(updateQuery)
            .execute(Tuple.of(rating.doubleValue(), rating.doubleValue(), LocalDateTime.now(ZoneOffset.UTC), characterId.toString()))
            .compose(result -> getCharacterById(characterId))
            .onSuccess(future::complete)
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Favorite or unfavorite a character.
     * Schema: favoriteCharacter(id: ID!, isFavorite: Boolean!): Character!
     */
    @Mutation
    public CompletableFuture<Character> favoriteCharacter(@Name("id") String id, @Name("isFavorite") Boolean isFavorite) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        UUID characterId = UUID.fromString(id);
        CompletableFuture<Character> future = new CompletableFuture<>();
        String userId = auth.getUserId().toString();

        if (isFavorite == null) {
            return CompletableFuture.failedFuture(new RuntimeException("isFavorite is required"));
        }
        if (isFavorite) {
            // Add to favorites
            String insertQuery = """
                INSERT INTO character_favorite (id, character_id, user_id, created_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE created_at = created_at
                """;

            mysqlPool.preparedQuery(insertQuery)
                .execute(Tuple.of(UUID.randomUUID().toString(), characterId.toString(), userId, LocalDateTime.now(ZoneOffset.UTC)))
                .compose(result -> getCharacterByIdForUser(characterId, auth.getUserId()))
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally);
        } else {
            // Remove from favorites
            String deleteQuery = """
                DELETE FROM character_favorite
                WHERE character_id = ? AND user_id = ?
                """;

            mysqlPool.preparedQuery(deleteQuery)
                .execute(Tuple.of(characterId.toString(), userId))
                .compose(result -> getCharacterByIdForUser(characterId, auth.getUserId()))
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally);
        }

        return future;
    }

    /**
     * Import settings from JSON string.
     * Schema: importSettings(data: String!): Settings!
     */
    @Mutation
    public CompletableFuture<Settings> importSettings(@Name("data") String data) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        CompletableFuture<Settings> future = new CompletableFuture<>();

        try {
            JsonObject importData = new JsonObject(data);

            // Extract settings from import data
            JsonObject llmSettings = importData.getJsonObject("llm", new JsonObject());
            JsonObject uiSettings = importData.getJsonObject("ui", new JsonObject());

            // Update user with imported settings
            String updateQuery = """
                UPDATE user SET
                    theme = COALESCE(?, theme),
                    language = COALESCE(?, language),
                    fontSize = COALESCE(?, fontSize),
                    codeTheme = COALESCE(?, codeTheme)
                WHERE userUUID = ?
                """;

            mysqlPool.preparedQuery(updateQuery)
                .execute(Tuple.of(
                    uiSettings.getString("theme"),
                    uiSettings.getString("language"),
                    uiSettings.getString("fontSize"),
                    uiSettings.getString("codeTheme"),
                    auth.getUserId().toString()
                ))
                .onSuccess(v -> {
                    // Return the actual current settings after the import
                    settingsService.getUserSettings(auth.getUserId().toString()).subscribe().with(
                        future::complete,
                        err -> future.completeExceptionally(err)
                    );
                })
                .onFailure(future::completeExceptionally);

        } catch (Exception e) {
            future.completeExceptionally(new RuntimeException("Invalid settings format: " + e.getMessage()));
        }

        return future;
    }

    /**
     * Import user data.
     * Schema: importData(data: String!): ImportResult!
     *
     * Handles two import formats:
     * 1. Flat structure (from our export): conversations[], messages[] with conversationId, characters[]
     * 2. Nested structure (legacy): conversations[].messages[]
     */
    @Mutation
    public CompletableFuture<ImportResult> importData(@Name("data") String data) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        CompletableFuture<ImportResult> future = new CompletableFuture<>();

        try {
            JsonObject importJson = new JsonObject(data);

            // Track import counts
            int[] counts = {0, 0, 0, 0}; // conversations, messages, characters, settings
            List<String> errors = new ArrayList<>();

            // Get arrays from import data
            JsonArray characters = importJson.getJsonArray("characters", new JsonArray());
            JsonArray conversations = importJson.getJsonArray("conversations", new JsonArray());
            JsonArray flatMessages = importJson.getJsonArray("messages", new JsonArray()); // Flat message array from export
            JsonObject settings = importJson.getJsonObject("settings");

            // Map old IDs to new IDs for import
            Map<String, String> conversationIdMap = new HashMap<>();
            Map<String, String> characterIdMap = new HashMap<>(); // Map old character IDs to new IDs

            mysqlPool.withTransaction(sqlClient -> {
                List<Future<?>> operations = new ArrayList<>();
                String userId = auth.getUserId().toString();
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

                // 1. Import characters with full fields and build ID mapping
                for (int i = 0; i < characters.size(); i++) {
                    JsonObject character = characters.getJsonObject(i);
                    String oldId = character.getString("id");
                    UUID newId = UUID.randomUUID();

                    // Store mapping for conversation character associations
                    if (oldId != null) {
                        characterIdMap.put(oldId, newId.toString());
                    }

                    String insertQuery = """
                        INSERT INTO `character` (characterUUID, ownerId, name, description, systemPrompt,
                                                  personality, greeting, avatar, temperature, maxTokens,
                                                  topP, topK, isPublic, isDefault, createdAt, updatedAt)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;

                    operations.add(sqlClient.preparedQuery(insertQuery)
                        .execute(io.vertx.sqlclient.Tuple.tuple()
                            .addValue(newId.toString())
                            .addValue(userId)
                            .addValue(character.getString("name"))
                            .addValue(character.getString("description"))
                            .addValue(character.getString("systemPrompt"))
                            .addValue(character.getString("personality"))
                            .addValue(character.getString("greeting"))
                            .addValue(character.getString("avatar"))
                            .addValue(character.getDouble("temperature", 0.7))
                            .addValue(character.getInteger("maxTokens", 2000))
                            .addValue(character.getDouble("topP", 0.9))
                            .addValue(character.getInteger("topK", 40))
                            .addValue(character.getBoolean("isPublic", false))
                            .addValue(false)
                            .addValue(// Never import as default
                            now)
                            .addValue(now)));
                    counts[2]++;
                }

                // 2. Import conversations and build ID mapping (including character associations)
                for (int i = 0; i < conversations.size(); i++) {
                    JsonObject conv = conversations.getJsonObject(i);
                    String oldId = conv.getString("id");
                    UUID newConvId = UUID.randomUUID();

                    // Store mapping for flat messages
                    if (oldId != null) {
                        conversationIdMap.put(oldId, newConvId.toString());
                    }

                    // Map character ID if present (restores character association)
                    String oldCharId = conv.getString("characterId");
                    String newCharId = (oldCharId != null) ? characterIdMap.get(oldCharId) : null;

                    String insertConvQuery = """
                        INSERT INTO conversation (conversationGUID, ownerId, characterId, title, description,
                                                   isPinned, isArchived, messageCount, createdAt, updatedAt)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;

                    operations.add(sqlClient.preparedQuery(insertConvQuery)
                        .execute(io.vertx.sqlclient.Tuple.tuple()
                            .addValue(newConvId.toString())
                            .addValue(userId)
                            .addValue(newCharId)
                            .addValue(// Restored character association (null if not mapped)
                            conv.getString("title", "Imported Conversation"))
                            .addValue(conv.getString("description"))
                            .addValue(conv.getBoolean("isPinned", false))
                            .addValue(conv.getBoolean("isArchived", false))
                            .addValue(conv.getInteger("messageCount", 0))
                            .addValue(now)
                            .addValue(now)));
                    counts[0]++;

                    // Handle nested messages (legacy format)
                    JsonArray nestedMessages = conv.getJsonArray("messages", new JsonArray());
                    for (int j = 0; j < nestedMessages.size(); j++) {
                        JsonObject msg = nestedMessages.getJsonObject(j);
                        UUID msgId = UUID.randomUUID();

                        // Normalize role to lowercase (export uses uppercase)
                        String role = msg.getString("role", "user").toLowerCase();

                        String insertMsgQuery = """
                            INSERT INTO message (messageUUID, conversationId, role, content,
                                                  editCount, isEdited, isDeleted, isBase64Encoded, createdAt, updatedAt)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """;

                        operations.add(sqlClient.preparedQuery(insertMsgQuery)
                            .execute(io.vertx.sqlclient.Tuple.tuple()
                                .addValue(msgId.toString())
                                .addValue(newConvId.toString())
                                .addValue(role)
                                .addValue(msg.getString("content"))
                                .addValue(msg.getInteger("editCount", 0))
                                .addValue(msg.getBoolean("isEdited", false))
                                .addValue(msg.getBoolean("isDeleted", false))
                                .addValue(msg.getBoolean("isBase64Encoded", false))
                                .addValue(now)
                                .addValue(now)));
                        counts[1]++;
                    }
                }

                // 3. Import flat messages (from our export format)
                for (int i = 0; i < flatMessages.size(); i++) {
                    JsonObject msg = flatMessages.getJsonObject(i);
                    String oldConvId = msg.getString("conversationId");

                    // Try to get from nested conversation object if not directly available
                    if (oldConvId == null) {
                        JsonObject convRef = msg.getJsonObject("conversation");
                        if (convRef != null) {
                            oldConvId = convRef.getString("id");
                        }
                    }

                    // Skip messages without valid conversation reference
                    if (oldConvId == null || !conversationIdMap.containsKey(oldConvId)) {
                        errors.add("Skipped message without valid conversation: " + msg.getString("id", "unknown"));
                        continue;
                    }

                    String newConvId = conversationIdMap.get(oldConvId);
                    UUID msgId = UUID.randomUUID();

                    // Normalize role to lowercase (export uses uppercase: "USER", "ASSISTANT")
                    String role = msg.getString("role", "user").toLowerCase();

                    String insertMsgQuery = """
                        INSERT INTO message (messageUUID, conversationId, role, content,
                                              editCount, isEdited, isDeleted, isBase64Encoded,
                                              completionTokens, totalTimeMs, createdAt, updatedAt)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;

                    operations.add(sqlClient.preparedQuery(insertMsgQuery)
                        .execute(io.vertx.sqlclient.Tuple.tuple()
                            .addValue(msgId.toString())
                            .addValue(newConvId)
                            .addValue(role)
                            .addValue(msg.getString("content"))
                            .addValue(msg.getInteger("editCount", 0))
                            .addValue(msg.getBoolean("isEdited", false))
                            .addValue(msg.getBoolean("isDeleted", false))
                            .addValue(msg.getBoolean("isBase64Encoded", false))
                            .addValue(msg.getInteger("completionTokens"))
                            .addValue(msg.getInteger("totalTimeMs"))
                            .addValue(now)
                            .addValue(now)));
                    counts[1]++;
                }

                // 4. Import settings if provided
                if (settings != null) {
                    JsonObject uiSettings = settings.getJsonObject("ui", new JsonObject());
                    JsonObject llmSettings = settings.getJsonObject("llm", new JsonObject());
                    JsonObject notifSettings = settings.getJsonObject("notifications", new JsonObject());

                    String updateUserQuery = """
                        UPDATE user SET
                            theme = COALESCE(?, theme),
                            language = COALESCE(?, language),
                            fontSize = COALESCE(?, fontSize),
                            codeTheme = COALESCE(?, codeTheme),
                            sounds = COALESCE(?, sounds),
                            notifications = COALESCE(?, notifications),
                            desktopNotifications = COALESCE(?, desktopNotifications),
                            emailNotifications = COALESCE(?, emailNotifications),
                            messageAlerts = COALESCE(?, messageAlerts),
                            systemAlerts = COALESCE(?, systemAlerts)
                        WHERE userUUID = ?
                        """;

                    operations.add(sqlClient.preparedQuery(updateUserQuery)
                        .execute(io.vertx.sqlclient.Tuple.tuple()
                            .addValue(uiSettings.getString("theme"))
                            .addValue(uiSettings.getString("language"))
                            .addValue(uiSettings.getString("fontSize"))
                            .addValue(uiSettings.getString("codeTheme"))
                            .addValue(notifSettings.getBoolean("sound"))
                            .addValue(notifSettings.getBoolean("enabled"))
                            .addValue(notifSettings.getBoolean("desktop"))
                            .addValue(notifSettings.getBoolean("email"))
                            .addValue(notifSettings.getBoolean("messageAlerts"))
                            .addValue(notifSettings.getBoolean("systemAlerts"))
                            .addValue(userId)));

                    // Update LLM settings in user_settings table
                    String upsertLlmQuery = """
                        INSERT INTO user_settings (fk_userUUID, llmTemperature, llmMaxTokens, llmTopP, llmTopK, llmContextSize)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            llmTemperature = COALESCE(VALUES(llmTemperature), llmTemperature),
                            llmMaxTokens = COALESCE(VALUES(llmMaxTokens), llmMaxTokens),
                            llmTopP = COALESCE(VALUES(llmTopP), llmTopP),
                            llmTopK = COALESCE(VALUES(llmTopK), llmTopK),
                            llmContextSize = COALESCE(VALUES(llmContextSize), llmContextSize)
                        """;

                    operations.add(sqlClient.preparedQuery(upsertLlmQuery)
                        .execute(Tuple.of(
                            userId,
                            llmSettings.getFloat("temperature"),
                            llmSettings.getInteger("maxTokens"),
                            llmSettings.getFloat("topP"),
                            llmSettings.getInteger("topK"),
                            llmSettings.getInteger("contextSize")
                        )));
                    counts[3]++;
                }

                return Future.all(operations);
            })
            .onSuccess(result -> {
                ImportResult.ImportCounts counts_result = ImportResult.ImportCounts.builder()
                    .conversations(counts[0]).messages(counts[1]).characters(counts[2]).settings(counts[3]).errors(errors)
                    .build();
                future.complete(ImportResult.builder().success(true).message("Import completed successfully").result(counts_result).build());
            })
            .onFailure(error -> {
                future.complete(ImportResult.builder().success(false).message("Import failed: " + error.getMessage()).build());
            });

        } catch (Exception e) {
            future.complete(ImportResult.builder().success(false).message("Invalid import data format: " + e.getMessage()).build());
        }

        return future;
    }

    /**
     * Delete all user data of a specific type.
     * Schema: deleteAllData(dataType: String!): Boolean!
     */
    @Mutation
    public CompletableFuture<Boolean> deleteAllData(@Name("dataType") String dataType) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        String deleteQuery;
        switch (dataType.toLowerCase()) {
            case "conversations":
                deleteQuery = "DELETE FROM conversation WHERE ownerId = ?";
                break;
            case "messages":
                deleteQuery = """
                    DELETE m FROM message m
                    JOIN conversation c ON m.conversationId = c.conversationGUID
                    WHERE c.ownerId = ?
                    """;
                break;
            case "characters":
                deleteQuery = "DELETE FROM `character` WHERE ownerId = ?";
                break;
            case "attachments":
                deleteQuery = """
                    DELETE a FROM attachment a
                    JOIN message m ON a.messageId = m.messageUUID
                    JOIN conversation c ON m.conversationGUID = c.conversationGUID
                    WHERE c.ownerId = ?
                    """;
                break;
            case "all":
                // Delete all user data
                mysqlPool.withTransaction(sqlClient -> {
                    List<Future<?>> operations = new ArrayList<>();

                    operations.add(sqlClient.preparedQuery(
                        "DELETE m FROM message m JOIN conversation c ON m.conversationId = c.conversationGUID WHERE c.ownerId = ?")
                        .execute(Tuple.of(auth.getUserId().toString())));

                    operations.add(sqlClient.preparedQuery("DELETE FROM conversation WHERE ownerId = ?")
                        .execute(Tuple.of(auth.getUserId().toString())));

                    operations.add(sqlClient.preparedQuery("DELETE FROM `character` WHERE ownerId = ?")
                        .execute(Tuple.of(auth.getUserId().toString())));

                    return Future.all(operations);
                })
                .onSuccess(v -> future.complete(true))
                .onFailure(future::completeExceptionally);
                return future;
            default:
                return CompletableFuture.failedFuture(new RuntimeException("Invalid data type: " + dataType));
        }

        mysqlPool.preparedQuery(deleteQuery)
            .execute(Tuple.of(auth.getUserId().toString()))
            .onSuccess(v -> future.complete(true))
            .onFailure(future::completeExceptionally);

        return future;
    }

    /**
     * Send typing indicator.
     * Schema: sendTypingIndicator(conversationId: ID!, isTyping: Boolean!): Boolean!
     */
    @Mutation
    public CompletableFuture<Boolean> sendTypingIndicator(@Name("conversationId") String conversationIdStr, @Name("isTyping") Boolean isTyping) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        UUID conversationId = UUID.fromString(conversationIdStr);

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        // Verify the caller has access to this conversation before publishing a
        // typing indicator into it (prevents cross-tenant indicator injection).
        serviceHelper.verifyConversationAccess(conversationId.toString(), auth.getUserId().toString())
            .subscribe().with(hasAccess -> {
                if (!hasAccess) {
                    future.completeExceptionally(new RuntimeException("Not authenticated"));
                    return;
                }
                // Publish typing indicator to subscribers via EventBus
                JsonObject indicator = new JsonObject()
                    .put("userId", auth.getUserId().toString())
                    .put("username", auth.getUsername())
                    .put("conversationId", conversationId.toString())
                    .put("isTyping", isTyping);

                vertx.eventBus().publish("typing.indicator." + conversationId, indicator);
                future.complete(true);
            }, err -> future.completeExceptionally(err));
        return future;
    }

    /**
     * Helper to get default settings map.
     */
    private Map<String, Object> getDefaultSettingsMap() {
        Map<String, Object> settings = new HashMap<>();

        Map<String, Object> llm = new HashMap<>();
        llm.put("model", "default");
        llm.put("temperature", 0.7);
        llm.put("maxTokens", -1);  // -1 means unlimited
        llm.put("topP", 0.95);
        llm.put("topK", 40);
        llm.put("streamingEnabled", true);
        llm.put("contextSize", 8192);
        llm.put("templates", List.of("CHATML", "LLAMA3", "MISTRAL"));
        llm.put("currentTemplate", "CHATML");
        settings.put("llm", llm);

        Map<String, Object> ui = new HashMap<>();
        ui.put("theme", "light");
        ui.put("language", "en");
        ui.put("fontSize", "medium");
        ui.put("codeTheme", "prism");
        ui.put("editorTheme", "vs-dark");
        ui.put("showLineNumbers", true);
        ui.put("wordWrap", true);
        settings.put("ui", ui);

        Map<String, Object> notifications = new HashMap<>();
        notifications.put("enabled", true);
        notifications.put("sound", false);
        notifications.put("desktop", false);
        notifications.put("email", false);
        notifications.put("messageAlerts", true);
        notifications.put("systemAlerts", true);
        settings.put("notifications", notifications);

        Map<String, Object> privacy = new HashMap<>();
        privacy.put("analytics", false);
        privacy.put("shareData", false);
        privacy.put("publicProfile", false);
        privacy.put("showOnlineStatus", true);
        settings.put("privacy", privacy);

        Map<String, Object> keyboard = new HashMap<>();
        keyboard.put("shortcuts", new HashMap<>());
        settings.put("keyboard", keyboard);

        return settings;
    }

    /**
     * Convert Settings model to GraphQL Map format.
     */
    private Map<String, Object> convertSettingsToGraphQLMap(Settings settings) {
        Map<String, Object> result = new HashMap<>();

        // LLM Settings
        Map<String, Object> llm = new HashMap<>();
        Settings.LLMSettings llmSettings = settings.getLlm();
        llm.put("model", llmSettings.getModel() != null ? llmSettings.getModel() : "");
        llm.put("temperature", llmSettings.getTemperature());
        llm.put("maxTokens", llmSettings.getMaxTokens());
        llm.put("topP", llmSettings.getTopP());
        llm.put("topK", llmSettings.getTopK());
        llm.put("repeatPenalty", llmSettings.getRepeatPenalty());
        llm.put("presencePenalty", llmSettings.getPresencePenalty());
        llm.put("frequencyPenalty", llmSettings.getFrequencyPenalty());
        llm.put("minP", llmSettings.getMinP());
        llm.put("tfsZ", llmSettings.getTfsZ());
        llm.put("typicalP", llmSettings.getTypicalP());
        llm.put("seed", llmSettings.getSeed());
        llm.put("stopSequences", llmSettings.getStopSequences());
        llm.put("nPredict", llmSettings.getNPredict());
        llm.put("nCtx", llmSettings.getNCtx());
        llm.put("nBatch", llmSettings.getNBatch());
        llm.put("nThreads", llmSettings.getNThreads());
        llm.put("nGpuLayers", llmSettings.getNGpuLayers());
        llm.put("mainGpu", llmSettings.getMainGpu());
        llm.put("promptTemplate", llmSettings.getPromptTemplate());
        llm.put("systemPrompt", llmSettings.getSystemPrompt());
        llm.put("chatFormat", llmSettings.getChatFormat());
        llm.put("streamingEnabled", llmSettings.getStream());
        llm.put("mirostat", llmSettings.getMirostat());
        llm.put("mirostatTau", llmSettings.getMirostatTau());
        llm.put("mirostatEta", llmSettings.getMirostatEta());
        llm.put("contextSize", llmSettings.getContextSize());
        llm.put("templates", llmSettings.getTemplates());
        llm.put("currentTemplate", llmSettings.getCurrentTemplate());
        result.put("llm", llm);

        // UI Settings
        Map<String, Object> ui = new HashMap<>();
        Settings.UISettings uiSettings = settings.getUi();
        ui.put("theme", uiSettings.getTheme());
        ui.put("fontSize", uiSettings.getFontSize());
        ui.put("fontFamily", uiSettings.getFontFamily());
        ui.put("compactMode", uiSettings.getCompactMode());
        ui.put("showTimestamps", uiSettings.getShowTimestamps());
        ui.put("showTokenCount", uiSettings.getShowTokenCount());
        ui.put("showModelInfo", uiSettings.getShowModelInfo());
        ui.put("enterToSend", uiSettings.getEnterToSend());
        ui.put("soundEnabled", uiSettings.getSoundEnabled());
        ui.put("notificationsEnabled", uiSettings.getNotificationsEnabled());
        ui.put("autoScroll", uiSettings.getAutoScroll());
        ui.put("messageGrouping", uiSettings.getMessageGrouping());
        ui.put("syntaxHighlighting", uiSettings.getSyntaxHighlighting());
        ui.put("markdownRendering", uiSettings.getMarkdownRendering());
        ui.put("emojiPicker", uiSettings.getEmojiPicker());
        ui.put("spellCheck", uiSettings.getSpellCheck());
        ui.put("autoSave", uiSettings.getAutoSave());
        ui.put("autoSaveInterval", uiSettings.getAutoSaveInterval());
        ui.put("sidebarPosition", uiSettings.getSidebarPosition());
        ui.put("sidebarCollapsed", uiSettings.getSidebarCollapsed());
        ui.put("messageLayout", uiSettings.getMessageLayout());
        ui.put("language", uiSettings.getLanguage());
        ui.put("codeTheme", uiSettings.getCodeTheme());
        ui.put("editorTheme", uiSettings.getEditorTheme());
        ui.put("showLineNumbers", uiSettings.getShowLineNumbers());
        ui.put("wordWrap", uiSettings.getWordWrap());
        result.put("ui", ui);

        // Notification Settings
        Map<String, Object> notifications = new HashMap<>();
        Settings.NotificationSettings notifSettings = settings.getNotifications();
        notifications.put("desktop", notifSettings.getDesktop());
        notifications.put("sound", notifSettings.getSound());
        notifications.put("vibration", notifSettings.getVibration());
        notifications.put("mentions", notifSettings.getMentions());
        notifications.put("replies", notifSettings.getReplies());
        notifications.put("newConversations", notifSettings.getNewConversations());
        notifications.put("systemAlerts", notifSettings.getSystemAlerts());
        notifications.put("schedule", notifSettings.getSchedule());
        if (notifSettings.getCustomSchedule() != null) {
            Map<String, Object> customSchedule = new HashMap<>();
            customSchedule.put("startTime", notifSettings.getCustomSchedule().getStartTime());
            customSchedule.put("endTime", notifSettings.getCustomSchedule().getEndTime());
            customSchedule.put("daysOfWeek", notifSettings.getCustomSchedule().getDaysOfWeek());
            notifications.put("customSchedule", customSchedule);
        }
        notifications.put("enabled", notifSettings.getEnabled());
        notifications.put("email", notifSettings.getEmail());
        notifications.put("messageAlerts", notifSettings.getMessageAlerts());
        result.put("notifications", notifications);

        // Privacy Settings
        Map<String, Object> privacy = new HashMap<>();
        Settings.PrivacySettings privacySettings = settings.getPrivacy();
        privacy.put("shareAnalytics", privacySettings.getShareAnalytics());
        privacy.put("saveHistory", privacySettings.getSaveHistory());
        privacy.put("encryptMessages", privacySettings.getEncryptMessages());
        privacy.put("clearOnLogout", privacySettings.getClearOnLogout());
        privacy.put("exportFormat", privacySettings.getExportFormat());
        privacy.put("dataRetentionDays", privacySettings.getDataRetentionDays());
        privacy.put("analytics", privacySettings.getAnalytics());
        privacy.put("shareData", privacySettings.getShareData());
        privacy.put("publicProfile", privacySettings.getPublicProfile());
        privacy.put("showOnlineStatus", privacySettings.getShowOnlineStatus());
        result.put("privacy", privacy);

        // Keyboard Settings
        Map<String, Object> keyboard = new HashMap<>();
        keyboard.put("shortcuts", settings.getKeyboard().getShortcuts());
        result.put("keyboard", keyboard);

        return result;
    }

    /**
     * Send a system notification (admin only).
     * Publishes to all connected clients or a specific user.
     */
    @Mutation
    public CompletableFuture<Boolean> sendSystemNotification(
            @Name("type") String type,
            @Name("title") String title,
            @Name("message") String message,
            @Name("severity") String severity,
            @Name("userId") String userId) {
        AuthContext auth = graphQLAuthContext.requireAdmin();

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        try {
            JsonObject notification = new JsonObject()
                .put("type", type != null ? type : "system")
                .put("title", title != null ? title : "")
                .put("message", message != null ? message : "")
                .put("severity", severity != null ? severity : "info");

            if (userId != null && !userId.isEmpty()) {
                vertx.eventBus().publish("user:" + userId + ":notifications", notification);
            } else {
                vertx.eventBus().publish("system:notifications", notification);
            }

            future.complete(true);
        } catch (Exception e) {
            log.error("Failed to send system notification", e);
            future.completeExceptionally(e);
        }

        return future;
    }
}