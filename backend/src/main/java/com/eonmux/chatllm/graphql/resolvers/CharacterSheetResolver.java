package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.utils.ServiceHelper;
import com.eonmux.chatllm.models.CharacterInventory;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.models.CharacterRelationship;
import com.eonmux.chatllm.models.CharacterStat;
import com.eonmux.chatllm.models.inputs.*;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;

/**
 * GraphQL resolver for character sheet operations.
 * Handles character stats, inventory, and relationships.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class CharacterSheetResolver {

    @Inject
    Vertx vertx;
    @Inject
    Pool mysqlPool;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    jakarta.enterprise.inject.Instance<io.smallrye.graphql.api.Context> graphqlInputContext;

    @Inject
    com.eonmux.chatllm.services.InventoryService inventoryService;

    @Inject
    com.eonmux.chatllm.utils.ServiceHelper serviceHelper;

    /**
     * Verify the caller owns or actively participates in the conversation
     * before touching character-sheet rows. Previously every query/mutation
     * here ran against caller-supplied conversation/character/inventory IDs
     * with no ownership check — any authenticated user could read or write
     * any conversation's stats, relationships, and inventories.
     */
    private Uni<Boolean> verifyConversationAccess(String conversationId, String userId) {
        return serviceHelper.verifyConversationAccess(conversationId, userId);
    }

    /**
     * Get character stats for a conversation and character.
     * Schema: characterStats(conversationId: ID!, characterId: ID!): [CharacterStat!]!
     */
    @Query
    public Uni<List<CharacterStat>> characterStats(@Name("conversationId") String conversationId, @Name("characterId") String characterId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return verifyConversationAccess(conversationId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }
                String query = "SELECT * FROM character_stat WHERE conversationId = ? AND characterId = ? ORDER BY statCategory, statName";
                return mysqlPool.preparedQuery(query)
                    .execute(Tuple.of(conversationId, characterId))
                    .map(rows -> {
                        List<CharacterStat> stats = new ArrayList<>();
                        rows.forEach(row -> stats.add(mapRowToCharacterStat(row)));
                        return stats;
                    });
            })
            .onFailure().invoke(err -> {
                log.error("Failed to get character stats: {}", err.getMessage());
            });
    }

    /**
     * Get character relationships for a conversation.
     * Schema: characterRelationships(conversationId: ID!, characterId: ID): [CharacterRelationship!]!
     */
    @Query
    public Uni<List<CharacterRelationship>> characterRelationships(@Name("conversationId") String conversationId, @Name("characterId") String characterId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return verifyConversationAccess(conversationId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }
                String query;
                Tuple params;

                if (characterId != null && !characterId.isEmpty()) {
                    query = "SELECT * FROM character_relationship WHERE conversationId = ? AND (fromCharacterId = ? OR toCharacterId = ?)";
                    params = Tuple.of(conversationId, characterId, characterId);
                } else {
                    query = "SELECT * FROM character_relationship WHERE conversationId = ?";
                    params = Tuple.of(conversationId);
                }

                return mysqlPool.preparedQuery(query)
                    .execute(params)
                    .map(rows -> {
                        List<CharacterRelationship> relationships = new ArrayList<>();
                        rows.forEach(row -> relationships.add(mapRowToCharacterRelationship(row)));
                        return relationships;
                    });
            })
            .onFailure().invoke(err -> {
                log.error("Failed to get character relationships: {}", err.getMessage());
            });
    }

    /**
     * Get character inventory for a conversation and character.
     * Schema: characterInventory(conversationId: ID!, characterId: ID!): [CharacterInventoryItem!]!
     */
    @Query
    public Uni<List<CharacterInventory>> characterInventory(@Name("conversationId") String conversationId, @Name("characterId") String characterId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return verifyConversationAccess(conversationId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }
                String query = "SELECT * FROM character_inventory WHERE conversationId = ? AND characterId = ? ORDER BY category, itemName";
                return mysqlPool.preparedQuery(query)
                    .execute(Tuple.of(conversationId, characterId))
                    .map(rows -> {
                        List<CharacterInventory> inventory = new ArrayList<>();
                        rows.forEach(row -> inventory.add(mapRowToCharacterInventory(row)));
                        return inventory;
                    });
            })
            .onFailure().invoke(err -> {
                log.error("Failed to get character inventory: {}", err.getMessage());
            });
    }

    /**
     * Get user inventory for a conversation and user.
     * Schema: userInventory(conversationId: ID!, userId: ID!): [CharacterInventoryItem!]!
     */
    @Query
    public Uni<List<CharacterInventory>> userInventory(@Name("conversationId") String conversationId, @Name("userId") String userId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String callerId = auth.getUserId().toString();

        // Only the owner of the inventory (or an admin) may read a user's
        // inventory — previously any authenticated user could read any user's
        // items in any conversation by passing arbitrary IDs.
        if (!auth.isAdmin() && !callerId.equals(userId)) {
            return Uni.createFrom().failure(new RuntimeException("Access denied"));
        }

        return verifyConversationAccess(conversationId, callerId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }
                String query = "SELECT * FROM character_inventory WHERE conversationId = ? AND userId = ? ORDER BY category, itemName";
                return mysqlPool.preparedQuery(query)
                    .execute(Tuple.of(conversationId, userId))
                    .map(rows -> {
                        List<CharacterInventory> inventory = new ArrayList<>();
                        rows.forEach(row -> inventory.add(mapRowToCharacterInventory(row)));
                        return inventory;
                    });
            })
            .onFailure().invoke(err -> {
                log.error("Failed to get user inventory: {}", err.getMessage());
            });
    }

    private CharacterStat mapRowToCharacterStat(Row row) {
        String statIdStr = row.getString("statId");
        String convIdStr = row.getString("conversationId");
        String charIdStr = row.getString("characterId");
        return CharacterStat.builder()
            .statId(statIdStr != null ? UUID.fromString(statIdStr) : null)
            .conversationId(convIdStr != null ? UUID.fromString(convIdStr) : null)
            .characterId(charIdStr != null ? UUID.fromString(charIdStr) : null)
            .statName(row.getString("statName"))
            .statValue(row.getDouble("statValue"))
            .statMax(row.getDouble("statMax"))
            .statType(row.getString("statType"))
            .statCategory(row.getString("statCategory"))
            .textValue(row.getString("textValue"))
            .isVisible(row.getBoolean("isVisible"))
            .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
            .updatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")))
            .build();
    }

    private CharacterRelationship mapRowToCharacterRelationship(Row row) {
        String relIdStr = row.getString("relationshipId");
        String convIdStr = row.getString("conversationId");
        String fromIdStr = row.getString("fromCharacterId");
        String toIdStr = row.getString("toCharacterId");
        return CharacterRelationship.builder()
            .relationshipId(relIdStr != null ? UUID.fromString(relIdStr) : null)
            .conversationId(convIdStr != null ? UUID.fromString(convIdStr) : null)
            .fromCharacterId(fromIdStr != null ? UUID.fromString(fromIdStr) : null)
            .toCharacterId(toIdStr != null ? UUID.fromString(toIdStr) : null)
            .relationshipType(row.getString("relationshipType"))
            .intensity(row.getDouble("intensity"))
            .description(row.getString("description"))
            // history is @Ignore JsonObject - skip
            .isReciprocal(row.getBoolean("isReciprocal"))
            .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
            .updatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")))
            .build();
    }

    private CharacterInventory mapRowToCharacterInventory(Row row) {
        String invIdStr = row.getString("inventoryId");
        String convIdStr = row.getString("conversationId");
        String charIdStr = row.getString("characterId");
        String userIdStr = row.getString("userId");
        return CharacterInventory.builder()
            .inventoryId(invIdStr != null ? UUID.fromString(invIdStr) : null)
            .conversationId(convIdStr != null ? UUID.fromString(convIdStr) : null)
            .characterId(charIdStr != null ? UUID.fromString(charIdStr) : null)
            .userId(userIdStr != null ? UUID.fromString(userIdStr) : null)
            .itemName(row.getString("itemName"))
            .itemDescription(row.getString("itemDescription"))
            .quantity(row.getInteger("quantity"))
            .category(row.getString("category"))
            .properties(ServiceHelper.safeGetJsonText(row, "properties"))
            .isEquipped(row.getBoolean("isEquipped"))
            .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
            .updatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")))
            .build();
    }

    /**
     * Set or update a character stat.
     * Schema: setCharacterStat(input: SetCharacterStatInput!): CharacterStat!
     */
    @Mutation("setCharacterStat")
    public Uni<CharacterStat> setCharacterStat(@Name("input") SetCharacterStatInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String characterId = (String) input.get("characterId");
        String statName = (String) input.get("statName");
        Double statValue = input.get("statValue") != null ? ((Number) input.get("statValue")).doubleValue() : 0.0;
        Double statMax = input.get("statMax") != null ? ((Number) input.get("statMax")).doubleValue() : null;
        String statType = (String) input.getOrDefault("statType", "numeric");
        String statCategory = (String) input.get("statCategory");
        String textValue = (String) input.get("textValue");
        Boolean isVisible = (Boolean) input.getOrDefault("isVisible", true);

        String statId = UUID.randomUUID().toString();
        String upsertQuery = """
            INSERT INTO character_stat (statId, conversationId, characterId, statName, statValue, statMax, statType, statCategory, textValue, isVisible)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                statValue = VALUES(statValue),
                statMax = VALUES(statMax),
                statType = VALUES(statType),
                statCategory = VALUES(statCategory),
                textValue = VALUES(textValue),
                isVisible = VALUES(isVisible),
                statId = LAST_INSERT_ID(statId)
            """;

        return verifyConversationAccess(conversationId, auth.getUserId().toString())
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }
                return mysqlPool.preparedQuery(upsertQuery)
                    .execute(Tuple.tuple()
                        .addValue(statId)
                        .addValue(conversationId)
                        .addValue(characterId)
                        .addValue(statName)
                        .addValue(statValue)
                        .addValue(statMax)
                        .addValue(statType)
                        .addValue(statCategory)
                        .addValue(textValue)
                        .addValue(isVisible))
                    .chain(result -> {
                        // Retrieve the updated/inserted stat
                        String selectQuery = "SELECT * FROM character_stat WHERE conversationId = ? AND characterId = ? AND statName = ?";
                        return mysqlPool.preparedQuery(selectQuery)
                            .execute(Tuple.of(conversationId, characterId, statName))
                            .map(rows -> {
                                if (rows.size() > 0) {
                                    return mapRowToCharacterStat(rows.iterator().next());
                                } else {
                                    throw new RuntimeException("Failed to retrieve stat after upsert");
                                }
                            });
                    });
            })
            .onFailure().invoke(err -> {
                log.error("Failed to set character stat: {}", err.getMessage());
            });
    }

    /**
     * Remove a character stat.
     * Schema: removeCharacterStat(conversationId: ID!, characterId: ID!, statName: String!): Boolean!
     */
    @Mutation
    public Uni<Boolean> removeCharacterStat(@Name("conversationId") String conversationId, @Name("characterId") String characterId, @Name("statName") String statName) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return verifyConversationAccess(conversationId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }
                String deleteQuery = "DELETE FROM character_stat WHERE conversationId = ? AND characterId = ? AND statName = ?";
                return mysqlPool.preparedQuery(deleteQuery)
                    .execute(Tuple.of(conversationId, characterId, statName))
                    .map(result -> result.rowCount() > 0);
            })
            .onFailure().invoke(err -> {
                log.error("Failed to remove character stat: {}", err.getMessage());
            });
    }

    /**
     * Set or update a character relationship.
     * Schema: setRelationship(input: SetRelationshipInput!): CharacterRelationship!
     */
    @Mutation("setRelationship")
    public Uni<CharacterRelationship> setRelationship(@Name("input") SetRelationshipInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String fromCharacterId = (String) input.get("fromCharacterId");
        String toCharacterId = (String) input.get("toCharacterId");
        String relationshipType = (String) input.get("relationshipType");
        Double intensity = input.get("intensity") != null ? ((Number) input.get("intensity")).doubleValue() : 0.5;
        String description = (String) input.get("description");
        Object history = input.get("history"); // JSON field
        Boolean isReciprocal = (Boolean) input.getOrDefault("isReciprocal", false);

        String relationshipId = UUID.randomUUID().toString();
        String upsertQuery = """
            INSERT INTO character_relationship (relationshipId, conversationId, fromCharacterId, toCharacterId, relationshipType, intensity, description, history, isReciprocal)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                relationshipType = VALUES(relationshipType),
                intensity = VALUES(intensity),
                description = VALUES(description),
                history = VALUES(history),
                isReciprocal = VALUES(isReciprocal),
                relationshipId = LAST_INSERT_ID(relationshipId)
            """;

        return verifyConversationAccess(conversationId, auth.getUserId().toString())
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }
                return mysqlPool.preparedQuery(upsertQuery)
                    .execute(Tuple.tuple()
                        .addValue(relationshipId)
                        .addValue(conversationId)
                        .addValue(fromCharacterId)
                        .addValue(toCharacterId)
                        .addValue(relationshipType)
                        .addValue(intensity)
                        .addValue(description)
                        .addValue(history)
                        .addValue(isReciprocal))
                    .chain(result -> {
                        // Retrieve the updated/inserted relationship
                        String selectQuery = "SELECT * FROM character_relationship WHERE conversationId = ? AND fromCharacterId = ? AND toCharacterId = ?";
                        return mysqlPool.preparedQuery(selectQuery)
                            .execute(Tuple.of(conversationId, fromCharacterId, toCharacterId))
                            .map(rows -> {
                                if (rows.size() > 0) {
                                    return mapRowToCharacterRelationship(rows.iterator().next());
                                } else {
                                    throw new RuntimeException("Failed to retrieve relationship after upsert");
                                }
                            });
                    });
            })
            .onFailure().invoke(err -> {
                log.error("Failed to set character relationship: {}", err.getMessage());
            });
    }

    @Query
    public Uni<com.eonmux.chatllm.models.InventoryAccess> inventoryAccess(@Name("conversationId") String conversationId,
            @Name("characterId") String characterId, @Name("userId") String userId) {
        return inventoryService.access(conversationId, characterId, userId, graphQLAuthContext.requireAuth().getUserId().toString());
    }

    @Mutation
    public Uni<CharacterInventory> addInventoryItem(@Name("input") AddInventoryItemInput input) {
        String caller = graphQLAuthContext.requireAuth().getUserId().toString();
        io.vertx.core.json.JsonObject props = input.getProperties() == null || input.getProperties().isBlank()
            ? null : new io.vertx.core.json.JsonObject(input.getProperties());
        return inventoryService.add(input.getConversationId(), input.getCharacterId(), input.getUserId(),
            input.getItemName(), input.getItemDescription(), input.getQuantity(), input.getCategory(), props,
            Boolean.TRUE.equals(input.getIsEquipped()), caller);
    }

    @Mutation
    public Uni<Boolean> removeInventoryItem(@Name("inventoryId") String id) {
        return inventoryService.removeItem(id, graphQLAuthContext.requireAuth().getUserId().toString());
    }

    @Mutation
    public Uni<CharacterInventory> equipItem(@Name("inventoryId") String id, @Name("equipped") Boolean equipped) {
        String caller = graphQLAuthContext.requireAuth().getUserId().toString();
        if (equipped == null) throw new IllegalArgumentException("Equipped is required");
        return inventoryService.equipItem(id, equipped, caller);
    }

    @Mutation
    public Uni<CharacterInventory> useInventoryItem(@Name("inventoryId") String id, @Name("targetCharacterId") String targetCharacterId) {
        return inventoryService.useItem(id, graphQLAuthContext.requireAuth().getUserId().toString(), targetCharacterId);
    }

    @Mutation
    public Uni<CharacterInventory> transferInventoryItem(@Name("inventoryId") String id,
            @Name("targetCharacterId") String ch, @Name("targetUserId") String u, @Name("quantity") Integer quantity) {
        return inventoryService.transferItem(id, ch, u, quantity, graphQLAuthContext.requireAuth().getUserId().toString());
    }
}
