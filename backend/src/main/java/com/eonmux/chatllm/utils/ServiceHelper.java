package com.eonmux.chatllm.utils;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

/**
 * Common service operations to eliminate duplicate code across services.
 * Consolidates patterns like ownership verification, existence checks, and metrics tracking.
 */
@Slf4j
@ApplicationScoped
public class ServiceHelper {

    @Inject
    Pool mysqlPool;

    @Inject
    EventBus eventBus;

    // Whitelist of allowed table and column identifiers to prevent SQL injection
    // via String.format in dynamic SQL helpers. Only identifiers in this set
    // may be interpolated into query strings.
    private static final Set<String> ALLOWED_SQL_IDENTIFIERS = Set.of(
        "conversation", "conversationGUID", "ownerId",
        "character", "characterUUID", "name");

    // ================================================================
    // Ownership and Access Verification
    // ================================================================

    /**
     * Verify that a user owns a specific entity.
     *
     * @param table     The table name (e.g., "conversation", "character")
     * @param idColumn  The ID column name (e.g., "conversationGUID", "characterUUID")
     * @param ownerColumn The owner column name (e.g., "ownerId")
     * @param entityId  The entity ID to check
     * @param userId    The user ID to verify ownership
     * @return Future<Boolean> true if user owns the entity
     */
    public Uni<Boolean> verifyOwnership(String table, String idColumn, String ownerColumn,
                                           String entityId, String userId) {
        validateSqlIdentifier(table, idColumn, ownerColumn);
        String query = String.format(
            "SELECT COUNT(*) as count FROM `%s` WHERE %s = ? AND %s = ?",
            table, idColumn, ownerColumn
        );

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(entityId, userId))
            .map(rows -> {
                long count = rows.iterator().next().getLong("count");
                return count > 0;
            })
            .onFailure().recoverWithItem(error -> {
                log.error("Error verifying ownership for {} in {}: {}", entityId, table, error.getMessage());
                return false;
            });
    }

    /**
     * Verify conversation ownership.
     *
     * @param conversationId The conversation ID
     * @param userId         The user ID
     * @return Future<Boolean> true if user owns the conversation
     */
    public Uni<Boolean> verifyConversationOwnership(String conversationId, String userId) {
        return verifyOwnership("conversation", "conversationGUID", "ownerId", conversationId, userId);
    }

    /**
     * Verify character ownership.
     *
     * @param characterId The character ID
     * @param userId      The user ID
     * @return Future<Boolean> true if user owns the character
     */
    public Uni<Boolean> verifyCharacterOwnership(String characterId, String userId) {
        return verifyOwnership("character", "characterUUID", "ownerId", characterId, userId);
    }

    /**
     * Verify conversation access (owner OR active participant).
     *
     * @param conversationId The conversation ID
     * @param userId         The user ID
     * @return Future<Boolean> true if user has access
     */
    public Uni<Boolean> verifyConversationAccess(String conversationId, String userId) {
        String query = """
            SELECT COUNT(*) as count
            FROM conversation c
            LEFT JOIN conversation_participant cp ON c.conversationGUID = cp.conversation_id
            WHERE c.conversationGUID = ?
              AND (c.ownerId = ? OR (cp.user_id = ? AND cp.is_active = true))
            """;

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversationId, userId, userId))
            .map(rows -> {
                long count = rows.iterator().next().getLong("count");
                return count > 0;
            })
            .onFailure().recoverWithItem(error -> {
                log.error("Error verifying conversation access for user {} in {}: {}",
                    userId, conversationId, error.getMessage());
                return false;
            });
    }

    /**
     * Verify character access (owner OR public).
     *
     * @param characterId The character ID
     * @param userId      The user ID
     * @return Future<Boolean> true if user has access
     */
    public Uni<Boolean> verifyCharacterAccess(String characterId, String userId) {
        String query = """
            SELECT COUNT(*) as count
            FROM `character`
            WHERE characterUUID = ?
              AND (ownerId = ? OR isPublic = true)
            """;

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(characterId, userId))
            .map(rows -> {
                long count = rows.iterator().next().getLong("count");
                return count > 0;
            })
            .onFailure().recoverWithItem(error -> {
                log.error("Error verifying character access for user {} to {}: {}",
                    userId, characterId, error.getMessage());
                return false;
            });
    }

    /**
     * Verify that a user can access a world map (i.e. owns or participates in
     * the conversation the map belongs to). Used to gate map-scoped subscriptions.
     *
     * @param mapId  The world map ID
     * @param userId The user ID
     * @return Uni<Boolean> true if user has access
     */
    public Uni<Boolean> verifyMapAccess(String mapId, String userId) {
        String query = """
            SELECT COUNT(*) as count
            FROM world_map m
            JOIN conversation c ON m.conversationId = c.conversationGUID
            LEFT JOIN conversation_participant cp ON c.conversationGUID = cp.conversation_id
            WHERE m.mapId = ?
              AND (c.ownerId = ? OR (cp.user_id = ? AND cp.is_active = true))
            """;

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(mapId, userId, userId))
            .map(rows -> rows.iterator().next().getLong("count") > 0)
            .onFailure().recoverWithItem(error -> {
                log.error("Error verifying map access for user {} to map {}: {}",
                    userId, mapId, error.getMessage());
                return false;
            });
    }

    // ================================================================
    // Existence Checks
    // ================================================================

    private static void validateSqlIdentifier(String... identifiers) {
        for (String id : identifiers) {
            if (!ALLOWED_SQL_IDENTIFIERS.contains(id)) {
                throw new IllegalArgumentException("Disallowed SQL identifier: " + id);
            }
        }
    }

    /**
     * Check if an entity exists in a table.
     *
     * @param table    The table name
     * @param idColumn The ID column name
     * @param entityId The entity ID to check
     * @return Future<Boolean> true if entity exists
     */
    public Uni<Boolean> exists(String table, String idColumn, String entityId) {
        validateSqlIdentifier(table, idColumn);
        String query = String.format("SELECT COUNT(*) as count FROM `%s` WHERE %s = ?", table, idColumn);

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(entityId))
            .map(rows -> {
                long count = rows.iterator().next().getLong("count");
                return count > 0;
            })
            .onFailure().recoverWithItem(error -> {
                log.error("Error checking existence for {} in {}: {}", entityId, table, error.getMessage());
                return false;
            });
    }

    /**
     * Check if a character exists. Character deletion removes the row.
     *
     * @param characterId The character ID
     * @return Future<Boolean> true if character exists and is active
     */
    public Uni<Boolean> characterExists(String characterId) {
        if (characterId == null || characterId.isEmpty()) {
            return Uni.createFrom().item(true); // null characterId is valid
        }

        String query = """
            SELECT COUNT(*) as count
            FROM `character`
            WHERE characterUUID = ?
            """;

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(characterId))
            .map(rows -> {
                long count = rows.iterator().next().getLong("count");
                return count > 0;
            })
            .onFailure().recoverWithItem(error -> {
                log.error("Error checking character existence for {}: {}", characterId, error.getMessage());
                return false;
            });
    }

    /**
     * Check if a name is already used by a user in a table.
     *
     * @param table       The table name
     * @param nameColumn  The name column
     * @param ownerColumn The owner column
     * @param name        The name to check
     * @param userId      The user ID
     * @return Future<Boolean> true if name already exists
     */
    public Uni<Boolean> nameExists(String table, String nameColumn, String ownerColumn,
                                       String name, String userId) {
        validateSqlIdentifier(table, nameColumn, ownerColumn);
        String query = String.format(
            "SELECT COUNT(*) as count FROM `%s` WHERE %s = ? AND %s = ?",
            table, ownerColumn, nameColumn
        );

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(userId, name))
            .map(rows -> {
                long count = rows.iterator().next().getLong("count");
                return count > 0;
            })
            .onFailure().recoverWithItem(error -> false);
    }

    // ================================================================
    // Row Mapping Utilities
    // ================================================================

    /**
     * Safely extract a JSON column from a database Row.
     * The Vert.x MySQL client may return JSON columns as String (LONGTEXT)
     * or JsonObject depending on driver version and column type.
     * This method handles both cases without throwing ClassCastException.
     *
     * @param row    The database row
     * @param column The column name containing JSON data
     * @return The parsed JsonObject, or null if the column is null or unparseable
     */
    @SuppressWarnings("unchecked")
    public static JsonObject safeGetJsonObject(Row row, String column) {
        try {
            Object val = row.getValue(column);
            if (val == null) return null;
            if (val instanceof JsonObject) return (JsonObject) val;
            if (val instanceof java.util.Map) return new JsonObject((java.util.Map<String, Object>) val);
            if (val instanceof String str) {
                if (str.isEmpty()) return null;
                return new JsonObject(str);
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to parse JSON column '{}': {}", column, e.getMessage());
            return null;
        }
    }

    /**
     * Safely extract a JSON array column from a database Row.
     * Handles both String and JsonArray returns from the MySQL driver.
     *
     * @param row    The database row
     * @param column The column name containing JSON array data
     * @return The parsed JsonArray, or null if the column is null or unparseable
     */
    @SuppressWarnings("unchecked")
    public static JsonArray safeGetJsonArray(Row row, String column) {
        try {
            Object val = row.getValue(column);
            if (val == null) return null;
            if (val instanceof JsonArray) return (JsonArray) val;
            if (val instanceof java.util.List) return new JsonArray((java.util.List) val);
            if (val instanceof String str) {
                if (str.isEmpty()) return null;
                return new JsonArray(str);
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to parse JSON array column '{}': {}", column, e.getMessage());
            return null;
        }
    }

    /**
     * Read a JSON column as text for callers that parse it themselves.
     * Row.getString throws ClassCastException when the driver decodes a JSON
     * column to JsonObject, so this accepts either representation.
     *
     * @param row    The database row
     * @param column The column name containing JSON data
     * @return The JSON text, or null when the column is null or unreadable
     */
    @SuppressWarnings("unchecked")
    public static String safeGetJsonText(Row row, String column) {
        try {
            Object val = row.getValue(column);
            if (val == null) return null;
            if (val instanceof String str) return str.isEmpty() ? null : str;
            if (val instanceof JsonObject obj) return obj.encode();
            if (val instanceof JsonArray arr) return arr.encode();
            if (val instanceof java.util.Map<?, ?>) return new JsonObject((java.util.Map<String, Object>) val).encode();
            if (val instanceof java.util.List<?>) return new JsonArray((java.util.List<Object>) val).encode();
            return val.toString();
        } catch (Exception e) {
            log.debug("Failed to read JSON column '{}': {}", column, e.getMessage());
            return null;
        }
    }

    /**
     * Map a database Row to JsonObject with specified fields.
     * Handles null values and common type conversions.
     *
     * @param row    The database row
     * @param fields The field names to map
     * @return JsonObject containing the specified fields
     */
    public static JsonObject mapRowToJson(Row row, String... fields) {
        JsonObject json = new JsonObject();

        for (String field : fields) {
            Object value = row.getValue(field);

            // Handle common conversions
            if (value instanceof LocalDateTime) {
                json.put(field, value.toString());
            } else if (value instanceof UUID) {
                json.put(field, value.toString());
            } else if (value != null) {
                json.put(field, value);
            } else {
                json.putNull(field);
            }
        }

        return json;
    }

    /**
     * Map multiple database rows to JsonArray with specified fields.
     *
     * @param rows   The database rows
     * @param fields The field names to map
     * @return JsonArray containing the mapped objects
     */
    public static JsonArray mapRowsToJsonArray(RowSet<Row> rows, String... fields) {
        JsonArray array = new JsonArray();

        for (Row row : rows) {
            array.add(mapRowToJson(row, fields));
        }

        return array;
    }

    /**
     * Unified count query helper.
     * Executes a COUNT(*) query with optional WHERE clause.
     *
     * @param table       The table name
     * @param whereClause Optional WHERE clause (can be null)
     * @param params      Parameters for the WHERE clause (can be null)
     * @return Future<Long> the count result
     */
    public Uni<Long> count(String table, String whereClause, Tuple params) {
        StringBuilder query = new StringBuilder("SELECT COUNT(*) as count FROM `")
            .append(table)
            .append("`");

        if (whereClause != null && !whereClause.isBlank()) {
            query.append(" ").append(whereClause);
        }

        var preparedQuery = mysqlPool.preparedQuery(query.toString());

        Uni<RowSet<Row>> execUni = (params != null && params.size() > 0)
            ? preparedQuery.execute(params)
            : preparedQuery.execute();

        return execUni
            .map(rows -> rows.iterator().next().getLong("count"))
            .onFailure().invoke(error ->
                log.warn("Error executing count query on {}: {}", table, error.getMessage()));
    }

    /**
     * Count all rows in a table.
     *
     * @param table The table name
     * @return Future<Long> the count result
     */
    public Uni<Long> countAll(String table) {
        return count(table, null, null);
    }

    // ================================================================
    // Metrics Tracking
    // ================================================================

    /**
     * Track a metric event.
     *
     * @param eventName The metric event name (e.g., "metrics.message.sent")
     * @param data      The metric data
     */
    public void trackMetric(String eventName, JsonObject data) {
        if (!data.containsKey("timestamp")) {
            data.put("timestamp", LocalDateTime.now(ZoneOffset.UTC).toString());
        }
        eventBus.send(eventName, data);
    }

    /**
     * Track an entity creation metric.
     *
     * @param entityType The entity type (e.g., "message", "conversation", "character")
     * @param entityId   The created entity ID
     * @param userId     The user who created it
     */
    public void trackCreated(String entityType, String entityId, String userId) {
        trackMetric("metrics." + entityType + ".created", new JsonObject()
            .put(entityType + "Id", entityId)
            .put("userId", userId));
    }

    /**
     * Track an entity deletion metric.
     *
     * @param entityType The entity type
     * @param entityId   The deleted entity ID
     * @param userId     The user who deleted it
     */
    public void trackDeleted(String entityType, String entityId, String userId) {
        trackMetric("metrics." + entityType + ".deleted", new JsonObject()
            .put(entityType + "Id", entityId)
            .put("userId", userId));
    }

    /**
     * Track an entity update metric.
     *
     * @param entityType The entity type
     * @param entityId   The updated entity ID
     * @param userId     The user who updated it
     */
    public void trackUpdated(String entityType, String entityId, String userId) {
        trackMetric("metrics." + entityType + ".updated", new JsonObject()
            .put(entityType + "Id", entityId)
            .put("userId", userId));
    }

    // ================================================================
    // Event Publishing
    // ================================================================

    /**
     * Publish an event to Redis pub/sub and event bus.
     *
     * @param channel The channel name (e.g., "message:events")
     * @param eventType The event type (e.g., "sent", "edited", "deleted")
     * @param data The event data
     */
    public void publishEvent(String channel, String eventType, Object data) {
        JsonObject event = new JsonObject()
            .put("type", eventType)
            .put("timestamp", LocalDateTime.now(ZoneOffset.UTC).toString());

        if (data instanceof JsonObject) {
            event.put("data", data);
        } else if (data != null) {
            event.put("data", JsonObject.mapFrom(data));
        }

        // Publish to Redis pub/sub
        eventBus.send("redis.publish", new JsonObject()
            .put("channel", channel)
            .put("message", event.encode()));

        // Also publish to EventBus for subscriptions
        eventBus.publish(channel.replace(":", ".").replace("events", "updates"), event);
    }

    // ================================================================
    // UUID Helpers
    // ================================================================

    /**
     * Verify that a UUID matches the expected user.
     *
     * @param ownerId The owner UUID (can be null)
     * @param userId  The user ID string to compare
     * @return true if they match
     */
    public static boolean isOwner(UUID ownerId, String userId) {
        return ownerId != null && ownerId.toString().equals(userId);
    }

    /**
     * Generate a new UUID string.
     *
     * @return A new random UUID as string
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
