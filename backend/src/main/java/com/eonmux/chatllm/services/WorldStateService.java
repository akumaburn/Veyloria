package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.WorldState;
import com.eonmux.chatllm.utils.ServiceHelper;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.mysqlclient.MySQLPool;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Slf4j
public class WorldStateService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;

    public Uni<WorldState> getOrCreateWorldState(String conversationId) {
        String query = "SELECT * FROM world_state WHERE conversationId = ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .chain(rows -> {
                if (rows.size() > 0) {
                    return Uni.createFrom().item(mapRowToWorldState(rows.iterator().next()));
                }
                return createWorldState(conversationId);
            });
    }

    private Uni<WorldState> createWorldState(String conversationId) {
        String id = UUID.randomUUID().toString();
        String insert = """
            INSERT INTO world_state (stateId, conversationId, timeOfDay, dayNumber, mood)
            VALUES (?, ?, 'morning', 1, 'neutral')
            """;
        return pool.preparedQuery(insert)
            .execute(Tuple.of(id, conversationId))
            .map(v -> WorldState.builder()
                .stateId(UUID.fromString(id))
                .conversationId(UUID.fromString(conversationId))
                .timeOfDay("morning")
                .dayNumber(1)
                .mood("neutral")
                .build());
    }

    /**
     * Applies a partial update and returns the stored state.
     *
     * <p>The row is created first when it does not exist. A plain UPDATE
     * silently changed nothing on the first write of a session, so the caller
     * received a fresh default state instead of the values it had just set.
     * The map stage then resolved to an unrelated settlement while the party
     * stood somewhere else.</p>
     */
    public Uni<WorldState> updateWorldState(String conversationId, JsonObject updates) {
        if (updates == null || updates.isEmpty()) {
            return getOrCreateWorldState(conversationId);
        }
        return getOrCreateWorldState(conversationId)
                .chain(current -> applyWorldStateUpdate(conversationId, updates));
    }

    private Uni<WorldState> applyWorldStateUpdate(String conversationId, JsonObject updates) {
        StringBuilder sql = new StringBuilder("UPDATE world_state SET ");
        java.util.List<Object> params = new java.util.ArrayList<>();
        boolean first = true;

        if (updates.containsKey("currentScene")) {
            if (!first) sql.append(", "); first = false;
            sql.append("currentScene = ?"); params.add(updates.getString("currentScene"));
        }
        if (updates.containsKey("currentLocation")) {
            if (!first) sql.append(", "); first = false;
            sql.append("currentLocation = ?"); params.add(updates.getString("currentLocation"));
        }
        if (updates.containsKey("currentLocationId")) {
            if (!first) sql.append(", "); first = false;
            sql.append("currentLocationId = ?"); params.add(updates.getString("currentLocationId"));
        }
        if (updates.containsKey("currentArea")) {
            if (!first) sql.append(", "); first = false;
            sql.append("currentArea = ?"); params.add(updates.getString("currentArea"));
        }
        if (updates.containsKey("currentAreaId")) {
            if (!first) sql.append(", "); first = false;
            sql.append("currentAreaId = ?"); params.add(updates.getString("currentAreaId"));
        }
        if (updates.containsKey("timeOfDay")) {
            if (!first) sql.append(", "); first = false;
            sql.append("timeOfDay = ?"); params.add(updates.getString("timeOfDay"));
        }
        if (updates.containsKey("dayNumber")) {
            if (!first) sql.append(", "); first = false;
            sql.append("dayNumber = ?"); params.add(updates.getInteger("dayNumber"));
        }
        if (updates.containsKey("weather")) {
            if (!first) sql.append(", "); first = false;
            sql.append("weather = ?"); params.add(updates.getString("weather"));
        }
        if (updates.containsKey("mood")) {
            if (!first) sql.append(", "); first = false;
            sql.append("mood = ?"); params.add(updates.getString("mood"));
        }
        if (updates.containsKey("globalFlags")) {
            if (!first) sql.append(", "); first = false;
            sql.append("globalFlags = JSON_MERGE_PATCH(COALESCE(globalFlags, '{}'), ?)");
            Object flagsVal = updates.getValue("globalFlags");
            String flagsJson = (flagsVal instanceof JsonObject)
                ? ((JsonObject) flagsVal).encode()
                : String.valueOf(flagsVal);
            params.add(flagsJson);
        }

        if (params.isEmpty()) {
            return getOrCreateWorldState(conversationId);
        }

        sql.append(" WHERE conversationId = ?");
        params.add(conversationId);

        return pool.preparedQuery(sql.toString())
            .execute(Tuple.from(params))
            .chain(v -> getOrCreateWorldState(conversationId));
    }

    public Uni<WorldState> initializeFromTemplate(String conversationId, String templateId) {
        String query = "SELECT defaultWorldState FROM story_template WHERE templateId = ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(templateId))
            .chain(rows -> {
                if (rows.size() == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Template not found: " + templateId));
                }
                Row row = rows.iterator().next();
                JsonObject defaults = ServiceHelper.safeGetJsonObject(row, "defaultWorldState");
                if (defaults == null) defaults = new JsonObject();

                String id = UUID.randomUUID().toString();
                String insert = """
                    INSERT INTO world_state (stateId, conversationId, templateId, timeOfDay, dayNumber, weather, mood, globalFlags)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE templateId = ?, timeOfDay = ?, dayNumber = ?, weather = ?, mood = ?,
                        globalFlags = VALUES(globalFlags)
                    """;
                String tod = defaults.getString("timeOfDay", "morning");
                int day = defaults.getInteger("dayNumber", 1);
                String weather = defaults.getString("weather", "clear");
                String mood = defaults.getString("mood", "neutral");

                return pool.preparedQuery(insert)
                    .execute(Tuple.tuple()
                        .addValue(id).addValue(conversationId).addValue(templateId).addValue(tod)
                        .addValue(day).addValue(weather).addValue(mood).addValue(defaults.encode())
                        .addValue(templateId).addValue(tod).addValue(day).addValue(weather).addValue(mood))
                    .chain(v -> getOrCreateWorldState(conversationId));
            });
    }

    public Uni<JsonObject> getWorldContextForPrompt(String conversationId) {
        return getOrCreateWorldState(conversationId)
            .map(ws -> {
                JsonObject ctx = new JsonObject();
                if (ws.getCurrentScene() != null) ctx.put("currentScene", ws.getCurrentScene());
                if (ws.getCurrentLocation() != null) ctx.put("currentLocation", ws.getCurrentLocation());
                if (ws.getCurrentLocationId() != null) ctx.put("currentLocationId", ws.getCurrentLocationId().toString());
                if (ws.getCurrentArea() != null) ctx.put("currentArea", ws.getCurrentArea());
                if (ws.getCurrentAreaId() != null) ctx.put("currentAreaId", ws.getCurrentAreaId().toString());
                if (ws.getTimeOfDay() != null) ctx.put("timeOfDay", ws.getTimeOfDay());
                if (ws.getDayNumber() != null) ctx.put("dayNumber", ws.getDayNumber());
                if (ws.getWeather() != null) ctx.put("weather", ws.getWeather());
                if (ws.getMood() != null) ctx.put("mood", ws.getMood());
                if (ws.getGlobalFlags() != null) ctx.put("globalFlags", ws.getGlobalFlags());
                if (ws.getActiveEffects() != null) ctx.put("activeEffects", ws.getActiveEffects());
                return ctx;
            });
    }

    public Uni<Void> advanceTime(String conversationId, String newTimeOfDay, Integer dayIncrement) {
        JsonObject updates = new JsonObject();
        if (newTimeOfDay != null) updates.put("timeOfDay", newTimeOfDay);
        if (dayIncrement != null && dayIncrement > 0) {
            return getOrCreateWorldState(conversationId)
                .chain(ws -> {
                    updates.put("dayNumber", (ws.getDayNumber() != null ? ws.getDayNumber() : 1) + dayIncrement);
                    return updateWorldState(conversationId, updates);
                })
                .replaceWithVoid();
        }
        return updateWorldState(conversationId, updates).replaceWithVoid();
    }

    private static final List<String> TIME_CYCLE = List.of("morning", "afternoon", "evening", "night");

    /**
     * Progress time-of-day through the cycle: morning -> afternoon -> evening -> night -> morning.
     * Increments dayNumber on wraparound (night -> morning).
     */
    public Uni<Void> progressTimeOfDay(String conversationId) {
        return getOrCreateWorldState(conversationId)
            .chain(ws -> {
                String current = ws.getTimeOfDay() != null ? ws.getTimeOfDay() : "morning";
                int idx = TIME_CYCLE.indexOf(current);
                int nextIdx = (idx + 1) % TIME_CYCLE.size();
                String nextTime = TIME_CYCLE.get(nextIdx);
                boolean wrapsAround = nextIdx == 0;

                JsonObject updates = new JsonObject().put("timeOfDay", nextTime);
                if (wrapsAround) {
                    updates.put("dayNumber", (ws.getDayNumber() != null ? ws.getDayNumber() : 1) + 1);
                }
                return updateWorldState(conversationId, updates).replaceWithVoid();
            });
    }

    /**
     * Convert a WorldState to a JsonObject matching the GraphQL WorldState type fields.
     */
    public static JsonObject toGraphQL(WorldState ws) {
        JsonObject obj = new JsonObject()
            .put("stateId", ws.getStateId() != null ? ws.getStateId().toString() : null)
            .put("conversationId", ws.getConversationId() != null ? ws.getConversationId().toString() : null);
        if (ws.getTemplateId() != null) obj.put("templateId", ws.getTemplateId());
        obj.put("currentScene", ws.getCurrentScene());
        obj.put("currentLocation", ws.getCurrentLocation());
        if (ws.getCurrentLocationId() != null) obj.put("currentLocationId", ws.getCurrentLocationId().toString());
        obj.put("currentArea", ws.getCurrentArea());
        if (ws.getCurrentAreaId() != null) obj.put("currentAreaId", ws.getCurrentAreaId().toString());
        obj.put("timeOfDay", ws.getTimeOfDay());
        obj.put("dayNumber", ws.getDayNumber());
        obj.put("weather", ws.getWeather());
        obj.put("mood", ws.getMood());
        if (ws.getGlobalFlags() != null) obj.put("globalFlags", ws.getGlobalFlags());
        if (ws.getLocations() != null) obj.put("locations", ws.getLocations());
        if (ws.getActiveEffects() != null) obj.put("activeEffects", ws.getActiveEffects());
        if (ws.getNarratorNotes() != null) obj.put("narratorNotes", ws.getNarratorNotes());
        return obj;
    }

    private WorldState mapRowToWorldState(Row row) {
        WorldState ws = new WorldState();
        ws.setStateId(UUID.fromString(row.getString("stateId")));
        ws.setConversationId(UUID.fromString(row.getString("conversationId")));
        ws.setTemplateId(row.getString("templateId"));
        ws.setCurrentScene(row.getString("currentScene"));
        ws.setCurrentLocation(row.getString("currentLocation"));
        if (row.getString("currentLocationId") != null) {
            ws.setCurrentLocationId(UUID.fromString(row.getString("currentLocationId")));
        }
        ws.setCurrentArea(row.getString("currentArea"));
        if (row.getString("currentAreaId") != null) {
            ws.setCurrentAreaId(UUID.fromString(row.getString("currentAreaId")));
        }
        ws.setTimeOfDay(row.getString("timeOfDay"));
        ws.setDayNumber(row.getInteger("dayNumber"));
        ws.setWeather(row.getString("weather"));
        ws.setMood(row.getString("mood"));
        ws.setGlobalFlags(safeGetJsonObject(row, "globalFlags", ws.getStateId()));
        ws.setLocations(safeGetJsonObject(row, "locations", ws.getStateId()));
        ws.setActiveEffects(safeGetJsonObject(row, "activeEffects", ws.getStateId()));
        ws.setNarratorNotes(safeGetJsonObject(row, "narratorNotes", ws.getStateId()));
        ws.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        ws.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return ws;
    }

    /**
     * Safely extract a JSON column that may come back as String (MariaDB LONGTEXT) or JsonObject.
     * Delegates to the centralized ServiceHelper utility.
     */
    private JsonObject safeGetJsonObject(Row row, String column, java.util.UUID stateId) {
        return ServiceHelper.safeGetJsonObject(row, column);
    }
}
