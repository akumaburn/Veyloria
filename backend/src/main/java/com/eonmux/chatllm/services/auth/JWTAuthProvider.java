package com.eonmux.chatllm.services.auth;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.eonmux.chatllm.config.AppConfig;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.redis.client.RedisAPI;
import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * JWT authentication provider for stateless authentication.
 * Uses auth0 java-jwt library for token creation and verification.
 *
 * <p>{@code @Startup} makes the bean eager. Without it the container creates
 * the provider on the first login, so an unusable secret produced a running
 * server whose every login failed. The secret is now checked at boot.</p>
 */
@Startup
@ApplicationScoped
@Slf4j
public class JWTAuthProvider {

    /** Fallback used when JWT_SECRET is absent. Rejected outside dev mode. */
    static final String DEFAULT_SECRET = "change-this-secret-in-production";
    /** Fixed secret for the Quarkus dev profile, which must run without setup. */
    static final String DEVELOPMENT_SECRET = "development-secret-change-in-production";

    @Inject
    Vertx vertx;

    @Inject
    AppConfig config;

    @Inject
    RedisAPI redis;

    private Algorithm algorithm;
    private JWTVerifier verifier;
    private final SecureRandom secureRandom = new SecureRandom();
    private long localCacheCleanupTimerId = -1;
    private long redisSyncTimerId = -1;

    // Local in-memory cache of revoked token IDs (tokenId -> expiry epoch millis).
    // This is checked synchronously by validateToken() which cannot hit Redis.
    // Populated by revokeToken() in addition to the Redis blacklist.
    private final Map<String, Long> localRevocationCache = new ConcurrentHashMap<>();

    // Local in-memory cache of per-user global revocation timestamps
    // (userId -> revocation epoch millis). A token whose issuedAt predates the
    // timestamp is rejected. Populated by revokeAllUserTokens() and the periodic
    // Redis sync so the synchronous validateToken() path can enforce it.
    private final Map<String, Long> localUserRevocationCache = new ConcurrentHashMap<>();

    // Periodic sync from Redis to local revocation cache.
    // Mitigates multi-instance gaps where a token revoked on instance A
    // (written to Redis) is not caught by instance B's synchronous validateToken().
    private static final long REDIS_SYNC_INTERVAL_MS = 30_000; // 30 seconds

    @PostConstruct
    void init() {
        // Validate JWT secret strength before any token is signed.
        validateJwtSecret(config.getJwtSecret(), config.isDevelopmentMode());

        this.algorithm = Algorithm.HMAC256(config.getJwtSecret());
        this.verifier = JWT.require(algorithm)
            .withIssuer(config.getJwtIssuer())
            .acceptLeeway(5) // 5 seconds clock skew tolerance
            .build();

        // Periodic cleanup of expired local revocation entries (every 5 minutes)
        localCacheCleanupTimerId = vertx.setPeriodic(TimeUnit.MINUTES.toMillis(5), id -> {
            long now = System.currentTimeMillis();
            localRevocationCache.entrySet().removeIf(e -> e.getValue() < now);
        });

        // Periodic sync from Redis to local revocation cache.
        // This catches tokens that were revoked on other instances.
        redisSyncTimerId = vertx.setPeriodic(REDIS_SYNC_INTERVAL_MS, id -> syncRevocationCacheFromRedis());
    }

    @PreDestroy
    void destroy() {
        if (localCacheCleanupTimerId >= 0) {
            vertx.cancelTimer(localCacheCleanupTimerId);
        }
        if (redisSyncTimerId >= 0) {
            vertx.cancelTimer(redisSyncTimerId);
        }
    }

    /**
     * Periodically scan recently-revoked tokens from Redis and populate the
     * local cache so that validateToken() (synchronous, used by GraphQL) can
     * catch cross-instance revocations without blocking on Redis.
     */
    private void syncRevocationCacheFromRedis() {
        if (redis == null) return;
        // Loop the SCAN cursor to completion so a backlog of revoked tokens is
        // fully synced (previously only the first batch was read).
        scanAllRevokedTokens("0");
        // Also refresh per-user global revocation timestamps so the synchronous
        // validateToken() path can enforce revokeAllUserTokens().
        scanAllUserRevocations("0");
    }

