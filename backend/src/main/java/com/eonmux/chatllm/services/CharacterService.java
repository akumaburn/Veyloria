package com.eonmux.chatllm.services;

import jakarta.annotation.PostConstruct;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.utils.CacheHelper;
import com.eonmux.chatllm.utils.DynamicUpdateBuilder;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for character-related operations.
 * Handles character creation, management, and AI generation.
 *
 * Uses CacheHelper, ServiceHelper, and ValidationHelper to reduce code duplication.
 */
@ApplicationScoped
@Slf4j
public class CharacterService {

    private static final int CACHE_TTL = 600; // 10 minutes

    // Default system prompt for characters without one
    private static final String DEFAULT_ASSISTANT_PROMPT =
        "You are a helpful AI assistant. Be informative, accurate, and friendly.";

    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    @Inject
    RedisAPI redis;
    // EventBus obtained from vertx.eventBus()

    // Utility helpers to reduce code duplication
    private CacheHelper<Character> cacheHelper;
    @Inject
    ServiceHelper serviceHelper;


    @PostConstruct
    void init() {
        this.cacheHelper = new CacheHelper<>(vertx.eventBus(), "character", CACHE_TTL, Character.class);
    }

    /**
     * Create a new character.
     */
    public Uni<Character> createCharacter(String userId, String name, String description,
                                            String systemPrompt, String initialPrompt,
                                            JsonObject metadata, boolean isPublic) {

        // Validate input
        if (name == null || name.trim().isEmpty()) {
            return Uni.createFrom().failure(new RuntimeException("Character name is required"));
    }

        // Truncate strings to max lengths using ValidationHelper
        name = ValidationHelper.truncate(name, ValidationHelper.MAX_NAME_LENGTH);
        description = ValidationHelper.truncate(description, ValidationHelper.MAX_DESCRIPTION_LENGTH);
        systemPrompt = ValidationHelper.truncate(systemPrompt, ValidationHelper.MAX_SYSTEM_PROMPT_LENGTH);
        initialPrompt = ValidationHelper.truncate(initialPrompt, ValidationHelper.MAX_INITIAL_PROMPT_LENGTH);
        
        // Set defaults
        if (systemPrompt == null || systemPrompt.trim().isEmpty()) {
            systemPrompt = DEFAULT_ASSISTANT_PROMPT;
        }
        
        if (initialPrompt == null || initialPrompt.trim().isEmpty()) {
            initialPrompt = "Hello! I'm " + name + ". How can I help you today?";
        }
        
        final String finalName = name;
        final String finalDescription = description;
        final String finalSystemPrompt = systemPrompt;
        final String finalInitialPrompt = initialPrompt;
        
        String characterId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        
        // Check for duplicate name for user
        return checkDuplicateName(userId, finalName)
            .chain(exists -> {
                if (exists) {
                    return Uni.createFrom().failure(new RuntimeException("Character with this name already exists"));
                }
                
                String insertQuery = """
                    INSERT INTO `character` (
                        characterUUID, ownerId, name, description, systemPrompt,
                        greeting, metadata, isPublic, createdAt, updatedAt,
                        usageCount, rating
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
                
                return pool.preparedQuery(insertQuery)
                    .execute(Tuple.tuple()
                        .addValue(characterId)
                        .addValue(userId)
                        .addValue(finalName)
                        .addValue(finalDescription)
                        .addValue(finalSystemPrompt)
                        .addValue(finalInitialPrompt)
                        .addValue(metadata != null ? metadata.encode() : null)
                        .addValue(isPublic)
                        .addValue(now)
                        .addValue(now)
                        .addValue(0)
                        .addValue(0.0)
                    );
            })
            .chain(result -> {
                Character character = new Character();
                character.setId(UUID.fromString(characterId));
                character.setUserId(UUID.fromString(userId));
                character.setName(finalName);
                character.setDescription(finalDescription);
                character.setSystemPrompt(finalSystemPrompt);
                character.setGreeting(finalInitialPrompt);
                character.setMetadata(metadata);
                character.setPublic(isPublic);
                character.setCreatedAt(now.toString());
                character.setLastModified(now.toString());
                character.setUsageCount(0);
                character.setRating(0.0);

                // Track metric using ServiceHelper to reduce duplication
                serviceHelper.trackMetric("metrics.character.created", new JsonObject()
                    .put("userId", userId)
                    .put("characterId", characterId)
                    .put("isPublic", isPublic));
                
                return Uni.createFrom().item(character);
            })
            
            .onFailure().invoke(error -> log.error("Failed to create character for user: {}", userId, error));
    }

    /**
     * Generate character using AI.
     */
    public Uni<Character> generateCharacter(String userId, String prompt, Float temperature) {
        
        if (prompt == null || prompt.trim().isEmpty()) {
            return Uni.createFrom().failure(new RuntimeException("Generation prompt is required"));
    }
        
        // Set default temperature if not provided
        final Float finalTemperature = (temperature != null) ? temperature : 0.7f;
        
        // Request character generation from LLM (can take longer than default 30s timeout)
        JsonObject llmRequest = new JsonObject()
            .put("userId", userId)
            .put("prompt", prompt)
            .put("temperature", finalTemperature);

        DeliveryOptions deliveryOptions = new DeliveryOptions().setSendTimeout(120000);
        return vertx.eventBus().<JsonObject>request("llm.generate.character", llmRequest, deliveryOptions)
            .chain(reply -> {
                JsonObject generated = reply.body();

                // Extract ALL generated character details
                String name = generated.getString("name", "Generated Character");
                String description = generated.getString("description", "An AI-generated character");
                String systemPrompt = generated.getString("systemPrompt", DEFAULT_ASSISTANT_PROMPT);
                // LLMVerticle generates "greeting" field, not "initialPrompt"
                String initialPrompt = generated.getString("greeting", "Hello!");

                // Extract additional character fields
                String personality = generated.getString("personality");
                String appearance = generated.getString("appearance");
                String backstory = generated.getString("backstory");
                String goals = generated.getString("goals");
                String likes = generated.getString("likes");
                String dislikes = generated.getString("dislikes");

                // Add generation metadata
                JsonObject metadata = new JsonObject()
                    .put("generatedBy", "AI")
                    .put("generationPrompt", prompt)
                    .put("temperature", finalTemperature)
                    .put("generatedAt", LocalDateTime.now(ZoneOffset.UTC).toString());

                // Create the character with basic fields
                return createCharacter(userId, name, description, systemPrompt,
                                     initialPrompt, metadata, false)
                    .chain(character -> {
                        // Update with additional fields if they exist
                        String updateQuery = """
                            UPDATE `character`
                            SET personality = ?, appearance = ?, backstory = ?,
                                goals = ?, likes = ?, dislikes = ?,
                                updatedAt = ?
                            WHERE characterUUID = ?
                            """;

                        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                        return pool.preparedQuery(updateQuery)
                            .execute(Tuple.tuple()
                                .addValue(personality)
                                .addValue(appearance)
                                .addValue(backstory)
                                .addValue(goals)
                                .addValue(likes)
                                .addValue(dislikes)
                                .addValue(now)
                                .addValue(character.getId().toString())
                            )
                            .map(result -> {
                                // Update the character object with the new fields
                                character.setPersonality(personality);
                                character.setAppearance(appearance);
                                character.setBackstory(backstory);
                                character.setGoals(goals);
                                character.setLikes(likes);
                                character.setDislikes(dislikes);
                                character.setUpdatedAt(now.toString());
                                return character;
                            });
                    });
            })
            .invoke(character -> log.info("Generated character: {} for user: {}", character.getName(), userId))
            .onFailure().invoke(error -> log.error("Failed to generate character", error));
    }
    
    /**
     * Get character by ID.
     * Uses CacheHelper.getOrLoad() pattern to reduce duplicate cache logic.
     */
    public Uni<Character> getCharacter(String characterId, String userId) {
        String cacheKey = cacheHelper.buildKey(characterId);

        return cacheHelper.get(cacheKey)
            .chain(cached -> {
                if (cached != null) {
                    // Verify access - compare UUID as String to avoid type mismatch
                    if (!cached.isPublic() && !ServiceHelper.isOwner(cached.getUserId(), userId)) {
                        return Uni.createFrom().failure(new RuntimeException("Access denied"));
                    }
                    return Uni.createFrom().item(cached);
                }

                // Load from database
                return loadCharacterFromDb(characterId, userId)
                    .map(character -> {
                        // Cache the result
                        cacheHelper.set(cacheKey, character);
                        return character;
                    });
            });
    }

    /**
     * Load character from database. Extracted for reuse and clarity.
     */
    private Uni<Character> loadCharacterFromDb(String characterId, String userId) {
        String query = """
            SELECT c.*,
                   u.displayName as ownerName,
                   (SELECT COUNT(*) FROM conversation WHERE characterId = c.characterUUID) as conversationCount
            FROM `character` c
            LEFT JOIN user u ON c.ownerId = u.userUUID
            WHERE c.characterUUID = ?
                AND (c.ownerId = ? OR c.isPublic = true)
            """;

        return pool.preparedQuery(query)
            .execute(Tuple.of(characterId, userId))
            .map(rows -> {
                if (rows.size() == 0) {
                    throw new RuntimeException("Character not found or access denied");
                }
                return mapRowToCharacter(rows.iterator().next());
            });
    }
    
    /**
     * Get user's characters.
     */
    public Uni<List<Character>> getUserCharacters(String userId, boolean includePublic) {
        
        String query;
        Tuple params;
        
        if (includePublic) {
            query = """
                SELECT c.*,
                       u.displayName as ownerName,
                       (SELECT COUNT(*) FROM conversation WHERE characterId = c.characterUUID) as conversationCount
                FROM `character` c
                LEFT JOIN user u ON c.ownerId = u.userUUID
                WHERE c.ownerId = ? OR c.isPublic = true
                ORDER BY c.usageCount DESC, c.createdAt DESC
                """;
            params = Tuple.of(userId);
        } else {
            query = """
                SELECT c.*,
                       u.displayName as ownerName,
                       (SELECT COUNT(*) FROM conversation WHERE characterId = c.characterUUID) as conversationCount
                FROM `character` c
                LEFT JOIN user u ON c.ownerId = u.userUUID
                WHERE c.ownerId = ?
                ORDER BY c.createdAt DESC
                """;
            params = Tuple.of(userId);
        }
        
        return pool.preparedQuery(query)
            .execute(params)
            .map(rows -> {
                List<Character> characters = new ArrayList<>();
                rows.forEach(row -> {
                    characters.add(mapRowToCharacter(row));
                });
                return characters;
            });
    }

    /**
     * Get public characters.
     */
    public Uni<List<Character>> getPublicCharacters(int limit, int offset, String sortBy) {
        
        // Validate sort parameter
        String orderBy = switch (sortBy != null ? sortBy : "popular") {
            case "recent" -> "c.createdAt DESC";
            case "rating" -> "c.rating DESC";
            case "usage" -> "c.usageCount DESC";
            default -> "c.usageCount DESC"; // popular
        };

        String query = """
            SELECT c.*,
                   u.displayName as ownerName,
                   (SELECT COUNT(*) FROM conversation WHERE characterId = c.characterUUID) as conversationCount
            FROM `character` c
            LEFT JOIN user u ON c.ownerId = u.userUUID
            WHERE c.isPublic = true
            ORDER BY %s
            LIMIT ? OFFSET ?
            """.formatted(orderBy);
        
        return pool.preparedQuery(query)
            .execute(Tuple.of(limit, offset))
            .map(rows -> {
                List<Character> characters = new ArrayList<>();
                rows.forEach(row -> {
                    characters.add(mapRowToCharacter(row));
                });
                return characters;
            });
    }
    
    /**
     * Update character.
     */
    public Uni<Character> updateCharacter(String characterId, String userId,
                                            String name, String description,
                                            String systemPrompt, String initialPrompt,
                                            Boolean isPublic) {
        
        // Get existing character
        return getCharacter(characterId, userId)
            .chain(existing -> {
                // Verify ownership using ServiceHelper to reduce duplication
                if (!ServiceHelper.isOwner(existing.getUserId(), userId)) {
                    return Uni.createFrom().failure(new RuntimeException("Only the owner can update this character"));
                }
                
                // Build update query using DynamicUpdateBuilder
                DynamicUpdateBuilder builder = new DynamicUpdateBuilder("character");

                // Truncate strings to max length and add to builder
                if (name != null && !name.trim().isEmpty()) {
                    builder.setString("name", ValidationHelper.truncate(name, ValidationHelper.MAX_NAME_LENGTH));
                }
                if (description != null) {
                    builder.setString("description", ValidationHelper.truncate(description, ValidationHelper.MAX_DESCRIPTION_LENGTH));
                }
                if (systemPrompt != null) {
                    builder.setString("systemPrompt", ValidationHelper.truncate(systemPrompt, ValidationHelper.MAX_SYSTEM_PROMPT_LENGTH));
                }
                if (initialPrompt != null) {
                    builder.setString("greeting", ValidationHelper.truncate(initialPrompt, ValidationHelper.MAX_INITIAL_PROMPT_LENGTH));
                }
                if (isPublic != null) {
                    builder.setBoolean("isPublic", isPublic);
                }
                builder.setLocalDateTime("updatedAt", LocalDateTime.now(ZoneOffset.UTC));

                // Build query
                DynamicUpdateBuilder.UpdateQuery query = builder.build("WHERE characterUUID = ?", characterId);

                if (!query.hasUpdates()) {
                    return Uni.createFrom().item(existing);
                }

                return pool.preparedQuery(query.sql())
                    .execute(query.params())
                    .chain(result -> {
                        // Invalidate cache
                        invalidateCharacterCache(characterId);

                        // Notify the characterUpdated subscription consumer.
                        // SubscriptionResolver registers on "user:<userId>:characters:updated"
                        // and reads the body as a JsonObject containing "id".
                        vertx.eventBus().publish("user:" + userId + ":characters:updated",
                            new JsonObject().put("id", characterId));

                        // Get updated character
                        return getCharacter(characterId, userId);
                    });
            })
            
            ;
    }
    
    /**
     * Delete character.
     */
    public Uni<Void> deleteCharacter(String characterId, String userId) {
        
        // Get character
        return getCharacter(characterId, userId)
            .chain(character -> {
                // Verify ownership using ServiceHelper to reduce duplication
                if (!ServiceHelper.isOwner(character.getUserId(), userId)) {
                    return Uni.createFrom().failure(new RuntimeException("Only the owner can delete this character"));
                }
                
                // Check if character is in use
                return checkCharacterInUse(characterId)
                    .chain(inUse -> {
                        if (inUse) {
                            return Uni.createFrom().failure(new RuntimeException("Cannot delete character that is in use by conversations"));
                        }
                        
                        // Delete character
                        String deleteQuery = "DELETE FROM `character` WHERE characterUUID = ?";
                        
                        return pool.preparedQuery(deleteQuery)
                            .execute(Tuple.of(characterId));
                    });
            })
            .chain(result -> {
                // Invalidate cache
                invalidateCharacterCache(characterId);

                // Notify the characterDeleted subscription consumer.
                // SubscriptionResolver registers on "user:<userId>:characters:deleted"
                // and reads the body as a raw UUID string.
                vertx.eventBus().publish("user:" + userId + ":characters:deleted", characterId);

                // Track metric using ServiceHelper to reduce duplication
                serviceHelper.trackMetric("metrics.character.deleted", new JsonObject()
                    .put("characterId", characterId)
                    .put("userId", userId));

                return Uni.createFrom().voidItem();
            })
            
            ;
    }
    
    /**
     * Clone a character.
     */
    public Uni<Character> cloneCharacter(String characterId, String userId, String newName) {
        
        // Get original character
        return getCharacter(characterId, userId)
            .chain(original -> {
                // Prepare cloned character details
                String cloneName = newName != null ? newName : original.getName() + " (Copy)";
                String description = original.getDescription();
                String systemPrompt = original.getSystemPrompt();
                String initialPrompt = original.getGreeting();
                
                JsonObject metadata = original.getMetadata() != null ?
                    original.getMetadata().copy() : new JsonObject();
                metadata.put("clonedFrom", characterId);
                metadata.put("clonedAt", LocalDateTime.now(ZoneOffset.UTC).toString());
                
                // Create the clone
                return createCharacter(userId, cloneName, description, systemPrompt,
                                     initialPrompt, metadata, false);
            })
            
            ;
    }
    
    /**
     * Rate a character.
     */
    public Uni<Void> rateCharacter(String characterId, String userId, int rating) {
        
        if (rating < 1 || rating > 5) {
            return Uni.createFrom().failure(new RuntimeException("Rating must be between 1 and 5"));
    }
        
        // Verify character exists and is public or owned by user
        return getCharacter(characterId, userId)
            .chain(character -> {
                // Store rating
                String upsertQuery = """
                    INSERT INTO character_rating (id, character_id, user_id, rating, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE rating = ?, updated_at = ?
                    """;
                
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                String ratingId = UUID.randomUUID().toString();

                return pool.preparedQuery(upsertQuery)
                    .execute(Tuple.tuple()
                        .addValue(ratingId)
                        .addValue(characterId)
                        .addValue(userId)
                        .addValue(rating)
                        .addValue(now)
                        .addValue(rating)
                        .addValue(now)
                    );
            })
            .chain(result -> {
                // Update average rating
                return updateCharacterRating(characterId);
            })
            
            ;
    }
    
    /**
     * Increment usage count.
     */
    public Uni<Void> incrementUsageCount(String characterId) {
        
        String updateQuery = """
            UPDATE `character`
            SET usageCount = usageCount + 1
            WHERE characterUUID = ?
            """;
        
        return pool.preparedQuery(updateQuery)
            .execute(Tuple.of(characterId))
            .invoke(v -> invalidateCharacterCache(characterId))
            .replaceWithVoid();
    }
    
    /**
     * Search characters.
     */
    public Uni<JsonArray> searchCharacters(String query, String userId, int limit) {
        
        String searchQuery = """
            SELECT c.*,
                   u.displayName as ownerName
            FROM `character` c
            LEFT JOIN user u ON c.ownerId = u.userUUID
            WHERE (c.name LIKE ? OR c.description LIKE ?)
                AND (c.ownerId = ? OR c.isPublic = true)
            ORDER BY c.usageCount DESC, c.rating DESC
            LIMIT ?
            """;
        
        String searchPattern = "%" + query + "%";
        
        return pool.preparedQuery(searchQuery)
            .execute(Tuple.of(searchPattern, searchPattern, userId, limit))
            .map(rows -> {
                JsonArray results = new JsonArray();

                rows.forEach(row -> {
                    JsonObject character = new JsonObject()
                        .put("id", row.getString("characterUUID"))
                        .put("name", row.getString("name"))
                        .put("description", row.getString("description"))
                        .put("isPublic", row.getBoolean("isPublic"))
                        .put("ownerName", row.getString("ownerName"))
                        .put("usageCount", row.getInteger("usageCount"))
                        .put("rating", row.getDouble("rating"));

                    results.add(character);
                });

                return results;
            });
    }
    
    // Helper methods

    // Helper methods replaced with ServiceHelper to reduce duplication
    private Uni<Boolean> checkDuplicateName(String userId, String name) {
        return serviceHelper.nameExists("character", "name", "ownerId", name, userId);
    }

    private Uni<Boolean> checkCharacterInUse(String characterId) {
        return serviceHelper.count("conversation", "WHERE characterId = ?", Tuple.of(characterId))
            .map(count -> count > 0);
    }
    
    private Uni<Void> updateCharacterRating(String characterId) {

        String query = """
            UPDATE `character` c
            SET c.rating = (
                SELECT AVG(rating)
                FROM character_rating
                WHERE character_id = ?
            )
            WHERE c.characterUUID = ?
            """;

        return pool.preparedQuery(query)
            .execute(Tuple.of(characterId, characterId))
            .invoke(v -> invalidateCharacterCache(characterId))
            .replaceWithVoid();
    }
    
    private Character mapRowToCharacter(Row row) {
        Character character = new Character();
        character.setId(UUID.fromString(row.getString("characterUUID")));
        String ownerIdStr = row.getString("ownerId");
        if (ownerIdStr != null) {
            character.setOwnerId(UUID.fromString(ownerIdStr));
        }
        character.setName(row.getString("name"));
        character.setDescription(row.getString("description"));
        character.setSystemPrompt(row.getString("systemPrompt"));
        character.setGreeting(row.getString("greeting"));

        // Extended character fields
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
        character.setCategory(row.getString("category"));
        character.setInitialAffinityToUser(row.getInteger("initialAffinityToUser"));
        character.setInitialAffinityFromUser(row.getInteger("initialAffinityFromUser"));

        // LLM parameters
        if (row.getDouble("temperature") != null) {
            character.setTemperature(row.getDouble("temperature"));
        }
        if (row.getInteger("maxTokens") != null) {
            character.setMaxTokens(row.getInteger("maxTokens"));
        }
        if (row.getDouble("topP") != null) {
            character.setTopP(row.getDouble("topP"));
        }
        if (row.getInteger("topK") != null) {
            character.setTopK(row.getInteger("topK"));
        }
        if (row.getDouble("repeatPenalty") != null) {
            character.setRepeatPenalty(row.getDouble("repeatPenalty"));
        }

        // Parse metadata if present
        String metadataStr = ServiceHelper.safeGetJsonText(row, "metadata");
        if (metadataStr != null) {
            try {
                character.setMetadata(new JsonObject(metadataStr));
            } catch (Exception e) {
                character.setMetadata(new JsonObject());
            }
        }

        character.setPublic(row.getBoolean("isPublic"));
        character.setIsDefault(row.getBoolean("isDefault"));
        character.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        character.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        character.setUsageCount(row.getInteger("usageCount"));
        character.setRating(row.getDouble("rating"));

        // Parse tags
        String tagsStr = row.getString("tags");
        if (tagsStr != null && !tagsStr.isEmpty()) {
            character.setTagsFromString(tagsStr);
        }

        // Add owner name if present
        if (row.getString("ownerName") != null) {
            character.setOwnerName(row.getString("ownerName"));
        }

        // Add conversation count if present
        if (row.getInteger("conversationCount") != null) {
            character.setConversationCount(row.getInteger("conversationCount"));
        }

        // Photo album (stored as JSON array)
        String photoAlbumStr = ServiceHelper.safeGetJsonText(row, "photoAlbum");
        if (photoAlbumStr != null && !photoAlbumStr.isEmpty()) {
            try {
                io.vertx.core.json.JsonArray arr = new io.vertx.core.json.JsonArray(photoAlbumStr);
                List<String> photos = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++) {
                    photos.add(arr.getString(i));
                }
                character.setPhotoAlbum(photos);
            } catch (Exception e) {
                character.setPhotoAlbum(new ArrayList<>());
            }
        }

        // Chat background image
        character.setChatBackgroundImage(row.getString("chatBackgroundImage"));

        return character;
    }
    
    // ================================================================
    // Trending and Statistics (moved from CharacterServiceVerticle)
    // ================================================================

    /**
     * Get trending characters based on recent usage (last 7 days).
     * Moved from CharacterServiceVerticle to centralize business logic.
     *
     * @param limit Maximum number of characters to return
     * @return Uni<JsonArray> array of trending characters
     */
    public Uni<JsonArray> getTrendingCharacters(int limit) {

        String query = """
            SELECT c.*,
                   u.displayName as ownerName,
                   COUNT(DISTINCT conv.conversationGUID) as recentUsage
            FROM `character` c
            LEFT JOIN user u ON c.ownerId = u.userUUID
            LEFT JOIN conversation conv ON c.characterUUID = conv.characterId
                AND conv.createdAt >= DATE_SUB(NOW(), INTERVAL 7 DAY)
            WHERE c.isPublic = true
            GROUP BY c.characterUUID
            ORDER BY recentUsage DESC
            LIMIT ?
            """;

        return pool.preparedQuery(query)
            .execute(Tuple.of(limit))
            .map(rows -> {
                JsonArray trending = new JsonArray();
                rows.forEach(row -> {
                    trending.add(new JsonObject()
                        .put("id", row.getString("characterUUID"))
                        .put("name", row.getString("name"))
                        .put("description", row.getString("description"))
                        .put("recentUsage", row.getInteger("recentUsage"))
                        .put("rating", row.getDouble("rating"))
                        .put("ownerName", row.getString("ownerName")));
                });
                return trending;
            })
            .onFailure().invoke(error -> log.error("Failed to get trending characters", error));
    }

    /**
     * Get character statistics including usage, ratings, and conversations.
     * Moved from CharacterServiceVerticle to centralize business logic.
     *
     * @param characterId The character ID
     * @return Uni<JsonObject> character statistics
     */
    public Uni<JsonObject> getCharacterStatistics(String characterId) {

        String query = """
            SELECT
                c.usageCount,
                c.rating,
                COUNT(DISTINCT conv.conversationGUID) as totalConversations,
                COUNT(DISTINCT conv.ownerId) as uniqueUsers,
                COUNT(DISTINCT cr.user_id) as totalRatings,
                AVG(cr.rating) as averageRating
            FROM `character` c
            LEFT JOIN conversation conv ON c.characterUUID = conv.characterId
            LEFT JOIN character_rating cr ON c.characterUUID = cr.character_id
            WHERE c.characterUUID = ?
            GROUP BY c.characterUUID
            """;

        return pool.preparedQuery(query)
            .execute(Tuple.of(characterId))
            .map(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    return new JsonObject()
                        .put("usageCount", row.getInteger("usageCount"))
                        .put("rating", row.getDouble("rating"))
                        .put("totalConversations", row.getLong("totalConversations"))
                        .put("uniqueUsers", row.getLong("uniqueUsers"))
                        .put("totalRatings", row.getLong("totalRatings"))
                        .put("averageRating", row.getDouble("averageRating"));
                } else {
                    return new JsonObject();
                }
            })
            .onFailure().invoke(error -> log.error("Failed to get character statistics for {}", characterId, error));
    }

    // ================================================================
    // Cache Helper Delegates (using CacheHelper to reduce duplication)
    // ================================================================

    /**
     * Invalidate character cache.
     * Delegates to CacheHelper.invalidate() to eliminate duplicate code.
     */
    private void invalidateCharacterCache(String characterId) {
        cacheHelper.invalidate(characterId);
    }
}