package com.eonmux.chatllm.map;

import com.eonmux.chatllm.map.generation.CulturalRegion;
import com.eonmux.chatllm.utils.ServiceHelper;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.map.generation.MapGenerator;
import com.eonmux.chatllm.map.models.GenerationConfig;
import com.eonmux.chatllm.map.models.GenerationResult;
import com.eonmux.chatllm.map.models.RoadSegment;
import com.eonmux.chatllm.map.models.TerrainData;
import com.eonmux.chatllm.models.MapCharacterPosition;
import com.eonmux.chatllm.models.MapItemPosition;
import com.eonmux.chatllm.models.MapLocation;
import com.eonmux.chatllm.models.WorldMap;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.eonmux.chatllm.map.MapScaleUtils.*;

@Slf4j
@ApplicationScoped
public class MapService {

    @Inject
    Pool mysqlPool;

    // ==================== Existing Map Operations ====================

    public Uni<List<WorldMap>> getMaps(String conversationId) {
        // metadata holds the full procedural map (roads, regions, waypoints).
        // Selecting it in a sorted list exceeds MySQL's sort buffer for large
        // worlds, so list queries read only the summary columns. Callers that
        // need the map body use getRoads/getCulturalRegions or getMap.
        String query = "SELECT mapId, conversationId, name, mapType, mapScale, imageId, "
            + "parentMapId, gridWidth, gridHeight, createdAt "
            + "FROM world_map WHERE conversationId = ? ORDER BY createdAt";
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                List<WorldMap> maps = new ArrayList<>();
                for (Row row : rows) maps.add(mapRowToWorldMap(row));
                return maps;
            });
    }

    public Uni<WorldMap> createMap(String conversationId, String name, String mapType,
                                       String mapScale, Integer gridWidth, Integer gridHeight) {
        String id = UUID.randomUUID().toString();
        String insert = "INSERT INTO world_map (mapId, conversationId, name, mapType, mapScale, gridWidth, gridHeight) VALUES (?, ?, ?, ?, ?, ?, ?)";
        return mysqlPool.preparedQuery(insert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(conversationId).addValue(name)
                .addValue(mapType != null ? mapType : "world")
                .addValue(mapScale != null ? mapScale : "region")
                .addValue(gridWidth).addValue(gridHeight))
            .map(v -> WorldMap.builder()
                .mapId(UUID.fromString(id))
                .conversationId(UUID.fromString(conversationId))
                .name(name)
                .mapType(mapType != null ? mapType : "world")
                .mapScale(mapScale != null ? mapScale : "region")
                .gridWidth(gridWidth)
                .gridHeight(gridHeight)
                .build());
    }

    public Uni<MapLocation> addLocation(String mapId, String name, String description,
                                            float x, float y, float z,
                                            String icon, String locationType, boolean isDiscovered) {
        String id = UUID.randomUUID().toString();
        String insert = "INSERT INTO map_location (locationId, mapId, name, description, x, y, z, icon, locationType, isDiscovered) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return mysqlPool.preparedQuery(insert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(mapId).addValue(name).addValue(description)
                .addValue(x).addValue(y).addValue(z).addValue(icon)
                .addValue(locationType).addValue(isDiscovered))
            .map(v -> MapLocation.builder()
                .locationId(UUID.fromString(id))
                .mapId(UUID.fromString(mapId))
                .name(name).description(description)
                .x(x).y(y).z(z).icon(icon).locationType(locationType)
                .isDiscovered(isDiscovered)
                .build());
    }

    public Uni<Void> updateCharacterPosition(String mapId, String characterId, String locationId,
                                                  Float x, Float y, Float z) {
        String id = UUID.randomUUID().toString();
        String upsert = """
            INSERT INTO map_character_position (id, mapId, characterId, locationId, x, y, z)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE locationId = VALUES(locationId), x = VALUES(x), y = VALUES(y), z = VALUES(z)
            """;
        return mysqlPool.preparedQuery(upsert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(mapId).addValue(characterId).addValue(locationId)
                .addValue(x != null ? x : 0f).addValue(y != null ? y : 0f).addValue(z != null ? z : 0f))
            .replaceWithVoid();
    }

    public Uni<Void> updateUserPosition(String mapId, String userId, String locationId,
                                             Float x, Float y, Float z) {
        String id = UUID.randomUUID().toString();
        String upsert = """
            INSERT INTO map_character_position (id, mapId, userId, locationId, x, y, z)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE locationId = VALUES(locationId), x = VALUES(x), y = VALUES(y), z = VALUES(z)
            """;
        return mysqlPool.preparedQuery(upsert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(mapId).addValue(userId).addValue(locationId)
                .addValue(x != null ? x : 0f).addValue(y != null ? y : 0f).addValue(z != null ? z : 0f))
            .replaceWithVoid();
    }

    public Uni<MapItemPosition> addItemPosition(String mapId, String conversationId, String itemName,
                                                     String itemDescription, String itemType,
                                                     float x, float y, float z,
                                                     String linkedLocationId, boolean isDiscovered) {
        String id = UUID.randomUUID().toString();
        String insert = """
            INSERT INTO map_item_position (id, mapId, conversationId, itemName, itemDescription, itemType, x, y, z, linkedLocationId, isDiscovered)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        return mysqlPool.preparedQuery(insert)
            .execute(Tuple.tuple()
                .addValue(id).addValue(mapId).addValue(conversationId).addValue(itemName)
                .addValue(itemDescription).addValue(itemType)
                .addValue(x).addValue(y).addValue(z).addValue(linkedLocationId).addValue(isDiscovered))
            .map(v -> MapItemPosition.builder()
                .id(UUID.fromString(id))
                .mapId(UUID.fromString(mapId))
                .conversationId(UUID.fromString(conversationId))
                .itemName(itemName).itemDescription(itemDescription).itemType(itemType)
                .x(x).y(y).z(z)
                .linkedLocationId(linkedLocationId != null ? UUID.fromString(linkedLocationId) : null)
                .isDiscovered(isDiscovered)
                .build());
    }

    public Uni<List<MapItemPosition>> getItemPositions(String mapId) {
        return mysqlPool.preparedQuery("SELECT * FROM map_item_position WHERE mapId = ? ORDER BY itemName")
            .execute(Tuple.of(mapId))
            .map(rows -> {
                List<MapItemPosition> items = new ArrayList<>();
                for (Row row : rows) items.add(mapRowToItemPosition(row));
                return items;
            });
    }

    public Uni<Boolean> removeItemPosition(String id) {
        return mysqlPool.preparedQuery("DELETE FROM map_item_position WHERE id = ?")
            .execute(Tuple.of(id))
            .map(result -> result.rowCount() > 0);
    }

    public Uni<MapItemPosition> discoverItem(String id) {
        return mysqlPool.preparedQuery("UPDATE map_item_position SET isDiscovered = TRUE WHERE id = ?")
            .execute(Tuple.of(id))
            .chain(v -> mysqlPool.preparedQuery("SELECT * FROM map_item_position WHERE id = ?")
                .execute(Tuple.of(id))
                .map(rows -> {
                    if (rows.size() == 0) throw new RuntimeException("Item not found: " + id);
                    return mapRowToItemPosition(rows.iterator().next());
                }));
    }

    public Uni<List<MapCharacterPosition>> getEntityPositions(String mapId) {
        return mysqlPool.preparedQuery("SELECT * FROM map_character_position WHERE mapId = ?")
            .execute(Tuple.of(mapId))
            .map(rows -> {
                List<MapCharacterPosition> positions = new ArrayList<>();
                for (Row row : rows) positions.add(mapRowToCharacterPosition(row));
                return positions;
            });
    }

    public Uni<List<MapLocation>> getLocations(String mapId) {
        return mysqlPool.preparedQuery("SELECT * FROM map_location WHERE mapId = ? ORDER BY name")
            .execute(Tuple.of(mapId))
            .map(rows -> {
                List<MapLocation> locs = new ArrayList<>();
                for (Row row : rows) locs.add(mapRowToLocation(row));
                return locs;
            });
    }

    public Uni<List<MapLocation>> getNearbyLocations(String mapId, float x, float y, float z, float radius) {
        String query = """
            SELECT * FROM map_location
            WHERE mapId = ?
              AND x BETWEEN ? AND ?
              AND y BETWEEN ? AND ?
              AND z BETWEEN ? AND ?
            ORDER BY name
            """;
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.tuple()
                .addValue(mapId).addValue(x - radius).addValue(x + radius)
                .addValue(y - radius).addValue(y + radius).addValue(z - radius).addValue(z + radius))
            .map(rows -> {
                List<MapLocation> locs = new ArrayList<>();
                float r2 = radius * radius;
                for (Row row : rows) {
                    MapLocation loc = mapRowToLocation(row);
                    float dx = loc.getX() - x;
                    float dy = loc.getY() - y;
                    float dz = loc.getZ() - z;
                    if (dx * dx + dy * dy + dz * dz <= r2) {
                        locs.add(loc);
                    }
                }
                return locs;
            });
    }

    public static double calculateDistance(float x1, float y1, float z1, float x2, float y2, float z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ==================== Single-Location Lookups ====================

    public Uni<MapLocation> getLocationById(String locationId) {
        return mysqlPool.preparedQuery("SELECT * FROM map_location WHERE locationId = ?")
            .execute(Tuple.of(locationId))
            .map(rows -> {
                if (rows.size() == 0) return null;
                return mapRowToLocation(rows.iterator().next());
            });
    }

    public Uni<MapLocation> findLocationByName(String mapId, String name) {
        // Try exact match first (case-insensitive)
        return mysqlPool.preparedQuery(
                "SELECT * FROM map_location WHERE mapId = ? AND LOWER(name) = LOWER(?) LIMIT 1")
            .execute(Tuple.of(mapId, name))
            .chain(rows -> {
                if (rows.size() > 0) return Uni.createFrom().item(mapRowToLocation(rows.iterator().next()));
                // Fall back to LIKE match (e.g. "Ashenmere, the Capital" -> match "Ashenmere")
                String firstWord = name.contains(",") ? name.split(",")[0].trim()
                    : name.contains(" ") ? name.split("\\s+")[0].trim()
                    : name;
                if (firstWord.length() < 3 || firstWord.equalsIgnoreCase(name)) {
                    return Uni.createFrom().nullItem();
                }
                return mysqlPool.preparedQuery(
                        "SELECT * FROM map_location WHERE mapId = ? AND LOWER(name) LIKE CONCAT(LOWER(?), '%') LIMIT 1")
                    .execute(Tuple.of(mapId, firstWord))
                    .map(likeRows -> likeRows.size() > 0 ? mapRowToLocation(likeRows.iterator().next()) : null);
            });
    }

    /**
     * Discover all undiscovered locations within a radius of (x, y, z).
     * Returns the list of newly discovered locations.
     */
    public Uni<List<MapLocation>> discoverNearbyLocations(String mapId, float x, float y, float z, float radius) {
        String query = """
            SELECT * FROM map_location
            WHERE mapId = ? AND isDiscovered = FALSE
              AND x BETWEEN ? AND ?
              AND y BETWEEN ? AND ?
            """;
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(mapId, x - radius, x + radius, y - radius, y + radius))
            .chain(rows -> {
                List<MapLocation> discovered = new ArrayList<>();
                float r2 = radius * radius;
                List<String> idsToUpdate = new ArrayList<>();
                for (Row row : rows) {
                    MapLocation loc = mapRowToLocation(row);
                    float dx = loc.getX() - x;
                    float dy = loc.getY() - y;
                    if (dx * dx + dy * dy <= r2) {
                        discovered.add(loc);
                        idsToUpdate.add(loc.getLocationId().toString());
                    }
                }
                if (idsToUpdate.isEmpty()) {
                    return Uni.createFrom().item(discovered);
                }
                // Batch-update discovered flag
                StringBuilder updateSql = new StringBuilder("UPDATE map_location SET isDiscovered = TRUE WHERE locationId IN (");
                Tuple params = Tuple.tuple();
                for (int i = 0; i < idsToUpdate.size(); i++) {
                    if (i > 0) updateSql.append(",");
                    updateSql.append("?");
                    params.addValue(idsToUpdate.get(i));
                }
                updateSql.append(")");
                return mysqlPool.preparedQuery(updateSql.toString())
                    .execute(params)
                    .map(v -> discovered);
            });
    }

    /**
     * Get the mapId for the first map associated with a conversation.
     */
    public Uni<String> getMapIdForConversation(String conversationId) {
        return getMaps(conversationId).map(maps -> {
            if (maps == null || maps.isEmpty()) return null;
            return maps.get(0).getMapId().toString();
        });
    }

    /**
     * Update the name and description of a map location (e.g. after LLM customization).
     */
    public Uni<Void> updateLocationNameAndDescription(String mapId, String locationId, String name, String description) {
        return mysqlPool.preparedQuery(
                "UPDATE map_location SET name = ?, description = ? WHERE mapId = ? AND locationId = ?")
            .execute(Tuple.of(name, description, mapId, locationId))
            .replaceWithVoid();
    }

    /**
     * Update the linkedSceneId on a map location.
     */
    public Uni<Void> updateLinkedSceneId(String locationId, String sceneId) {
        return mysqlPool.preparedQuery("UPDATE map_location SET linkedSceneId = ? WHERE locationId = ?")
            .execute(Tuple.of(sceneId, locationId))
            .replaceWithVoid();
    }

    // ==================== Procedural Generation ====================

    public Uni<WorldMap> generateProceduralMap(String conversationId, String name,
                                                    String mapScale, GenerationConfig config) {
        return Uni.createFrom().item(() -> {
            MapGenerator generator = new MapGenerator();
            return generator.generate(config);
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
        .chain(result -> {
            String mapId = UUID.randomUUID().toString();
            JsonObject metadata = serializeGenerationResult(result, config);

            int gridSize = result.getTerrain().getWidth();
            String insert = """
                INSERT INTO world_map (mapId, conversationId, name, mapType, mapScale, gridWidth, gridHeight, metadata)
                VALUES (?, ?, ?, 'generated', ?, ?, ?, ?)
                """;

            return mysqlPool.preparedQuery(insert)
                .execute(Tuple.tuple()
                    .addValue(mapId).addValue(conversationId).addValue(name).addValue(mapScale)
                    .addValue(gridSize).addValue(gridSize).addValue(metadata.encode()))
                .chain(v -> {
                    List<MapLocation> locations = result.getLocations();
                    if (locations.isEmpty()) {
                        return Uni.createFrom().item(WorldMap.builder()
                            .mapId(UUID.fromString(mapId))
                            .conversationId(UUID.fromString(conversationId))
                            .name(name)
                            .mapType("generated")
                            .mapScale(mapScale)
                            .gridWidth(gridSize)
                            .gridHeight(gridSize)
                            .metadata(metadata)
                            .build());
                    }

                    return batchInsertLocations(mapId, locations).map(inserted ->
                        WorldMap.builder()
                            .mapId(UUID.fromString(mapId))
                            .conversationId(UUID.fromString(conversationId))
                            .name(name)
                            .mapType("generated")
                            .mapScale(mapScale)
                            .gridWidth(gridSize)
                            .gridHeight(gridSize)
                            .metadata(metadata)
                            .locations(inserted)
                            .build()
                    );
                });
        });
    }

    public Uni<TerrainData> getTerrainData(String mapId) {
        return mysqlPool.preparedQuery("SELECT metadata FROM world_map WHERE mapId = ?")
            .execute(Tuple.of(mapId))
            .map(rows -> {
                if (rows.size() == 0) throw new RuntimeException("Map not found: " + mapId);
                Row row = rows.iterator().next();
                String metadataStr = ServiceHelper.safeGetJsonText(row, "metadata");
                if (metadataStr == null || metadataStr.isEmpty()) return null;
                JsonObject metadata = new JsonObject(metadataStr);
                return deserializeTerrainData(metadata);
            });
    }

    public Uni<List<RoadSegment>> getRoads(String mapId) {
        return mysqlPool.preparedQuery("SELECT metadata FROM world_map WHERE mapId = ?")
            .execute(Tuple.of(mapId))
            .map(rows -> {
                if (rows.size() == 0) throw new RuntimeException("Map not found: " + mapId);
                Row row = rows.iterator().next();
                String metadataStr = ServiceHelper.safeGetJsonText(row, "metadata");
                if (metadataStr == null || metadataStr.isEmpty()) return List.<RoadSegment>of();
                JsonObject metadata = new JsonObject(metadataStr);
                return deserializeRoads(metadata);
            });
    }

    /**
     * Reads cultural regions from the map metadata JSON.
     * Converts grid coordinates to world coordinates using the map's actual scale.
     */
    public Uni<List<com.eonmux.chatllm.map.models.CulturalRegion>> getCulturalRegions(String mapId) {
        return mysqlPool.preparedQuery("SELECT metadata, gridWidth, mapScale FROM world_map WHERE mapId = ?")
            .execute(Tuple.of(mapId))
            .map(rows -> {
                if (rows.size() == 0) throw new RuntimeException("Map not found: " + mapId);
                Row row = rows.iterator().next();
                String metadataStr = ServiceHelper.safeGetJsonText(row, "metadata");
                if (metadataStr == null || metadataStr.isEmpty()) return List.<com.eonmux.chatllm.map.models.CulturalRegion>of();

                JsonObject metadata = new JsonObject(metadataStr);
                JsonArray regionsJson = metadata.getJsonArray("culturalRegions");
                if (regionsJson == null) return List.<com.eonmux.chatllm.map.models.CulturalRegion>of();

                int gridSize = row.getInteger("gridWidth") != null ? row.getInteger("gridWidth") : 384;
                float worldSize = worldSizeForScale(row.getString("mapScale"));
                float worldOffset = worldSize * 0.5f;

                List<com.eonmux.chatllm.map.models.CulturalRegion> result = new ArrayList<>();
                for (int i = 0; i < regionsJson.size(); i++) {
                    JsonObject rj = regionsJson.getJsonObject(i);
                    int centerX = rj.getInteger("centerX");
                    int centerY = rj.getInteger("centerY");
                    float worldX = (centerX / (float) gridSize) * worldSize - worldOffset;
                    float worldY = (centerY / (float) gridSize) * worldSize - worldOffset;
                    result.add(com.eonmux.chatllm.map.models.CulturalRegion.builder()
                        .culture(rj.getString("culture"))
                        .name(rj.getString("name"))
                        .centerX(centerX)
                        .centerY(centerY)
                        .radius((rj.getFloat("radius") / gridSize) * worldSize)
                        .worldX(worldX)
                        .worldY(worldY)
                        .build());
                }
                return result;
            });
    }

    // ==================== Coordinate Helpers ====================

    // worldSizeForScale() is now in MapScaleUtils

    // ==================== Serialization Helpers ====================

    private JsonObject serializeGenerationResult(GenerationResult result, GenerationConfig config) {
        TerrainData terrain = result.getTerrain();
        JsonObject metadata = new JsonObject();

        // Terrain data
        JsonObject terrainJson = new JsonObject();
        terrainJson.put("width", terrain.getWidth());
        terrainJson.put("height", terrain.getHeight());
        terrainJson.put("heightmap", encodeFloatArray(terrain.getHeightmap()));
        terrainJson.put("biomeGrid", encodeIntArray(terrain.getBiomeGrid()));
        terrainJson.put("waterLevel", terrain.getWaterLevel());
        if (terrain.getSlopeGrid() != null) {
            terrainJson.put("slopeGrid", encodeFloatArray(terrain.getSlopeGrid()));
        }
        terrainJson.put("seed", terrain.getSeed());
        terrainJson.put("mapScale", terrain.getMapScale());
        terrainJson.put("theme", terrain.getTheme());
        metadata.put("terrain", terrainJson);

        // Roads
        JsonArray roadsJson = new JsonArray();
        for (RoadSegment road : result.getRoads()) {
            JsonObject roadJson = new JsonObject();
            roadJson.put("roadId", road.getRoadId());
            roadJson.put("fromLocationId", road.getFromLocationId());
            roadJson.put("toLocationId", road.getToLocationId());
            roadJson.put("roadType", road.getRoadType());
            JsonArray waypointsJson = new JsonArray();
            for (float[] wp : road.getWaypoints()) {
                waypointsJson.add(new JsonArray().add(wp[0]).add(wp[1]).add(wp[2]));
            }
            roadJson.put("waypoints", waypointsJson);
            roadsJson.add(roadJson);
        }
        metadata.put("roads", roadsJson);

        // Cultural regions
        if (result.getCulturalRegions() != null) {
            JsonArray regionsJson = new JsonArray();
            for (CulturalRegion region : result.getCulturalRegions()) {
                JsonObject regionJson = new JsonObject();
                regionJson.put("culture", region.culture().name());
                regionJson.put("name", region.name());
                regionJson.put("centerX", region.centerX());
                regionJson.put("centerY", region.centerY());
                regionJson.put("radius", region.radius());
                regionsJson.add(regionJson);
            }
            metadata.put("culturalRegions", regionsJson);
        }

        // Starting location ID for deterministic entity placement
        if (result.getStartingLocationId() != null) {
            metadata.put("startingLocationId", result.getStartingLocationId());
        }

        // Generation config for reproducibility
        JsonObject configJson = new JsonObject();
        configJson.put("seed", config.getSeed());
        configJson.put("theme", config.getTheme());
        metadata.put("generationConfig", configJson);

        return metadata;
    }

    private TerrainData deserializeTerrainData(JsonObject metadata) {
        JsonObject terrainJson = metadata.getJsonObject("terrain");
        if (terrainJson == null) return null;

        TerrainData.TerrainDataBuilder builder = TerrainData.builder()
            .width(terrainJson.getInteger("width"))
            .height(terrainJson.getInteger("height"))
            .heightmap(decodeFloatArray(terrainJson.getString("heightmap")))
            .biomeGrid(decodeIntArray(terrainJson.getString("biomeGrid")))
            .waterLevel(terrainJson.getFloat("waterLevel"))
            .seed(terrainJson.getLong("seed"))
            .mapScale(terrainJson.getString("mapScale"))
            .theme(terrainJson.getString("theme", "temperate"));

        String slopeGridStr = terrainJson.getString("slopeGrid");
        if (slopeGridStr != null) {
            builder.slopeGrid(decodeFloatArray(slopeGridStr));
        }

        return builder.build();
    }

    private List<RoadSegment> deserializeRoads(JsonObject metadata) {
        JsonArray roadsJson = metadata.getJsonArray("roads");
        if (roadsJson == null) return List.of();

        List<RoadSegment> roads = new ArrayList<>();
        for (int i = 0; i < roadsJson.size(); i++) {
            JsonObject roadJson = roadsJson.getJsonObject(i);
            List<float[]> waypoints = new ArrayList<>();
            JsonArray waypointsJson = roadJson.getJsonArray("waypoints");
            if (waypointsJson != null) {
                for (int j = 0; j < waypointsJson.size(); j++) {
                    JsonArray wp = waypointsJson.getJsonArray(j);
                    waypoints.add(new float[]{wp.getFloat(0), wp.getFloat(1), wp.getFloat(2)});
                }
            }
            roads.add(RoadSegment.builder()
                .roadId(roadJson.getString("roadId"))
                .fromLocationId(roadJson.getString("fromLocationId"))
                .toLocationId(roadJson.getString("toLocationId"))
                .roadType(roadJson.getString("roadType"))
                .waypoints(waypoints)
                .build());
        }
        return roads;
    }

    // encodeFloatArray, encodeIntArray, decodeFloatArray, decodeIntArray
    // are now in MapScaleUtils (static-imported above)

    // MySQL limit is 65,535 prepared statement params. At 10 params/location, max 6553 per INSERT.
    private static final int MAX_LOCATIONS_PER_BATCH = 6000;

    private Uni<List<MapLocation>> batchInsertLocations(String mapId, List<MapLocation> locations) {
        if (locations.isEmpty()) return Uni.createFrom().item(List.of());

        if (locations.size() <= MAX_LOCATIONS_PER_BATCH) {
            return insertLocationChunk(mapId, locations).map(v -> locations);
        }

        // Split into chunks and execute sequentially to avoid overwhelming the pool
        List<List<MapLocation>> chunks = new ArrayList<>();
        for (int i = 0; i < locations.size(); i += MAX_LOCATIONS_PER_BATCH) {
            int end = Math.min(i + MAX_LOCATIONS_PER_BATCH, locations.size());
            chunks.add(locations.subList(i, end));
        }

        Uni<Void> chain = Uni.createFrom().voidItem();
        for (List<MapLocation> chunk : chunks) {
            chain = chain.chain(v -> insertLocationChunk(mapId, chunk));
        }
        return chain.map(v -> locations);
    }

    private Uni<Void> insertLocationChunk(String mapId, List<MapLocation> chunk) {
        StringBuilder sb = new StringBuilder(
            "INSERT INTO map_location (locationId, mapId, name, description, x, y, z, icon, locationType, isDiscovered) VALUES ");
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            MapLocation loc = chunk.get(i);
            String locId = loc.getLocationId() != null ? loc.getLocationId().toString() : UUID.randomUUID().toString();
            params.add(locId);
            params.add(mapId);
            params.add(loc.getName());
            params.add(loc.getDescription());
            params.add(loc.getX());
            params.add(loc.getY());
            params.add(loc.getZ());
            params.add(loc.getIcon());
            params.add(loc.getLocationType());
            params.add(loc.getIsDiscovered() != null ? loc.getIsDiscovered() : true);
        }

        Tuple tuple = Tuple.tuple();
        for (Object p : params) tuple.addValue(p);

        return mysqlPool.preparedQuery(sb.toString())
            .execute(tuple)
            .replaceWithVoid();
    }

    // ==================== Row Mappers ====================

    private WorldMap mapRowToWorldMap(Row row) {
        WorldMap m = new WorldMap();
        m.setMapId(UUID.fromString(row.getString("mapId")));
        m.setConversationId(UUID.fromString(row.getString("conversationId")));
        m.setName(row.getString("name"));
        m.setMapType(row.getString("mapType"));
        m.setMapScale(row.getString("mapScale"));
        if (row.getString("imageId") != null) m.setImageId(UUID.fromString(row.getString("imageId")));
        if (row.getString("parentMapId") != null) m.setParentMapId(UUID.fromString(row.getString("parentMapId")));
        m.setGridWidth(row.getInteger("gridWidth"));
        m.setGridHeight(row.getInteger("gridHeight"));
        // Read metadata JSON column when the query selected it.
        JsonObject metadata = com.eonmux.chatllm.utils.ServiceHelper.safeGetJsonObject(row, "metadata");
        if (metadata != null) m.setMetadata(metadata);
        m.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        return m;
    }

    private MapLocation mapRowToLocation(Row row) {
        MapLocation l = new MapLocation();
        l.setLocationId(UUID.fromString(row.getString("locationId")));
        l.setMapId(UUID.fromString(row.getString("mapId")));
        l.setName(row.getString("name"));
        l.setDescription(row.getString("description"));
        l.setX(row.getFloat("x"));
        l.setY(row.getFloat("y"));
        l.setZ(row.getFloat("z"));
        l.setIcon(row.getString("icon"));
        l.setLocationType(row.getString("locationType"));
        if (row.getString("linkedSceneId") != null) l.setLinkedSceneId(UUID.fromString(row.getString("linkedSceneId")));
        l.setIsDiscovered(row.getBoolean("isDiscovered"));
        l.setMovementCost(row.getFloat("movementCost"));
        return l;
    }

    private MapCharacterPosition mapRowToCharacterPosition(Row row) {
        MapCharacterPosition p = new MapCharacterPosition();
        p.setId(UUID.fromString(row.getString("id")));
        p.setMapId(UUID.fromString(row.getString("mapId")));
        if (row.getString("characterId") != null) p.setCharacterId(UUID.fromString(row.getString("characterId")));
        if (row.getString("userId") != null) p.setUserId(UUID.fromString(row.getString("userId")));
        if (row.getString("locationId") != null) p.setLocationId(UUID.fromString(row.getString("locationId")));
        p.setX(row.getFloat("x"));
        p.setY(row.getFloat("y"));
        p.setZ(row.getFloat("z"));
        p.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return p;
    }

    private MapItemPosition mapRowToItemPosition(Row row) {
        MapItemPosition item = new MapItemPosition();
        item.setId(UUID.fromString(row.getString("id")));
        item.setMapId(UUID.fromString(row.getString("mapId")));
        item.setConversationId(UUID.fromString(row.getString("conversationId")));
        item.setItemName(row.getString("itemName"));
        item.setItemDescription(row.getString("itemDescription"));
        item.setItemType(row.getString("itemType"));
        item.setX(row.getFloat("x"));
        item.setY(row.getFloat("y"));
        item.setZ(row.getFloat("z"));
        if (row.getString("linkedLocationId") != null) {
            item.setLinkedLocationId(UUID.fromString(row.getString("linkedLocationId")));
        }
        item.setIsDiscovered(row.getBoolean("isDiscovered"));
        item.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        item.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return item;
    }
}
