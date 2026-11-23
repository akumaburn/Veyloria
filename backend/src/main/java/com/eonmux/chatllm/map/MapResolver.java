package com.eonmux.chatllm.map;

import com.eonmux.chatllm.config.RoleplayEventAddresses;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.utils.ServiceHelper;
import com.eonmux.chatllm.map.models.CulturalRegion;
import com.eonmux.chatllm.map.models.GenerationConfig;
import com.eonmux.chatllm.map.models.JourneyState;
import com.eonmux.chatllm.map.models.RoadSegment;
import com.eonmux.chatllm.map.models.TerrainData;
import com.eonmux.chatllm.map.models.TerrainDataView;
import com.eonmux.chatllm.map.models.TravelResult;
import com.eonmux.chatllm.models.DistanceResult;
import com.eonmux.chatllm.models.LocalMap;
import com.eonmux.chatllm.models.inputs.AddMapItemPositionInput;
import com.eonmux.chatllm.models.inputs.AddMapLocationInput;
import com.eonmux.chatllm.models.inputs.GenerateMapInput;
import com.eonmux.chatllm.models.inputs.MoveCharacterOnMapInput;
import com.eonmux.chatllm.models.inputs.UpdateEntityPositionInput;
import com.eonmux.chatllm.models.inputs.WorldMapInput;
import com.eonmux.chatllm.models.MapCharacterPosition;
import com.eonmux.chatllm.models.MapItemPosition;
import com.eonmux.chatllm.models.MapLocation;
import com.eonmux.chatllm.models.WorldMap;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.services.auth.AuthContext;
import com.eonmux.chatllm.services.WorldStateService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GraphQL resolver for world maps, locations, entity positions, and item positions.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class MapResolver {

    @Inject
    Vertx vertx;

    @Inject
    Pool mysqlPool;

    @Inject
    MapService mapService;

    @Inject
    TravelService travelService;

    @Inject
    JourneyService journeyService;

    @Inject com.eonmux.chatllm.services.BattleService battleService;

    @Inject
    StageService stageService;

    @Inject
    WorldStateService worldStateService;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    ServiceHelper serviceHelper;

    private AuthContext requireAuth() {
        return graphQLAuthContext.requireAuth();
    }

    /** Table and column are internal constants, never client-supplied identifiers. */
    private Uni<Boolean> recordAccess(String table, String column, String id, String callerId) {
        return mysqlPool.preparedQuery("SELECT mapId FROM " + table + " WHERE " + column + " = ?")
            .execute(Tuple.of(id))
            .chain(rows -> rows.size() == 0
                ? Uni.createFrom().item(false)
                : serviceHelper.verifyMapAccess(rows.iterator().next().getString("mapId"), callerId));
    }

    // ==================== Map Queries ====================

    @Query("worldMaps")
    public Uni<List<WorldMap>> worldMaps(String conversationId) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyConversationAccess(conversationId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return mapService.getMaps(conversationId);
        });
    }

    @Query("worldMap")
    public Uni<WorldMap> worldMap(String mapId) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyMapAccess(mapId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            String query = "SELECT * FROM world_map WHERE mapId = ?";
            return mysqlPool.preparedQuery(query)
                .execute(Tuple.of(mapId))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return null;
                    }
                    return mapWorldMapRow(rows.iterator().next());
                });
        });
    }

    @Query("localMap")
    public Uni<LocalMap> localMap(String conversationId) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyConversationAccess(conversationId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return stageService.getLocalMap(conversationId);
        });
    }

    @Query("mapLocations")
    public Uni<List<MapLocation>> mapLocations(String mapId) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyMapAccess(mapId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return resolveLocations(mapId);
        });
    }

    @Query("entityPositions")
    public Uni<List<MapCharacterPosition>> entityPositions(String mapId) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyMapAccess(mapId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return mapService.getEntityPositions(mapId);
        });
    }

    @Query("mapItemPositions")
    public Uni<List<MapItemPosition>> mapItemPositions(String mapId) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyMapAccess(mapId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return mapService.getItemPositions(mapId);
        });
    }

    @Query("nearbyLocations")
    public Uni<List<MapLocation>> nearbyLocations(String mapId, float x, float y, float z, float radius) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyMapAccess(mapId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return mapService.getNearbyLocations(mapId, x, y, z, radius);
        });
    }

    @Query("calculateDistance")
    public Uni<DistanceResult> calculateDistance(float x1, float y1, float z1,
                                                       float x2, float y2, float z2) {
        requireAuth();

        double distance = MapService.calculateDistance(x1, y1, z1, x2, y2, z2);

        return Uni.createFrom().item(DistanceResult.builder()
            .distance((float) distance)
            .fromX(x1).fromY(y1).fromZ(z1)
            .toX(x2).toY(y2).toZ(z2)
            .build());
    }

    // ==================== Map Mutations ====================

    @Mutation("createWorldMap")
    public Uni<WorldMap> createWorldMap(WorldMapInput input) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyConversationAccess(input.getConversationId(), callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            String conversationId = input.getConversationId();
            String name = input.getName();
            String mapType = input.getMapType() != null ? input.getMapType() : "world";
            String mapScale = input.getMapScale() != null ? input.getMapScale() : "region";
            Integer gridWidth = input.getGridWidth();
            Integer gridHeight = input.getGridHeight();

            String mapId = UUID.randomUUID().toString();
            String insertQuery = """
                INSERT INTO world_map (mapId, conversationId, name, mapType, mapScale, gridWidth, gridHeight)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

            return mysqlPool.preparedQuery(insertQuery)
                .execute(Tuple.tuple()
                    .addValue(mapId)
                    .addValue(conversationId)
                    .addValue(name)
                    .addValue(mapType)
                    .addValue(mapScale)
                    .addValue(gridWidth)
                    .addValue(gridHeight))
                .map(insertResult -> WorldMap.builder()
                    .mapId(UUID.fromString(mapId))
                    .conversationId(UUID.fromString(conversationId))
                    .name(name)
                    .mapType(mapType)
                    .mapScale(mapScale)
                    .gridWidth(gridWidth)
                    .gridHeight(gridHeight)
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                    .build());
        });
    }

    @Mutation("addMapLocation")
    public Uni<MapLocation> addMapLocation(AddMapLocationInput input) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyMapAccess(input.getMapId(), callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            String mapId = input.getMapId();
            String name = input.getName();
            String description = input.getDescription();
            float x = input.getX() != null ? input.getX() : 0f;
            float y = input.getY() != null ? input.getY() : 0f;
            float z = input.getZ() != null ? input.getZ() : 0f;
            String icon = input.getIcon();
            String locationType = input.getLocationType();
            String linkedSceneId = input.getLinkedSceneId();
            boolean isDiscovered = input.getIsDiscovered() != null ? input.getIsDiscovered() : false;

            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                return Uni.createFrom().failure(new IllegalArgumentException("Coordinates must be finite"));
            }
            String locationId = UUID.randomUUID().toString();
            String insertQuery = """
                INSERT INTO map_location (locationId, mapId, name, description, x, y, z, icon, locationType, linkedSceneId, isDiscovered)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

            return mysqlPool.preparedQuery(insertQuery)
                .execute(Tuple.tuple()
                    .addValue(locationId)
                    .addValue(mapId)
                    .addValue(name)
                    .addValue(description)
                    .addValue(x)
                    .addValue(y)
                    .addValue(z)
                    .addValue(icon)
                    .addValue(locationType)
                    .addValue(linkedSceneId)
                    .addValue(isDiscovered))
                .map(insertResult -> MapLocation.builder()
                    .locationId(UUID.fromString(locationId))
                    .mapId(UUID.fromString(mapId))
                    .name(name)
                    .description(description)
                    .x(x).y(y).z(z)
                    .icon(icon)
                    .locationType(locationType)
                    .linkedSceneId(linkedSceneId != null ? UUID.fromString(linkedSceneId) : null)
                    .isDiscovered(isDiscovered)
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                    .build());
        });
    }

    @Mutation("removeMapLocation")
    public Uni<Boolean> removeMapLocation(String locationId) {
        String callerId = requireAuth().getUserId().toString();
        return recordAccess("map_location", "locationId", locationId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return mysqlPool.preparedQuery("DELETE FROM map_location WHERE locationId = ?")
                .execute(Tuple.of(locationId))
                .map(result -> result.rowCount() > 0);
        });
    }

    @Mutation("discoverLocation")
    public Uni<MapLocation> discoverLocation(String locationId) {
        String callerId = requireAuth().getUserId().toString();
        return recordAccess("map_location", "locationId", locationId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return mysqlPool.preparedQuery("UPDATE map_location SET isDiscovered = TRUE WHERE locationId = ?")
                .execute(Tuple.of(locationId))
                .chain(updateResult ->
                    mysqlPool.preparedQuery("SELECT * FROM map_location WHERE locationId = ?")
                        .execute(Tuple.of(locationId))
                        .chain(rows -> {
                            if (rows.size() == 0) {
                                return Uni.createFrom().failure(new RuntimeException("Location not found"));
                            }
                            MapLocation location = mapLocationRow(rows.iterator().next());

                            // Publish MAP_LOCATION_DISCOVERED event for subscription
                            String mapId = location.getMapId() != null ? location.getMapId().toString() : null;
                            if (mapId != null) {
                                mysqlPool.preparedQuery("SELECT conversationId FROM world_map WHERE mapId = ?")
                                    .execute(Tuple.of(mapId))
                                    .subscribe().with(
                                        mapRows -> {
                                            if (mapRows.size() > 0) {
                                                String conversationId = mapRows.iterator().next().getString("conversationId");
                                                vertx.eventBus().publish(
                                                    RoleplayEventAddresses.MAP_LOCATION_DISCOVERED + conversationId,
                                                    locationToJson(location));
                                            }
                                        },
                                        err -> log.warn("Failed to publish location discovered event for {}", locationId, err)
                                    );
                            }

                            return Uni.createFrom().item(location);
                        })
                );
        });
    }

    @Mutation("moveCharacterOnMap")
    public Uni<MapCharacterPosition> moveCharacterOnMap(MoveCharacterOnMapInput input) {
        return updateEntityPosition(new UpdateEntityPositionInput(input.getMapId(), input.getCharacterId(),
            input.getUserId(), input.getLocationId(), input.getX(), input.getY(), input.getZ()));
    }

    @Mutation("updateEntityPosition")
    public Uni<MapCharacterPosition> updateEntityPosition(UpdateEntityPositionInput input) {
        String callerId = requireAuth().getUserId().toString();
        String mapId = input.getMapId();
        String characterId = input.getCharacterId();
        String userId = input.getUserId();
        String locationId = input.getLocationId();
        if ((characterId == null) == (userId == null)) {
            return Uni.createFrom().failure(new IllegalArgumentException("Exactly one of characterId or userId is required"));
        }
        if (userId != null && !callerId.equals(userId)) {
            return Uni.createFrom().failure(new SecurityException("Cannot move another player"));
        }
        return serviceHelper.verifyMapAccess(mapId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            Uni<Boolean> entityAllowed = characterId == null ? Uni.createFrom().item(true)
                : mysqlPool.preparedQuery("""
                    SELECT cc.characterId FROM conversation_character cc
                    JOIN world_map wm ON wm.conversationId = cc.conversationId
                    WHERE wm.mapId = ? AND cc.characterId = ? AND cc.isActive = TRUE
                    """).execute(Tuple.of(mapId, characterId)).map(rows -> rows.size() > 0);
            return mysqlPool.preparedQuery("""
                SELECT b.battleId FROM world_map wm JOIN battle b ON b.conversationId = wm.conversationId
                WHERE wm.mapId = ? AND b.status = 'ACTIVE'
                UNION ALL SELECT j.journeyId FROM journey_state j WHERE j.mapId = ? AND j.status IN ('TRAVELING','PAUSED') LIMIT 1
                """).execute(Tuple.of(mapId, mapId)).invoke(rows -> {
                    if (rows.size() > 0) throw new IllegalStateException("Resolve the encounter or finish the route before repositioning entities");
                }).chain(() -> entityAllowed).chain(valid -> {
                if (!valid) return Uni.createFrom().failure(new IllegalArgumentException("Character is not active in this map's conversation"));
                Uni<MapLocation> location = locationId == null ? Uni.createFrom().nullItem()
                    : mapService.getLocationById(locationId);
                return location.chain(loc -> {
                    if (locationId != null && (loc == null || loc.getMapId() == null || !mapId.equals(loc.getMapId().toString()))) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Location does not belong to this map"));
                    }
                    float x = loc != null ? loc.getX() : input.getX() != null ? input.getX() : 0f;
                    float y = loc != null ? loc.getY() : input.getY() != null ? input.getY() : 0f;
                    float z = loc != null ? loc.getZ() : input.getZ() != null ? input.getZ() : 0f;
                    if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Coordinates must be finite"));
                    }
                    Uni<Void> update = characterId == null
                        ? mapService.updateUserPosition(mapId, userId, locationId, x, y, z)
                        : mapService.updateCharacterPosition(mapId, characterId, locationId, x, y, z);
                    return update.chain(v -> mysqlPool.preparedQuery(characterId == null
                            ? "SELECT * FROM map_character_position WHERE mapId = ? AND userId = ?"
                            : "SELECT * FROM map_character_position WHERE mapId = ? AND characterId = ?")
                        .execute(Tuple.of(mapId, characterId == null ? userId : characterId))
                        .map(rows -> {
                            if (rows.size() == 0) throw new IllegalStateException("Position not found after upsert");
                            MapCharacterPosition position = mapPositionRow(rows.iterator().next());
                            vertx.eventBus().publish(RoleplayEventAddresses.MAP_ENTITY_MOVED + mapId, positionToJson(position));
                            return position;
                        }));
                });
            });
        });
    }

    @Mutation("addMapItemPosition")
    public Uni<MapItemPosition> addMapItemPosition(AddMapItemPositionInput input) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyMapAccess(input.getMapId(), callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            String mapId = input.getMapId();
            String conversationId = input.getConversationId();
            String itemName = input.getItemName();
            String itemDescription = input.getItemDescription();
            String itemType = input.getItemType();
            float x = input.getX() != null ? input.getX() : 0f;
            float y = input.getY() != null ? input.getY() : 0f;
            float z = input.getZ() != null ? input.getZ() : 0f;
            String linkedLocationId = input.getLinkedLocationId();
            boolean isDiscovered = input.getIsDiscovered() != null ? input.getIsDiscovered() : false;

            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                return Uni.createFrom().failure(new IllegalArgumentException("Coordinates must be finite"));
            }
            return mysqlPool.preparedQuery("""
                    SELECT wm.mapId FROM world_map wm WHERE wm.mapId = ? AND wm.conversationId = ?
                    AND (? IS NULL OR EXISTS (SELECT 1 FROM map_location ml WHERE ml.locationId = ? AND ml.mapId = wm.mapId))
                    """).execute(Tuple.of(mapId, conversationId, linkedLocationId, linkedLocationId))
                .chain(rows -> {
                    if (rows.size() == 0) return Uni.createFrom().failure(
                        new IllegalArgumentException("Item conversation and linked location must belong to the map"));
                    return mapService.addItemPosition(mapId, conversationId, itemName, itemDescription, itemType,
                        x, y, z, linkedLocationId, isDiscovered);
                });
        });
    }

    @Mutation("removeMapItemPosition")
    public Uni<Boolean> removeMapItemPosition(String id) {
        String callerId = requireAuth().getUserId().toString();
        return recordAccess("map_item_position", "id", id, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return mapService.removeItemPosition(id);
        });
    }

    @Mutation("discoverMapItem")
    public Uni<MapItemPosition> discoverMapItem(String id) {
        String callerId = requireAuth().getUserId().toString();
        return recordAccess("map_item_position", "id", id, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return mapService.discoverItem(id)
                .map(item -> {
                    JsonObject json = new JsonObject()
                        .put("id", item.getId().toString())
                        .put("mapId", item.getMapId().toString())
                        .put("conversationId", item.getConversationId().toString())
                        .put("itemName", item.getItemName())
                        .put("isDiscovered", item.getIsDiscovered());
                    vertx.eventBus().publish(RoleplayEventAddresses.MAP_ITEM_DISCOVERED + item.getConversationId().toString(), json);
                    return item;
                });
        });
    }

    // ==================== Procedural Generation ====================

    @Mutation("generateProceduralMap")
    public Uni<WorldMap> generateProceduralMap(GenerateMapInput input) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyConversationAccess(input.getConversationId(), callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            String conversationId = input.getConversationId();
            String name = input.getName();
            String mapScale = input.getMapScale() != null ? input.getMapScale() : "region";
            String seedStr = input.getSeed();
            Double waterLevelNum = input.getWaterLevel();
            Integer locationCountNum = input.getLocationCount();
            String theme = input.getTheme() != null ? input.getTheme() : "temperate";

            long seed = seedStr != null ? seedStr.hashCode() : System.nanoTime();
            float waterLevel = waterLevelNum != null ? waterLevelNum.floatValue() : 0.22f;
            int locationCount = locationCountNum != null ? locationCountNum.intValue() : -1;

            int gridResolution;
            switch (mapScale) {
                case "city" -> gridResolution = 384;
                case "continent" -> gridResolution = 768;
                default -> gridResolution = 512;
            }

            GenerationConfig config = GenerationConfig.builder()
                .seed(seed)
                .mapScale(mapScale)
                .gridResolution(gridResolution)
                .waterLevel(waterLevel)
                .locationCount(locationCount)
                .theme(theme)
                .build();

            String userId = callerId;

            return mapService.generateProceduralMap(conversationId, name, mapScale, config)
                .chain(worldMap -> {
                    String mapId = worldMap.getMapId().toString();

                    // Extract starting location ID from metadata for deterministic placement
                    String startingLocationId;
                    if (worldMap.getMetadata() != null) {
                        startingLocationId = worldMap.getMetadata().getString("startingLocationId");
                    } else {
                        startingLocationId = null;
                    }

                    // Position entities at the starting location and wait for completion
                    return positionEntitiesOnGeneratedMap(mapId, userId, conversationId, startingLocationId)
                        .chain(v ->
                            // Customize starting location via LLM
                            customizeStartingLocation(mapId, conversationId, startingLocationId)
                                .map(ignored -> worldMap)
                                .onFailure().recoverWithItem(err -> {
                                    log.warn("LLM customization of starting location failed, continuing", err);
                                    return worldMap;
                                })
                        )
                        .chain(map -> stageService.ensureStage(conversationId).replaceWith(map))
                        .onFailure().recoverWithItem(err -> {
                            // Position failure is non-fatal -- map was created successfully
                            log.warn("Failed to position entities on map {}, continuing", mapId, err);
                            return worldMap;
                        });
                });
        });
    }

    @Query("terrainData")
    public Uni<TerrainDataView> terrainData(String mapId) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyMapAccess(mapId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return mapService.getTerrainData(mapId).map(terrain -> {
                if (terrain == null) return null;
                return TerrainDataView.builder()
                    .width(terrain.getWidth())
                    .height(terrain.getHeight())
                    .heightmap(encodeFloatArray(terrain.getHeightmap()))
                    .biomeGrid(encodeIntArray(terrain.getBiomeGrid()))
                    .waterLevel(terrain.getWaterLevel())
                    .seed(String.valueOf(terrain.getSeed()))
                    .mapScale(terrain.getMapScale())
                    .theme(terrain.getTheme())
                    .build();
            });
        });
    }

    @Query("mapRoads")
    public Uni<List<RoadSegment>> mapRoads(String mapId) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyMapAccess(mapId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return mapService.getRoads(mapId);
        });
    }

    @Query("culturalRegions")
    public Uni<List<CulturalRegion>> culturalRegions(String mapId) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyMapAccess(mapId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return mapService.getCulturalRegions(mapId);
        });
    }

    // ==================== Type-level Resolvers ====================

    /**
     * Lazy resolver for WorldMap.locations.
     * If already populated (e.g. from generateProceduralMap), returns the data directly.
     * Otherwise queries the database on demand.
     */
    public Uni<List<MapLocation>> locations(@Source WorldMap worldMap) {
        if (worldMap.getLocations() != null && !worldMap.getLocations().isEmpty()) {
            return Uni.createFrom().item(worldMap.getLocations());
        }
        return resolveLocations(worldMap.getMapId().toString());
    }

    /**
     * Resolves the locations field on a WorldMap object by mapId.
     * Used by the @Source resolver above and by the mapLocations top-level query.
     */
    public Uni<List<MapLocation>> resolveLocations(String mapId) {
        return mysqlPool.preparedQuery("SELECT * FROM map_location WHERE mapId = ? ORDER BY name")
            .execute(Tuple.of(mapId))
            .map(rows -> {
                List<MapLocation> locations = new ArrayList<>();
                rows.forEach(row -> locations.add(mapLocationRow(row)));
                return locations;
            });
    }

    // ==================== Entity Positioning ====================

    private static final Set<String> SETTLEMENT_TYPES = Set.of("town", "village", "city", "camp", "port");

    /**
     * Positions the authenticated user and all active conversation characters
     * at the specified starting location on a newly generated map.
     * If startingLocationId is null, falls back to any settlement or first location.
     * Returns a Uni that completes once all positions are written to DB.
     */
    private Uni<Void> positionEntitiesOnGeneratedMap(String mapId, String userId,
                                                         String conversationId, String startingLocationId) {
        String locationQuery = "SELECT * FROM map_location WHERE mapId = ?";

        return mysqlPool.preparedQuery(locationQuery)
            .execute(Tuple.of(mapId))
            .chain(rows -> {
                if (rows.size() == 0) {
                    log.warn("No locations found on map {} -- cannot position entities", mapId);
                    return Uni.createFrom().voidItem();
                }

                // Try to find the exact starting location by ID
                Row targetLoc = null;
                List<Row> settlements = new ArrayList<>();
                List<Row> allLocs = new ArrayList<>();
                for (var row : rows) {
                    allLocs.add(row);
                    if (startingLocationId != null && startingLocationId.equals(row.getString("locationId"))) {
                        targetLoc = row;
                    }
                    String locType = row.getString("locationType");
                    if (locType != null && SETTLEMENT_TYPES.contains(locType)) {
                        settlements.add(row);
                    }
                }

                // Fallback: if startingLocationId wasn't found, pick first settlement or first location
                if (targetLoc == null) {
                    log.warn("Starting location {} not found on map {}, falling back", startingLocationId, mapId);
                    targetLoc = settlements.isEmpty() ? allLocs.get(0) : settlements.get(0);
                }

                return positionAllEntities(mapId, userId, conversationId, targetLoc);
            });
    }

    /**
     * Writes position records for the user and all active conversation characters
     * at the given location row.
     */
    private Uni<Void> positionAllEntities(String mapId, String userId, String conversationId,
                                              Row loc) {
        String locationId = loc.getString("locationId");
        float x = loc.getFloat("x");
        float y = loc.getFloat("y");
        float z = loc.getFloat("z");

        // Position the auth user
        Uni<Void> userUni = mapService.updateUserPosition(mapId, userId, locationId, x, y, z);

        // Position all characters currently onstage
        Uni<Void> charUni = mysqlPool.preparedQuery(
                "SELECT characterId FROM conversation_character WHERE conversationId = ? AND isActive = TRUE"
                        + " AND (isIntroduced = TRUE OR isIntroduced IS NULL)")
            .execute(Tuple.of(conversationId))
            .chain(charRows -> {
                List<Uni<Void>> charPositionUnis = new ArrayList<>();
                for (var charRow : charRows) {
                    String characterId = charRow.getString("characterId");
                    charPositionUnis.add(
                        mapService.updateCharacterPosition(mapId, characterId, locationId, x, y, z));
                }
                if (charPositionUnis.isEmpty()) {
                    return Uni.createFrom().voidItem();
                }
                return Uni.combine().all().unis(charPositionUnis).discardItems();
            });

        return Uni.combine().all().unis(userUni, charUni).discardItems()
            .invoke(v -> log.info("Positioned user + characters at location '{}' on map {}",
                loc.getString("name"), mapId));
    }

    // ==================== LLM Starting Location Customization ====================

    /**
     * Uses the LLM to customize the starting location's name and description
     * to fit the roleplay's story context. Updates both the map location and
     * world_state.currentLocation. Non-fatal: failures are caught by the caller.
     */
    private Uni<Void> customizeStartingLocation(String mapId, String conversationId, String startingLocationId) {
        if (startingLocationId == null) {
            return Uni.createFrom().voidItem();
        }

        // Fetch the location and conversation description in parallel
        Uni<Row> locUni = mysqlPool.preparedQuery(
                "SELECT * FROM map_location WHERE locationId = ?")
            .execute(Tuple.of(startingLocationId))
            .map(rows -> rows.size() > 0 ? rows.iterator().next() : null);

        Uni<Row> convUni = mysqlPool.preparedQuery(
                "SELECT title, description FROM conversation WHERE conversationGUID = ?")
            .execute(Tuple.of(conversationId))
            .map(rows -> rows.size() > 0 ? rows.iterator().next() : null);

        return Uni.combine().all().unis(locUni, convUni).asTuple().chain(tuple -> {
            Row locRow = tuple.getItem1();
            Row convRow = tuple.getItem2();

            if (locRow == null) {
                log.warn("Starting location {} not found for LLM customization", startingLocationId);
                return Uni.createFrom().voidItem();
            }

            String locName = locRow.getString("name");
            String locType = locRow.getString("locationType");
            String locDescription = locRow.getString("description");
            String storyTitle = convRow != null ? convRow.getString("title") : "Untitled Story";
            String storyDescription = convRow != null ? convRow.getString("description") : "";

            String systemPrompt = """
                You are a creative writing assistant helping to name and describe a starting location \
                for a roleplay adventure. Given the story context and the location's type, provide a \
                fitting name and atmospheric description.

                Output ONLY a valid JSON object with two fields:
                {"name": "...", "description": "..."}

                The name should be evocative and fit the story's genre/setting (2-4 words).
                The description should be 1-2 atmospheric sentences that set the scene.
                Do not include any text outside the JSON object.""";

            String userPrompt = String.format("""
                Story: "%s"
                Story description: %s

                Location type: %s
                Current template name: %s
                Current template description: %s

                Provide a better name and description that fits this story's world.""",
                storyTitle,
                storyDescription != null && !storyDescription.isEmpty() ? storyDescription : "(none)",
                locType != null ? locType : "settlement",
                locName != null ? locName : "Unknown",
                locDescription != null ? locDescription : "(none)");

            JsonArray messages = new JsonArray()
                .add(new JsonObject().put("role", "system").put("content", systemPrompt))
                .add(new JsonObject().put("role", "user").put("content", userPrompt));

            JsonObject params = new JsonObject()
                .put("messages", messages)
                .put("temperature", 0.8)
                .put("max_tokens", 300)
                .put("stream", false);

            io.vertx.core.eventbus.DeliveryOptions opts =
                new io.vertx.core.eventbus.DeliveryOptions().setSendTimeout(60000);

            return vertx.eventBus().<JsonObject>request("llm.agent.completion", params, opts)
                .chain(reply -> {
                    JsonObject body = reply.body();
                    String content = extractLlmContent(body);
                    if (content == null || content.isBlank()) {
                        log.warn("LLM returned empty content for starting location customization");
                        return Uni.createFrom().voidItem();
                    }

                    // Parse the JSON response
                    try {
                        String cleaned = content.strip();
                        // Try to extract JSON from markdown fences if present
                        if (cleaned.contains("```")) {
                            java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("```(?:json)?\\s*\\n?(\\{.*?\\})\\s*```", java.util.regex.Pattern.DOTALL)
                                .matcher(cleaned);
                            if (m.find()) {
                                cleaned = m.group(1).strip();
                            }
                        }
                        // Fallback: extract first JSON object from the response
                        int first = cleaned.indexOf('{');
                        int last = cleaned.lastIndexOf('}');
                        if (first >= 0 && last > first) {
                            cleaned = cleaned.substring(first, last + 1);
                        }
                        JsonObject llmResult = new JsonObject(cleaned);
                        String newName = llmResult.getString("name");
                        String newDescription = llmResult.getString("description");

                        if (newName == null || newName.isBlank()) {
                            log.warn("LLM did not provide a name for starting location");
                            return Uni.createFrom().voidItem();
                        }

                        log.info("LLM customized starting location: '{}' -> '{}'", locName, newName);

                        // Update the location and world_state.currentLocation in parallel
                        Uni<Void> updateLoc = mapService.updateLocationNameAndDescription(
                            mapId, startingLocationId, newName, newDescription);
                        Uni<Void> updateWs = worldStateService.updateWorldState(conversationId,
                                new JsonObject()
                                        .put("currentLocation", newName)
                                        .put("currentLocationId", startingLocationId))
                                .replaceWithVoid();

                        return Uni.combine().all().unis(updateLoc, updateWs).discardItems();
                    } catch (Exception e) {
                        log.warn("Failed to parse LLM customization response: {}", content, e);
                        return Uni.createFrom().voidItem();
                    }
                });
        });
    }

    /** Extract content string from an OpenAI-compatible chat completion response. */
    private static String extractLlmContent(JsonObject response) {
        JsonArray choices = response.getJsonArray("choices");
        if (choices == null || choices.isEmpty()) return null;
        JsonObject message = choices.getJsonObject(0).getJsonObject("message");
        return message != null ? message.getString("content") : null;
    }

    // ==================== Travel ====================

    @Query("travelPreview")
    public Uni<TravelResult> travelPreview(String mapId, String fromLocationId, String toLocationId) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyMapAccess(mapId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return travelService.calculateTravel(mapId, fromLocationId, toLocationId);
        });
    }

    @SuppressWarnings("unchecked")
    @Mutation("travelToLocation")
    public Uni<TravelResult> travelToLocation(String mapId, String toLocationId, List<String> characterIds) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyMapAccess(mapId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            String userId = callerId;

            // Get conversationId from the map
            return mysqlPool.preparedQuery("SELECT conversationId FROM world_map WHERE mapId = ?")
                .execute(Tuple.of(mapId))
                .chain(rows -> {
                    if (rows.size() == 0) {
                        return Uni.createFrom().failure(new RuntimeException("Map not found: " + mapId));
                    }
                    String conversationId = rows.iterator().next().getString("conversationId");

                    // If no characterIds provided, get all active characters in conversation
                    Uni<List<String>> charIdsUni;
                    if (characterIds != null && !characterIds.isEmpty()) {
                        charIdsUni = mysqlPool.preparedQuery(
                            "SELECT characterId FROM conversation_character WHERE conversationId = ? AND isActive = TRUE")
                        .execute(Tuple.of(conversationId))
                        .map(charRows -> {
                            Set<String> activeIds = new java.util.HashSet<>();
                            charRows.forEach(row -> activeIds.add(row.getString("characterId")));
                            if (!activeIds.containsAll(characterIds)) {
                                throw new IllegalArgumentException("Travel characters must be active in this conversation");
                            }
                            return characterIds.stream().distinct().toList();
                        });
                    } else {
                        charIdsUni = mysqlPool.preparedQuery(
                                "SELECT characterId FROM conversation_character WHERE conversationId = ? AND isActive = TRUE"
                                        + " AND (isIntroduced = TRUE OR isIntroduced IS NULL)")
                            .execute(Tuple.of(conversationId))
                            .map(charRows -> {
                                List<String> ids = new ArrayList<>();
                                for (var row : charRows) {
                                    ids.add(row.getString("characterId"));
                                }
                                return ids;
                            });
                    }

                    return charIdsUni.chain(resolvedCharIds ->
                        battleService.canManage(conversationId, userId).invoke(manager -> {
                            if (!manager) throw new SecurityException("Only the host can skip narrated travel; begin a journey instead");
                        }).chain(() -> battleService.requirePeace(conversationId)).chain(() -> journeyService.getActiveJourney(conversationId)).chain(journey -> {
                            if (journey != null) return Uni.createFrom().failure(new IllegalStateException("Finish or cancel the current journey first"));
                            return travelService.executeTravelAndAdvanceTime(conversationId, mapId, toLocationId, userId, resolvedCharIds);
                        })
                    );
                });
        });
    }

    @SuppressWarnings("unchecked")
    @Mutation("beginJourney")
    public Uni<JourneyState> beginJourney(String mapId, String toLocationId, List<String> characterIds) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyMapAccess(mapId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            String userId = callerId;

            return mysqlPool.preparedQuery("SELECT conversationId FROM world_map WHERE mapId = ?")
                .execute(Tuple.of(mapId))
                .chain(rows -> {
                    if (rows.size() == 0) {
                        return Uni.createFrom().failure(new RuntimeException("Map not found: " + mapId));
                    }
                    String conversationId = rows.iterator().next().getString("conversationId");

                    // If no characterIds provided, get all active characters
                    Uni<List<String>> charIdsUni;
                    if (characterIds != null && !characterIds.isEmpty()) {
                        charIdsUni = mysqlPool.preparedQuery(
                            "SELECT characterId FROM conversation_character WHERE conversationId = ? AND isActive = TRUE")
                        .execute(Tuple.of(conversationId))
                        .map(charRows -> {
                            Set<String> activeIds = new java.util.HashSet<>();
                            charRows.forEach(row -> activeIds.add(row.getString("characterId")));
                            if (!activeIds.containsAll(characterIds)) {
                                throw new IllegalArgumentException("Travel characters must be active in this conversation");
                            }
                            return characterIds.stream().distinct().toList();
                        });
                    } else {
                        charIdsUni = mysqlPool.preparedQuery(
                                "SELECT characterId FROM conversation_character WHERE conversationId = ? AND isActive = TRUE"
                                        + " AND (isIntroduced = TRUE OR isIntroduced IS NULL)")
                            .execute(Tuple.of(conversationId))
                            .map(charRows -> {
                                List<String> ids = new ArrayList<>();
                                for (var row : charRows) ids.add(row.getString("characterId"));
                                return ids;
                            });
                    }

                    return charIdsUni.chain(resolvedCharIds ->
                        journeyService.startJourney(conversationId, mapId, userId, toLocationId, resolvedCharIds)
                    );
                });
        });
    }

    @Mutation("resumeJourney")
    public Uni<JourneyState> resumeJourney(String conversationId) {
        String caller = requireAuth().getUserId().toString();
        return serviceHelper.verifyConversationAccess(conversationId, caller).chain(allowed -> allowed
            ? journeyService.resumeJourney(conversationId, caller)
            : Uni.createFrom().failure(new SecurityException("Access denied")));
    }

    @Mutation("cancelJourney")
    public Uni<Boolean> cancelJourney(String conversationId) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyConversationAccess(conversationId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return journeyService.cancelJourney(conversationId, callerId);
        });
    }

    @Query("activeJourney")
    public Uni<JourneyState> activeJourney(String conversationId) {
        String callerId = requireAuth().getUserId().toString();
        return serviceHelper.verifyConversationAccess(conversationId, callerId).chain(allowed -> {
            if (!allowed) return Uni.createFrom().failure(new SecurityException("Map not found or access denied"));
            return journeyService.getActiveJourney(conversationId);
        });
    }

    // ==================== Helper Methods ====================

    private MapLocation mapLocationRow(Row row) {
        return MapLocation.builder()
            .locationId(row.getString("locationId") != null ? UUID.fromString(row.getString("locationId")) : null)
            .mapId(row.getString("mapId") != null ? UUID.fromString(row.getString("mapId")) : null)
            .name(row.getString("name"))
            .description(row.getString("description"))
            .x(row.getFloat("x"))
            .y(row.getFloat("y"))
            .z(row.getFloat("z"))
            .icon(row.getString("icon"))
            .locationType(row.getString("locationType"))
            .linkedSceneId(row.getString("linkedSceneId") != null ? UUID.fromString(row.getString("linkedSceneId")) : null)
            .isDiscovered(row.getBoolean("isDiscovered"))
            .movementCost(row.getFloat("movementCost"))
            .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
            .build();
    }

    private WorldMap mapWorldMapRow(Row row) {
        return WorldMap.builder()
            .mapId(row.getString("mapId") != null ? UUID.fromString(row.getString("mapId")) : null)
            .conversationId(row.getString("conversationId") != null ? UUID.fromString(row.getString("conversationId")) : null)
            .name(row.getString("name"))
            .mapType(row.getString("mapType"))
            .mapScale(row.getString("mapScale"))
            .imageId(row.getString("imageId") != null ? UUID.fromString(row.getString("imageId")) : null)
            .parentMapId(row.getString("parentMapId") != null ? UUID.fromString(row.getString("parentMapId")) : null)
            .gridWidth(row.getInteger("gridWidth"))
            .gridHeight(row.getInteger("gridHeight"))
            .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
            .build();
    }

    private MapCharacterPosition mapPositionRow(Row row) {
        return MapCharacterPosition.builder()
            .id(row.getString("id") != null ? UUID.fromString(row.getString("id")) : null)
            .mapId(row.getString("mapId") != null ? UUID.fromString(row.getString("mapId")) : null)
            .characterId(row.getString("characterId") != null ? UUID.fromString(row.getString("characterId")) : null)
            .userId(row.getString("userId") != null ? UUID.fromString(row.getString("userId")) : null)
            .x(row.getFloat("x"))
            .y(row.getFloat("y"))
            .z(row.getFloat("z"))
            .locationId(row.getString("locationId") != null ? UUID.fromString(row.getString("locationId")) : null)
            .build();
    }

    private JsonObject locationToJson(MapLocation loc) {
        JsonObject json = new JsonObject()
            .put("locationId", loc.getLocationId() != null ? loc.getLocationId().toString() : null)
            .put("mapId", loc.getMapId() != null ? loc.getMapId().toString() : null)
            .put("name", loc.getName())
            .put("description", loc.getDescription())
            .put("x", loc.getX())
            .put("y", loc.getY())
            .put("z", loc.getZ())
            .put("icon", loc.getIcon())
            .put("locationType", loc.getLocationType())
            .put("isDiscovered", loc.getIsDiscovered());
        if (loc.getLinkedSceneId() != null) json.put("linkedSceneId", loc.getLinkedSceneId().toString());
        return json;
    }

    private JsonObject positionToJson(MapCharacterPosition pos) {
        JsonObject json = new JsonObject()
            .put("id", pos.getId() != null ? pos.getId().toString() : null)
            .put("mapId", pos.getMapId() != null ? pos.getMapId().toString() : null)
            .put("x", pos.getX())
            .put("y", pos.getY())
            .put("z", pos.getZ());
        if (pos.getCharacterId() != null) json.put("characterId", pos.getCharacterId().toString());
        if (pos.getUserId() != null) json.put("userId", pos.getUserId().toString());
        if (pos.getLocationId() != null) json.put("locationId", pos.getLocationId().toString());
        return json;
    }

    private String encodeFloatArray(float[] arr) {
        return MapScaleUtils.encodeFloatArray(arr);
    }

    private String encodeIntArray(int[] arr) {
        return MapScaleUtils.encodeIntArray(arr);
    }
}