    private void scanAllRevokedTokens(String cursor) {
        redis.scan(List.of(cursor, "MATCH", "token:blacklist:*", "COUNT", "100"))
            .onFailure().recoverWithNull()
            .subscribe().with(response -> {
                if (response == null) return;
                String nextCursor = response.get(0).toString();
                var keys = response.get(1);
                if (keys != null) {
                    for (int i = 0; i < keys.size(); i++) {
                        String key = keys.get(i).toString();
                        String tokenId = key.substring("token:blacklist:".length());
                        redis.ttl(key)
                            .onFailure().recoverWithNull()
                            .subscribe().with(ttl -> {
                                if (ttl != null && ttl.toLong() > 0) {
                                    localRevocationCache.put(tokenId,
                                        System.currentTimeMillis() + ttl.toLong() * 1000);
                                }
                            });
                    }
                }
                if (!"0".equals(nextCursor)) {
                    scanAllRevokedTokens(nextCursor);
                }
            });
    }

    private void scanAllUserRevocations(String cursor) {
        redis.scan(List.of(cursor, "MATCH", "user:revoked:*", "COUNT", "100"))
            .onFailure().recoverWithNull()
            .subscribe().with(response -> {
                if (response == null) return;
                String nextCursor = response.get(0).toString();
                var keys = response.get(1);
                if (keys != null) {
                    for (int i = 0; i < keys.size(); i++) {
                        String key = keys.get(i).toString();
                        String userId = key.substring("user:revoked:".length());
                        redis.get(key)
                            .onFailure().recoverWithNull()
                            .subscribe().with(val -> {
                                if (val != null) {
                                    try {
                                        localUserRevocationCache.put(userId, Long.parseLong(val.toString()));
                                    } catch (NumberFormatException ignored) { }
                                }
                            });
                    }
                }
                if (!"0".equals(nextCursor)) {
                    scanAllUserRevocations(nextCursor);
                }
            });
    }

