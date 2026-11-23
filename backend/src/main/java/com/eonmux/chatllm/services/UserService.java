package com.eonmux.chatllm.services;

import jakarta.annotation.PostConstruct;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.utils.DisplayNameFormatter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import at.favre.lib.crypto.bcrypt.BCrypt;
import com.eonmux.chatllm.models.User;
import com.eonmux.chatllm.models.UserSettings;
import com.eonmux.chatllm.services.auth.JWTAuthProvider;
import com.eonmux.chatllm.utils.DynamicUpdateBuilder;
import com.eonmux.chatllm.utils.ServiceHelper;
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
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Service for user-related operations.
 * Handles user registration, authentication, profile management, and settings.
 */
@ApplicationScoped
@Slf4j
public class UserService {
    
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[A-Za-z0-9+_.-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})$"
    );
    
    private static final int BCRYPT_ROUNDS = 12;
    private static final int MIN_PASSWORD_LENGTH = 8;
    
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    @Inject
    RedisAPI redis;
    @Inject
    JWTAuthProvider jwtProvider;
    // EventBus obtained from vertx.eventBus()
    @Inject
    ServiceHelper serviceHelper;

    
    @PostConstruct
    void init() {
    }

    /**
     * Register a new user.
     */
    public Uni<User> registerUser(String email, String password, String displayName) {
        
        // Validate input
        if (!isValidEmail(email)) {
            return Uni.createFrom().failure(new RuntimeException("Invalid email format"));
        }
        
        if (!isValidPassword(password)) {
            return Uni.createFrom().failure(new RuntimeException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
        }
        
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = email.split("@")[0];
        }
        
        final String finalDisplayName = DisplayNameFormatter.normalize(displayName);
        
        // Check if email already exists
        return checkEmailExists(email)
            .chain(exists -> {
                if (exists) {
                    return Uni.createFrom().failure(new RuntimeException("Email already registered"));
                }

                // Hash password on worker thread (BCrypt is CPU-bound)
                return hashPassword(password).chain(hashedPassword -> {
                // Generate user UUID
                String userUUID = UUID.randomUUID().toString();
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

                // Insert user
                String insertQuery = """
                    INSERT INTO user (
                        userUUID, email, displayName, passwordHash,
                        admin, createdAt, lastLogin, emailVerified
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;

                return pool.preparedQuery(insertQuery)
                    .execute(Tuple.tuple()
                        .addValue(userUUID)
                        .addValue(email.toLowerCase())
                        .addValue(finalDisplayName)
                        .addValue(hashedPassword)
                        .addValue(false)
                        .addValue(now)
                        .addValue(now)
                        .addValue(false))
                    .map(rows -> {
                        User user = new User();
                        user.setId(UUID.fromString(userUUID));
                        user.setEmail(email.toLowerCase());
                        user.setDisplayName(finalDisplayName);
                        user.setAdmin(false);
                        user.setCreatedAt(now.toString());
                        user.setLastLogin(now.toString());
                        user.setEmailVerified(false);
                        return user;
                    });
                });
            })
            .chain(user -> {
                // Create default settings
                return createDefaultSettings(user.getId())
                    .map(settings -> {
                        user.setSettings(settings);
                        return user;
                    });
            })
            .map(user -> {
                // Send welcome email (async)
                sendWelcomeEmail(user);

                // Track registration metric using ServiceHelper to reduce duplication
                serviceHelper.trackMetric("metrics.user.registered", new JsonObject()
                    .put("userId", user.getId().toString()));

                return user;
            })

            .onFailure().invoke(error -> {
                log.error("Failed to register user: {}", email, error);
            });
    }
    
    /**
     * Authenticate user and generate tokens.
     * Rate limiting is handled at the endpoint level (MainVerticle/MutationResolver).
     */
    public Uni<JsonObject> authenticateUser(String email, String password) {

        if (email == null || password == null) {
            return Uni.createFrom().failure(new RuntimeException("Email and password are required"));
        }

        // Dummy hash for timing-safe comparison when user not found
        // This prevents username enumeration via timing attacks
        final String DUMMY_HASH = "$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/X4.VTtYw.VNjGsWKe";

        return getUserByEmail(email)
            .chain(user -> {
                String storedHash;
                boolean userExists = user != null;

                if (userExists) {
                    storedHash = user.getString("password");
                } else {
                    // Use dummy hash to prevent timing attacks
                    storedHash = DUMMY_HASH;
                }

                // Always verify password (even with dummy hash) to maintain constant timing
                return verifyPassword(password, storedHash).flatMap(passwordValid -> {

                // Now check if user exists and password is correct
                if (!userExists || !passwordValid) {
                    return Uni.createFrom().failure(new RuntimeException("Invalid credentials"));
                }

                // Check if email is verified (optional)
                if (!user.getBoolean("emailVerified", false)) {
                    log.warn("User {} logged in with unverified email", email);
                }

                // Update last login
                updateLastLogin(user.getString("userUUID"));

                // Generate tokens
                String userId = user.getString("userUUID");
                String username = user.getString("username");
                String displayName = user.getString("displayName", username);
                boolean isAdmin = user.getBoolean("isAdmin", false);

                Set<String> roles = new HashSet<>();
                roles.add("USER");
                if (isAdmin) {
                    roles.add("ADMIN");
                }

                JsonObject tokens = jwtProvider.generateTokensResponse(UUID.fromString(userId), username, displayName, roles);

                // Store refresh token in Redis
                storeRefreshToken(userId, tokens.getString("refreshToken"));

                // Create session data
                JsonObject session = new JsonObject()
                    .put("userId", userId)
                    .put("email", email)
                    .put("displayName", displayName)
                    .put("isAdmin", isAdmin)
                    .put("loginTime", LocalDateTime.now(ZoneOffset.UTC).toString());

                // Store session in Redis
                String sessionId = UUID.randomUUID().toString();
                storeSession(sessionId, session);

                // Track login metric using ServiceHelper to reduce duplication
                serviceHelper.trackMetric("metrics.auth.success", new JsonObject()
                    .put("userId", userId));

                return Uni.createFrom().item(new JsonObject()
                    .put("user", new JsonObject()
                        .put("id", userId)
                        .put("email", email)
                        .put("displayName", displayName)
                        .put("isAdmin", isAdmin)
                        .put("emailVerified", user.getBoolean("emailVerified", false)))
                    .put("accessToken", tokens.getString("accessToken"))
                    .put("refreshToken", tokens.getString("refreshToken"))
                    .put("sessionId", sessionId)
                    .put("expiresIn", tokens.getInteger("expiresIn")));
                });
            })
            
            .onFailure().invoke(error -> {
                log.debug("Authentication failed for: {}", email);

                // Track failed login metric using ServiceHelper to reduce duplication
                serviceHelper.trackMetric("metrics.auth.failure", new JsonObject()
                    .put("email", email));
            });
    }
    
    /**
     * Get user by ID.
     */
    public Uni<User> getUserById(String userId) {
        
        String query = """
            SELECT u.*, s.*
            FROM user u
            LEFT JOIN user_settings s ON u.userUUID = s.fk_userUUID
            WHERE u.userUUID = ?
            """;
        
        return pool.preparedQuery(query)
            .execute(Tuple.of(userId))
            .chain(rows -> {
                if (rows.size() == 0) {
                    return Uni.createFrom().failure(new RuntimeException("User not found"));
                } else {
                    Row row = rows.iterator().next();
                    User user = mapRowToUser(row);
                    return Uni.createFrom().item(user);
                }
            })
            ;
    }
    
    /**
     * Update user profile.
     */
    public Uni<User> updateProfile(String userId, String displayName, String email) {
        
        // Validate email if provided
        if (email != null && !isValidEmail(email)) {
            return Uni.createFrom().failure(new RuntimeException("Invalid email format"));
        }
        
        // Build update query using DynamicUpdateBuilder
        DynamicUpdateBuilder builder = new DynamicUpdateBuilder("user");

        if (displayName != null && !displayName.trim().isEmpty()) {
            builder.setString("displayName", DisplayNameFormatter.normalize(displayName));
        }

        if (email != null) {
            builder.setString("email", email.toLowerCase())
                   .setBoolean("emailVerified", false);
        }

        // Build query
        DynamicUpdateBuilder.UpdateQuery query = builder.build("WHERE userUUID = ?", userId);

        if (!query.hasUpdates()) {
            return Uni.createFrom().failure(new RuntimeException("No updates provided"));
        }

        return pool.preparedQuery(query.sql())
            .execute(query.params())
            .chain(result -> {
                if (result.rowCount() == 0) {
                    return Uni.createFrom().failure(new RuntimeException("User not found"));
                }
                
                // Invalidate user cache
                invalidateUserCache(userId);
                
                // Get updated user
                return getUserById(userId);
            })
            
            ;
    }
    
    /**
     * Change user password.
     */
    public Uni<Void> changePassword(String userId, String currentPassword, String newPassword) {
        
        if (!isValidPassword(newPassword)) {
            return Uni.createFrom().failure(new RuntimeException("New password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
        }
        
        // Get current password hash
        String query = "SELECT passwordHash FROM user WHERE userUUID = ?";
        
        return pool.preparedQuery(query)
            .execute(Tuple.of(userId))
            .chain(rows -> {
                if (rows.size() == 0) {
                    return Uni.createFrom().failure(new RuntimeException("User not found"));
                }
                
                String currentHash = rows.iterator().next().getString("passwordHash");

                // Verify current password and hash new one on worker thread (BCrypt is CPU-bound)
                final String oldPass = currentPassword;
                final String newPass = newPassword;
                return verifyPassword(oldPass, currentHash).flatMap(passwordValid -> {
                if (!passwordValid) {
                    return Uni.createFrom().failure(new RuntimeException("Current password is incorrect"));
                }

                return hashPassword(newPass).flatMap(newHash -> {
                // Update password
                String updateQuery = """
                    UPDATE user
                    SET passwordHash = ?, updatedAt = ?
                    WHERE userUUID = ?
                    """;

                return pool.preparedQuery(updateQuery)
                    .execute(Tuple.of(newHash, LocalDateTime.now(ZoneOffset.UTC), userId));
                });
                });
            })
            .replaceWithVoid()
            .invoke(v -> {
                // Invalidate all sessions for this user
                invalidateUserSessions(userId);

                // Send password change notification
                sendPasswordChangeNotification(userId);
            })

            ;
    }
    
    /**
     * Delete user account.
     */
    public Uni<Void> deleteAccount(String userId) {
        
        // Start transaction
        return pool.withTransaction(client -> {
            // Delete in correct order due to foreign key constraints
            // Note: Most deletes will cascade via ON DELETE CASCADE, but we do it explicitly for safety

            // 1. Delete message edits
            return client.preparedQuery("DELETE FROM message_edit WHERE messageId IN (SELECT messageUUID FROM message WHERE ownerId = ?)")
                .execute(Tuple.of(userId))
                .chain(v -> {
                    // 2. Delete messages
                    return client.preparedQuery("DELETE FROM message WHERE ownerId = ?")
                        .execute(Tuple.of(userId));
                })
                .chain(v -> {
                    // 3. Delete conversations
                    return client.preparedQuery("DELETE FROM conversation WHERE ownerId = ?")
                        .execute(Tuple.of(userId));
                })
                .chain(v -> {
                    // 4. Delete characters
                    return client.preparedQuery("DELETE FROM `character` WHERE ownerId = ?")
                        .execute(Tuple.of(userId));
                })
                .chain(v -> {
                    // 5. Delete settings
                    return client.preparedQuery("DELETE FROM user_settings WHERE fk_userUUID = ?")
                        .execute(Tuple.of(userId));
                })
                .chain(v -> {
                    // 6. Delete sessions
                    return client.preparedQuery("DELETE FROM session WHERE userId = ?")
                        .execute(Tuple.of(userId));
                })
                .chain(v -> {
                    // 7. Finally delete user
                    return client.preparedQuery("DELETE FROM user WHERE userUUID = ?")
                        .execute(Tuple.of(userId));
                })
                .replaceWithVoid();
        })
        .invoke(v -> {
            // Clean up Redis data
            invalidateUserCache(userId);
            invalidateUserSessions(userId);

            // Track deletion metric using ServiceHelper to reduce duplication
            serviceHelper.trackMetric("metrics.user.deleted", new JsonObject()
                .put("userId", userId));
        })
        .invoke(v -> {
            log.info("User account deleted: {}", userId);
        })
        .onFailure().invoke(error -> {
            log.error("Failed to delete user account: {}", userId, error);
        });
    }
    
    /**
     * Get user statistics.
     */
    public Uni<JsonObject> getUserStats(String userId) {
        
        String query = """
            SELECT
                (SELECT COUNT(*) FROM conversation WHERE ownerId = ?) as conversationCount,
                (SELECT COUNT(*) FROM message WHERE ownerId = ?) as messageCount,
                (SELECT COUNT(*) FROM `character` WHERE ownerId = ?) as characterCount,
                (SELECT COUNT(*) FROM message WHERE ownerId = ? AND createdAt >= DATE_SUB(NOW(), INTERVAL 7 DAY)) as messagesLastWeek,
                (SELECT MAX(createdAt) FROM message WHERE ownerId = ?) as lastMessageDate,
                (SELECT SUM(LENGTH(content)) FROM message WHERE ownerId = ?) as totalCharacters
            """;
        
        return pool.preparedQuery(query)
            .execute(Tuple.of(userId, userId, userId, userId, userId, userId))
            .map(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    return new JsonObject()
                        .put("conversationCount", row.getInteger(0))
                        .put("messageCount", row.getInteger(1))
                        .put("characterCount", row.getInteger(2))
                        .put("messagesLastWeek", row.getInteger(3))
                        .put("lastMessageDate", row.getLocalDateTime(4) != null ?
                            Time4JHelper.localDateTimeToString(row.getLocalDateTime(4)) : null)
                        .put("totalCharacters", row.getLong(5));
                } else {
                    return new JsonObject();
                }
            })
            ;
    }
    
    /**
     * Search users (admin only).
     */
    public Uni<JsonArray> searchUsers(String query, int limit, int offset) {
        
        String searchQuery = """
            SELECT userUUID, email, displayName, admin, createdAt, lastLogin, emailVerified
            FROM user
            WHERE email LIKE ? OR displayName LIKE ?
            ORDER BY createdAt DESC
            LIMIT ? OFFSET ?
            """;
        
        String searchPattern = "%" + query + "%";
        
        return pool.preparedQuery(searchQuery)
            .execute(Tuple.of(searchPattern, searchPattern, limit, offset))
            .map(rows -> {
                JsonArray users = new JsonArray();
                rows.forEach(row -> {
                    users.add(new JsonObject()
                        .put("id", row.getString("userUUID"))
                        .put("email", row.getString("email"))
                        .put("displayName", row.getString("displayName"))
                        .put("isAdmin", row.getBoolean("admin"))
                        .put("created", Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                        .put("lastLogin", row.getLocalDateTime("lastLogin") != null ?
                            Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastLogin")) : null)
                        .put("emailVerified", row.getBoolean("emailVerified")));
                });
                return users;
            })
            ;
    }
    
    // Helper methods
    
    private boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }
    
    private boolean isValidPassword(String password) {
        return password != null && password.length() >= MIN_PASSWORD_LENGTH;
    }
    
    private Uni<String> hashPassword(String password) {
        return vertx.executeBlocking(() ->
            BCrypt.withDefaults().hashToString(BCRYPT_ROUNDS, password.toCharArray()));
    }

    private Uni<Boolean> verifyPassword(String password, String hash) {
        if (password == null || hash == null) {
            return Uni.createFrom().item(false);
        }
        return vertx.executeBlocking(() ->
            BCrypt.verifyer().verify(password.toCharArray(), hash).verified);
    }
    
    // Helper method replaced with ServiceHelper to reduce duplication
    private Uni<Boolean> checkEmailExists(String email) {
        return serviceHelper.count("user", "WHERE email = ?", Tuple.of(email.toLowerCase()))
            .map(count -> count > 0)
            .onFailure().recoverWithUni(error -> Uni.createFrom().item(false));
    }
    
    private Uni<JsonObject> getUserByEmail(String email) {
        
        String query = """
            SELECT userUUID, username, email, displayName, passwordHash, admin, emailVerified
            FROM user
            WHERE email = ?
            """;

        return pool.preparedQuery(query)
            .execute(Tuple.of(email.toLowerCase()))
            .map(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    JsonObject user = new JsonObject()
                        .put("userUUID", row.getString("userUUID"))
                        .put("username", row.getString("username"))
                        .put("email", row.getString("email"))
                        .put("displayName", row.getString("displayName"))
                        .put("password", row.getString("passwordHash"))
                        .put("isAdmin", row.getBoolean("admin"))
                        .put("emailVerified", row.getBoolean("emailVerified"));
                    return user;
                } else {
                    return null;
                }
            })
            .onFailure().invoke(error -> log.error("Database error looking up user by email", error))
            .onFailure().recoverWithItem((JsonObject) null);
    }
    
    private Uni<UserSettings> createDefaultSettings(UUID userId) {
        
        UserSettings settings = new UserSettings();
        settings.setUserId(userId.toString());
        settings.setTheme("light");
        settings.setLanguage("en");
        settings.setLlmTemperature(0.7f);
        settings.setLlmMaxTokens(-1);  // -1 means unlimited
        settings.setLlmTopP(0.9f);
        settings.setLlmTopK(40);
        settings.setLlmContextSize(4096);
        settings.setNotificationsEnabled(true);
        settings.setSoundEnabled(true);
        
        String insertQuery = """
            INSERT INTO user_settings (
                fk_userUUID, theme, language, llmTemperature, llmMaxTokens,
                llmTopP, llmTopK, llmContextSize, notificationsEnabled, soundEnabled
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        
        return pool.preparedQuery(insertQuery)
            .execute(Tuple.tuple()
                .addValue(userId.toString())
                .addValue(settings.getTheme())
                .addValue(settings.getLanguage())
                .addValue(settings.getLlmTemperature())
                .addValue(settings.getLlmMaxTokens())
                .addValue(settings.getLlmTopP())
                .addValue(settings.getLlmTopK())
                .addValue(settings.getLlmContextSize())
                .addValue(settings.isNotificationsEnabled())
                .addValue(settings.isSoundEnabled()))
            .map(v -> settings)
            .onFailure().recoverWithItem(error -> {
                log.error("Failed to create default settings for user: {}", userId, error);
                return settings;
            });
    }
    
    private void updateLastLogin(String userId) {
        String updateQuery = "UPDATE user SET lastLogin = ? WHERE userUUID = ?";

        pool.preparedQuery(updateQuery)
            .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC), userId))
            .onFailure().invoke(error -> log.error("Failed to update last login for user: {}", userId, error))
            .subscribe().with(v -> {}, err -> {});
    }
    
    private void storeRefreshToken(String userId, String refreshToken) {
        String key = "refresh_token:" + userId;
        vertx.eventBus().send("redis.set", new JsonObject()
            .put("key", key)
            .put("value", refreshToken)
            .put("ttl", 604800)); // 7 days
    }
    
    private void storeSession(String sessionId, JsonObject session) {
        vertx.eventBus().send("redis.session.set", new JsonObject()
            .put("sessionId", sessionId)
            .put("session", session)
            .put("ttl", 3600)); // 1 hour
    }
    
    private void invalidateUserCache(String userId) {
        String pattern = "user:" + userId + ":*";
        vertx.eventBus().send("redis.cache.invalidate", new JsonObject()
            .put("pattern", pattern));
    }
    
    private void invalidateUserSessions(String userId) {
        // Invalidate refresh token for this user
        String refreshTokenKey = "refresh_token:" + userId;
        vertx.eventBus().send("redis.delete", new JsonObject().put("key", refreshTokenKey));

        // Revoke all tokens for this user via JWTAuthProvider
        if (jwtProvider != null) {
            jwtProvider.revokeAllUserTokens(UUID.fromString(userId))
                .onFailure().invoke(error -> log.warn("Failed to revoke all tokens for user: {}", userId, error))
                .subscribe().with(v -> {}, err -> {});
        }

        // Delete sessions from database
        String deleteQuery = "DELETE FROM session WHERE userId = ?";
        pool.preparedQuery(deleteQuery)
            .execute(Tuple.of(userId))
            .onFailure().invoke(error -> log.warn("Failed to delete sessions for user: {}", userId, error))
            .subscribe().with(v -> {}, err -> {});
    }
    
    private void sendWelcomeEmail(User user) {
        // Async email sending
        vertx.executeBlocking(() -> {
            // Email sending logic would go here
            log.info("Welcome email queued for: {}", user.getEmail());
            return null;
        }).subscribe().with(v -> {}, err -> {});
    }

    private void sendPasswordChangeNotification(String userId) {
        // Async notification
        vertx.executeBlocking(() -> {
            // Notification logic would go here
            log.info("Password change notification sent for user: {}", userId);
            return null;
        }).subscribe().with(v -> {}, err -> {});
    }
    
    private User mapRowToUser(Row row) {
        User user = new User();
        user.setId(UUID.fromString(row.getString("userUUID")));
        user.setEmail(row.getString("email"));
        user.setDisplayName(row.getString("displayName"));
        user.setAdmin(row.getBoolean("admin"));
        user.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        user.setLastLogin(Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastLogin")));
        user.setEmailVerified(row.getBoolean("emailVerified"));
        
        // Map settings if present
        if (row.getString("theme") != null) {
            UserSettings settings = new UserSettings();
            settings.setUserId(user.getId().toString());
            settings.setTheme(row.getString("theme"));
            settings.setLanguage(row.getString("language"));
            settings.setLlmTemperature(row.getFloat("llmTemperature"));
            settings.setLlmMaxTokens(row.getInteger("llmMaxTokens"));
            settings.setLlmTopP(row.getFloat("llmTopP"));
            settings.setLlmTopK(row.getInteger("llmTopK"));
            settings.setLlmContextSize(row.getInteger("llmContextSize"));
            settings.setNotificationsEnabled(row.getBoolean("notificationsEnabled"));
            settings.setSoundEnabled(row.getBoolean("soundEnabled"));
            user.setSettings(settings);
        }
        
        return user;
    }
}