    /**
     * Validate JWT secret strength. Runs at startup, so an unusable secret
     * stops the server instead of leaving every login to fail at runtime.
     */
    static void validateJwtSecret(String secret, boolean developmentMode) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException(
                "JWT secret must be at least 32 characters long. Set the JWT_SECRET environment variable.");
        }
        if (secret.equals(DEFAULT_SECRET)) {
            if (!developmentMode) {
                throw new IllegalArgumentException(
                    "The default JWT secret cannot be used outside development. "
                        + "Set the JWT_SECRET environment variable to a value of at least 32 characters.");
            }
            log.warn("WARNING: Using default JWT secret in development mode. Change this before deploying to production!");
        }
        if (secret.equals(DEVELOPMENT_SECRET) && !developmentMode) {
            throw new IllegalArgumentException(
                "The development JWT secret cannot be used outside development. "
                    + "Set the JWT_SECRET environment variable to a value of at least 32 characters.");
        }
    }

    /**
     * Generate access token for a user.
     */
    public String generateAccessToken(UUID userId, String username, String displayName, Set<String> roles) {
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + config.getJwtAccessTokenExpiry());

        return JWT.create()
            .withIssuer(config.getJwtIssuer())
            .withSubject(userId.toString())
            .withClaim("username", username)
            .withClaim("displayName", displayName != null ? displayName : username)
            .withClaim("roles", new ArrayList<>(roles))
            .withClaim("type", "access")
            .withIssuedAt(now)
            .withExpiresAt(expiresAt)
            .withJWTId(UUID.randomUUID().toString())
            .sign(algorithm);
    }

    /**
     * Generate refresh token for a user.
     */
    public String generateRefreshToken(UUID userId) {
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + config.getJwtRefreshTokenExpiry());

        return JWT.create()
            .withIssuer(config.getJwtIssuer())
            .withSubject(userId.toString())
            .withClaim("type", "refresh")
            .withIssuedAt(now)
            .withExpiresAt(expiresAt)
            .withJWTId(UUID.randomUUID().toString())
            .sign(algorithm);
    }

    /**
     * Verify and decode a token.
     */
    public Uni<DecodedJWT> verifyToken(String token) {
        try {
            DecodedJWT jwt = verifier.verify(token);

            // Check if token is blacklisted
            if (redis != null) {
                String tokenId = jwt.getId();
                String subject = jwt.getSubject();
                return checkUserRevocation(subject, jwt.getIssuedAt())
                    .chain(isUserRevoked -> {
                        if (isUserRevoked) {
                            log.warn("Attempt to use token after user-wide revocation: user={}", subject);
                            auditLog("USER_TOKEN_REVOKED_ATTEMPT", subject, tokenId);
                            return Uni.createFrom().failure(new AuthenticationException("Token has been revoked"));
                        }
                        return checkTokenBlacklist(tokenId);
                    })
                    .chain(isBlacklisted -> {
                        if (isBlacklisted) {
                            log.warn("Attempt to use blacklisted token: {}", tokenId);
                            auditLog("TOKEN_BLACKLISTED_ATTEMPT", jwt.getSubject(), tokenId);
                            return Uni.createFrom().failure(new AuthenticationException("Token has been revoked"));
                        }
                        return Uni.createFrom().item(jwt);
                    })
                    .onFailure().recoverWithUni(error -> {
                        if (error instanceof AuthenticationException) {
                            return Uni.createFrom().failure(error);
                        }
                        // Redis check failed (connection issue, pool exhaustion, etc.).
                        // Check local cache before making a decision.
                        log.error("CRITICAL: Redis blacklist check failed — connection issue or pool exhaustion? "
                            + "Falling back to local revocation cache for token {}", tokenId, error);
                        if (tokenId != null && isLocallyRevoked(tokenId)) {
                            return Uni.createFrom().failure(new AuthenticationException("Token has been revoked"));
                        }
                        // Fail-closed: if we can't verify the token isn't revoked, reject it
                        // to prevent attackers from bypassing revocation via Redis exhaustion.
                        log.warn("SECURITY: Token {} rejected because Redis blacklist check failed "
                            + "and token is not in local revocation cache — failing closed", tokenId);
                        return Uni.createFrom().failure(
                            new AuthenticationException("Token validation unavailable — try again"));
                    });
            }
            return Uni.createFrom().item(jwt);
        } catch (JWTVerificationException e) {
            log.debug("Token verification failed", e);
            auditLog("TOKEN_VERIFICATION_FAILED", null, "token-id-redacted");
            return Uni.createFrom().failure(new AuthenticationException("Invalid token"));
        }
    }

    /**
     * Check if a token ID is in the local revocation cache.
     * Entries are auto-evicted once their underlying JWT has expired.
     */
    private boolean isLocallyRevoked(String tokenId) {
        Long expiry = localRevocationCache.get(tokenId);
        if (expiry == null) return false;
        if (expiry < System.currentTimeMillis()) {
            localRevocationCache.remove(tokenId);
            return false;
        }
        return true;
    }

    /**
     * Synchronous check for per-user global revocation against the local cache.
     */
    private boolean isUserLocallyRevoked(String userId, Date issuedAt) {
        Long ts = localUserRevocationCache.get(userId);
        if (ts == null) return false;
        return issuedAt != null && ts > issuedAt.getTime();
    }

    /**
     * Check if token is blacklisted.
     */
    private Uni<Boolean> checkTokenBlacklist(String tokenId) {
        if (redis == null || tokenId == null) {
            return Uni.createFrom().item(false);
        }

        String key = "token:blacklist:" + tokenId;
        return redis.exists(List.of(key))
            .map(response -> response != null && response.toLong() > 0);
    }

    /**
     * Check whether a user has been globally revoked (revokeAllUserTokens).
     * A token is revoked if the user's revocation timestamp postdates the
     * token's issuedAt. Falls back to the local cache (and accepts the token
     * when Redis is unreachable, consistent with the blacklist fallback below).
     */
    private Uni<Boolean> checkUserRevocation(String userId, Date issuedAt) {
        if (userId == null) {
            return Uni.createFrom().item(false);
        }
        // Fast path: local cache (synchronous, always up to date for this instance).
        Long localTs = localUserRevocationCache.get(userId);
        if (localTs != null && issuedAt != null && localTs > issuedAt.getTime()) {
            return Uni.createFrom().item(true);
        }
        if (redis == null) {
            return Uni.createFrom().item(false);
        }
        String key = "user:revoked:" + userId;
        return redis.get(key)
            .map(response -> {
                if (response == null) return false;
                try {
                    long ts = Long.parseLong(response.toString());
                    localUserRevocationCache.put(userId, ts);
                    return issuedAt != null && ts > issuedAt.getTime();
                } catch (NumberFormatException e) {
                    return false;
                }
            });
    }

    /**
     * Revoke the access token described by an AuthContext by blacklisting its
     * tokenId. Used by logout() so the access token stops being valid before
     * its natural expiry. Does not require the raw token string.
     */
    public Uni<Void> revokeAccessToken(AuthContext auth) {
        if (auth == null || auth.getTokenId() == null || redis == null) {
            return Uni.createFrom().voidItem();
        }
        String tokenId = auth.getTokenId();
        long remaining = auth.getRemainingTime();
        if (remaining <= 0) {
            return Uni.createFrom().voidItem();
        }
        long expiryMs = System.currentTimeMillis() + remaining * 1000;
        localRevocationCache.put(tokenId, expiryMs);
        return redis.setex("token:blacklist:" + tokenId, String.valueOf(remaining), "revoked")
            .replaceWithVoid()
            .onFailure().recoverWithItem(err -> {
                log.warn("Failed to blacklist access token {} on logout: {}", tokenId, err.getMessage());
                return null;
            });
    }

    /**
     * Blacklist/revoke a token.
     */
    public Uni<Void> revokeToken(String token) {
        return verifyToken(token)
            .chain(jwt -> {
                String tokenId = jwt.getId();
                if (tokenId == null || redis == null) {
                    return Uni.createFrom().failure(new RuntimeException("Cannot revoke token without ID or Redis"));
                }

                // Calculate remaining TTL
                long ttl = (jwt.getExpiresAt().getTime() - System.currentTimeMillis()) / 1000;
                if (ttl <= 0) {
                    return Uni.createFrom().voidItem(); // Token already expired
                }

                String key = "token:blacklist:" + tokenId;
                return redis.setex(key, String.valueOf(ttl), "revoked")
                    .map(response -> {
                        // Also populate local cache for synchronous validateToken() checks
                        localRevocationCache.put(tokenId, jwt.getExpiresAt().getTime());
                        log.info("Token revoked: {}", tokenId);
                        auditLog("TOKEN_REVOKED", jwt.getSubject(), tokenId);
                        // Notify any active subscription sessions that this token has expired
                        vertx.eventBus().publish("session:" + tokenId + ":expired", true);
                        return null;
                    });
            });
    }

    /**
     * Revoke all tokens for a user.
     */
    public Uni<Void> revokeAllUserTokens(UUID userId) {
        if (redis == null) {
            return Uni.createFrom().failure(new RuntimeException("Redis not available for token revocation"));
        }

        // Store user revocation timestamp
        String key = "user:revoked:" + userId;
        long timestamp = System.currentTimeMillis();

        // Populate the local cache immediately so the synchronous validateToken()
        // path enforces this revocation on this instance without waiting for the
        // periodic Redis sync.
        localUserRevocationCache.put(userId.toString(), timestamp);

        return redis.setex(key, String.valueOf(TimeUnit.DAYS.toSeconds(7)), String.valueOf(timestamp))
            .map(response -> {
                log.info("All tokens revoked for user: {}", userId);
                auditLog("USER_TOKENS_REVOKED", userId.toString(), null);
                return null;
            });
    }

    /**
     * Extract user ID from token.
     */
    public Uni<UUID> getUserIdFromToken(String token) {
        return verifyToken(token)
            .map(jwt -> UUID.fromString(jwt.getSubject()));
    }

    /**
     * Extract username from token.
     */
    public Uni<String> getUsernameFromToken(String token) {
        return verifyToken(token)
            .map(jwt -> jwt.getClaim("username").asString());
    }

    /**
     * Extract roles from token.
     */
    public Uni<Set<String>> getRolesFromToken(String token) {
        return verifyToken(token)
            .map(jwt -> {
                List<String> roles = jwt.getClaim("roles").asList(String.class);
                return roles != null ? new HashSet<>(roles) : new HashSet<>();
            });
    }

    /**
     * Check if token is an access token.
     */
    public Uni<Boolean> isAccessToken(String token) {
        return verifyToken(token)
            .map(jwt -> "access".equals(jwt.getClaim("type").asString()));
    }

    /**
     * Check if token is a refresh token.
     */
    public Uni<Boolean> isRefreshToken(String token) {
        return verifyToken(token)
            .map(jwt -> "refresh".equals(jwt.getClaim("type").asString()));
    }

    /**
     * Get token expiration time.
     */
    public Uni<Instant> getTokenExpiration(String token) {
        return verifyToken(token)
            .map(jwt -> jwt.getExpiresAt().toInstant());
    }

    /**
     * Create auth context from token.
     */
    public Uni<AuthContext> createAuthContext(String token) {
        return verifyToken(token)
            .chain(jwt -> {
                AuthContext context = new AuthContext();
                context.setUserId(UUID.fromString(jwt.getSubject()));
                context.setUsername(jwt.getClaim("username").asString());
                context.setDisplayName(jwt.getClaim("displayName").asString());

                List<String> roles = jwt.getClaim("roles").asList(String.class);
                context.setRoles(roles != null ? new HashSet<>(roles) : new HashSet<>());

                context.setTokenId(jwt.getId());
                context.setIssuedAt(jwt.getIssuedAt().toInstant());
                context.setExpiresAt(jwt.getExpiresAt().toInstant());

                return Uni.createFrom().item(context);
            });
    }

    /**
     * Synchronously validate token and return AuthContext.
     * Used by GraphQL handler context setup.
     * Returns null if token is invalid.
     */
    public AuthContext validateToken(String token) {
        try {
            DecodedJWT jwt = verifier.verify(token);

            // Enforce token type: only "access" tokens are valid bearer
            // credentials for API requests. Accepting refresh tokens here would
            // let a leaked refresh token act as a full 4-hour session.
            if (!"access".equals(jwt.getClaim("type").asString())) {
                log.debug("Token validation failed: not an access token");
                return null;
            }

            // Check local revocation cache (populated synchronously by revokeToken)
            String tokenId = jwt.getId();
            if (tokenId != null && isLocallyRevoked(tokenId)) {
                log.warn("Attempt to use revoked token (local cache): {}", tokenId);
                auditLog("TOKEN_BLACKLISTED_ATTEMPT", jwt.getSubject(), tokenId);
                return null;
            }

            // Check per-user global revocation (revokeAllUserTokens). A token is
            // revoked if the user's revocation timestamp postdates issuedAt.
            String subject = jwt.getSubject();
            if (subject != null && isUserLocallyRevoked(subject, jwt.getIssuedAt())) {
                log.warn("Attempt to use token after user-wide revocation: user={}", subject);
                auditLog("USER_TOKEN_REVOKED_ATTEMPT", subject, tokenId);
                return null;
            }

            // Redis consistency check. verifyToken() (refresh/logout/revoke paths)
            // checks Redis and fails closed on connection errors; the GraphQL
            // request path is synchronous so it cannot await Redis. Use a
            // blocking client here so a token revoked on another instance is
            // rejected immediately instead of within the 30s sync window.
            // If Redis is unreachable we fall back to the local caches rather
            // than taking the whole API down (availability over strict
            // consistency, deliberately opposite to verifyToken's fail-closed
            // posture which only guards token-issuing operations).
            if (redis != null && tokenId != null) {
                try {
                    io.vertx.mutiny.redis.client.Response exists =
                            redis.exists(List.of("token:blacklist:" + tokenId)).await().indefinitely();
                    if (exists != null && exists.toLong() > 0) {
                        localRevocationCache.put(tokenId, jwt.getExpiresAt().getTime());
                        log.warn("Attempt to use revoked token (redis): {}", tokenId);
                        auditLog("TOKEN_BLACKLISTED_ATTEMPT", jwt.getSubject(), tokenId);
                        return null;
                    }
                } catch (Exception redisErr) {
                    log.debug("Redis blacklist check skipped during validateToken: {}", redisErr.getMessage());
                }
                try {
                    io.vertx.mutiny.redis.client.Response userRevoked =
                            redis.get("user:revoked:" + subject).await().indefinitely();
                    if (userRevoked != null) {
                        try {
                            long ts = Long.parseLong(userRevoked.toString());
                            localUserRevocationCache.put(subject, ts);
                            if (jwt.getIssuedAt() != null && ts > jwt.getIssuedAt().getTime()) {
                                log.warn("Attempt to use token after user-wide revocation (redis): user={}", subject);
                                auditLog("USER_TOKEN_REVOKED_ATTEMPT", subject, tokenId);
                                return null;
                            }
                        } catch (NumberFormatException ignored) { }
                    }
                } catch (Exception redisErr) {
                    log.debug("Redis user-revocation check skipped during validateToken: {}", redisErr.getMessage());
                }
            }

            AuthContext context = new AuthContext();
            context.setUserId(UUID.fromString(jwt.getSubject()));
            context.setUsername(jwt.getClaim("username").asString());
            context.setDisplayName(jwt.getClaim("displayName").asString());

            List<String> roles = jwt.getClaim("roles").asList(String.class);
            context.setRoles(roles != null ? new HashSet<>(roles) : new HashSet<>());

            context.setTokenId(jwt.getId());
            context.setIssuedAt(jwt.getIssuedAt().toInstant());
            context.setExpiresAt(jwt.getExpiresAt().toInstant());

            return context;
        } catch (JWTVerificationException e) {
            log.debug("Token validation failed: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Unexpected error during token validation: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Validate refresh token and return user ID.
     */
    public Uni<UUID> validateRefreshToken(String token) {
        return verifyToken(token)
            .chain(jwt -> {
                String tokenType = jwt.getClaim("type").asString();
                if (!"refresh".equals(tokenType)) {
                    return Uni.createFrom().failure(new AuthenticationException("Not a refresh token"));
                }
                return Uni.createFrom().item(UUID.fromString(jwt.getSubject()));
            });
    }

    /**
     * Validate access token and return user ID.
     */
    public Uni<UUID> validateAccessToken(String token) {
        return verifyToken(token)
            .chain(jwt -> {
                String tokenType = jwt.getClaim("type").asString();
                if (!"access".equals(tokenType)) {
                    return Uni.createFrom().failure(new AuthenticationException("Not an access token"));
                }
                return Uni.createFrom().item(UUID.fromString(jwt.getSubject()));
            });
    }

    /**
     * Generate tokens response.
     */
    public JsonObject generateTokensResponse(UUID userId, String username, String displayName, Set<String> roles) {
        String accessToken = generateAccessToken(userId, username, displayName, roles);
        String refreshToken = generateRefreshToken(userId);

        // Audit successful token generation
        auditLog("TOKEN_GENERATED", userId.toString(), null);

        return new JsonObject()
            .put("accessToken", accessToken)
            .put("refreshToken", refreshToken)
            .put("expiresIn", config.getJwtAccessTokenExpiry() / 1000) // in seconds
            .put("tokenType", "Bearer");
    }

    /**
     * Audit log security events.
     */
    private void auditLog(String event, String userId, String details) {
        JsonObject auditEntry = new JsonObject()
            .put("timestamp", Instant.now().toString())
            .put("event", event)
            .put("userId", userId)
            .put("details", details);

        // Send to audit logging system
        vertx.eventBus().send("audit.security", auditEntry);

        // Also log to file
        log.info("SECURITY_AUDIT: {}", auditEntry.encode());
    }

    /**
     * Authentication exception.
     */
    public static class AuthenticationException extends Exception {
        public AuthenticationException(String message) {
            super(message);
        }

        public AuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Authorization exception.
     */
    public static class AuthorizationException extends Exception {
        public AuthorizationException(String message) {
            super(message);
        }
    }
}
