package com.eonmux.chatllm.map;

import com.eonmux.chatllm.models.LocalArea;
import com.eonmux.chatllm.models.LocalAreaConnection;
import com.eonmux.chatllm.models.LocalMap;
import com.eonmux.chatllm.models.MapLocation;
import com.eonmux.chatllm.models.WorldMap;
import com.eonmux.chatllm.models.WorldState;
import com.eonmux.chatllm.services.WorldStateService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The single authority for where the scene is.
 *
 * <p>World-map nodes are geographic points. Local areas are the rooms or
 * streets inside a node. This service resolves the party to one node and one
 * area, records changes, and refuses moves that skip the map's connections.</p>
 */
@ApplicationScoped
@Slf4j
public class StageService {

    @Inject
    Pool pool;

    @Inject
    MapService mapService;

    @Inject
    LocalMapService localMapService;

    @Inject
    WorldStateService worldStateService;

    // ==================== Reading the stage ====================

    /** Resolves and, when needed, initializes the current stage. */
    public Uni<StageContext> getStageContext(String conversationId) {
        return pool.preparedQuery("SELECT journeyId FROM journey_state WHERE conversationId = ? AND status IN ('TRAVELING','PAUSED') LIMIT 1")
            .execute(Tuple.of(conversationId)).chain(routes -> routes.size() > 0
                ? Uni.createFrom().item(StageContext.empty()) : resolveStageContext(conversationId));
    }

    private Uni<StageContext> resolveStageContext(String conversationId) {
        return Uni.combine().all()
                .unis(worldStateService.getOrCreateWorldState(conversationId),
                        mapService.getMaps(conversationId))
                .asTuple()
                .chain(tuple -> {
                    WorldState state = tuple.getItem1();
                    if (state.getCurrentLocationId() == null && ("On the road".equals(state.getCurrentArea()) || "Route encounter".equals(state.getCurrentArea())))
                        return Uni.createFrom().item(StageContext.empty());
                    List<WorldMap> maps = tuple.getItem2();
                    if (maps == null || maps.isEmpty()) return Uni.createFrom().item(StageContext.empty());
                    WorldMap map = maps.get(0);
                    return resolveNode(map, state).chain(node -> {
                        if (node == null) return Uni.createFrom().item(StageContext.empty());
                        return localMapService.ensureLayout(node.getLocationId().toString())
                                .chain(local -> {
                                    if (local == null || local.getAreas().isEmpty()) {
                                        return Uni.createFrom().item(StageContext.empty());
                                    }
                                    LocalArea area = resolveArea(state, local.getAreas());
                                    if (area == null) return Uni.createFrom().item(StageContext.empty());
                                    return syncWorldState(conversationId, state, node, area)
                                            // The area the scene stands in is, by definition,
                                            // seen. This is what lifts the fog of war: the
                                            // first round marks the entry area, and every
                                            // later move marks its target in the move path.
                                            .chain(v -> localMapService.markAreaVisited(
                                                    conversationId,
                                                    node.getLocationId().toString(),
                                                    area.getLocalAreaId().toString()))
                                            .chain(v -> localMapService.loadVisitedAreaIds(
                                                    conversationId, node.getLocationId().toString()))
                                            .chain(visited -> buildStage(map, node, area, local, visited));
                                });
                    });
                })
                .onFailure().recoverWithItem(error -> {
                    log.warn("[stage] Unable to resolve stage for {}: {}", conversationId, error.getMessage());
                    return StageContext.empty();
                });
    }

    /** The local map for the current stage, for the frontend. */
    public Uni<LocalMap> getLocalMap(String conversationId) {
        return getStageContext(conversationId).chain(stage -> {
            if (!stage.isPresent()) return Uni.createFrom().nullItem();
            String locationId = stage.getNode().getLocationId().toString();
            return localMapService.getLayout(locationId).map(local -> {
                if (local == null) return null;
                local.setCurrentAreaId(stage.getArea().getLocalAreaId().toString());
                local.setCurrentAreaName(stage.getArea().getName());
                local.setVisitedAreaIds(stage.getVisitedAreaIds());
                return local;
            });
        });
    }

    /** Initializes the stage without moving anyone. */
    public Uni<Void> ensureStage(String conversationId) {
        return getStageContext(conversationId).replaceWithVoid();
    }

    private Uni<MapLocation> resolveNode(WorldMap map, WorldState state) {
        String mapId = map.getMapId().toString();
        String name = state.getCurrentLocation();
        if (state.getCurrentLocationId() != null) {
            return mapService.getLocationById(state.getCurrentLocationId().toString())
                    .chain(byId -> {
                        if (byId == null || byId.getMapId() == null
                                || !byId.getMapId().toString().equals(mapId)) {
                            return resolveByName(map, name);
                        }
                        if (name == null || name.isBlank() || name.equalsIgnoreCase(byId.getName())) {
                            return Uni.createFrom().item(byId);
                        }
                        // The name may be a room in this node (a scene change
                        // wrote a room here) or a different world node.
                        return localMapService.ensureLayout(byId.getLocationId().toString())
                                .chain(local -> {
                                    if (local != null
                                            && LocalMapService.findArea(local.getAreas(), name) != null) {
                                        return Uni.createFrom().item(byId);
                                    }
                                    return mapService.findLocationByName(mapId, name)
                                            .map(byName -> byName != null ? byName : byId);
                                });
                    });
        }
        return resolveByName(map, name);
    }

    private Uni<MapLocation> resolveByName(WorldMap map, String name) {
        if (name != null && !name.isBlank()) {
            return mapService.findLocationByName(map.getMapId().toString(), name)
                    .chain(node -> node != null ? Uni.createFrom().item(node) : pickStartingNode(map));
        }
        return pickStartingNode(map);
    }

    /**
     * Picks the node where the session really starts.
     *
     * <p>The generated map records the node it positioned the party on. A
     * missing world-state row used to skip that fact and fall back to the first
     * discovered settlement, so a dungeon party opened in a town market. The
     * map's own starting node wins, then the node holding the party's position
     * rows, then a discovered settlement.</p>
     */
    private Uni<MapLocation> pickStartingNode(WorldMap map) {
        String mapId = map.getMapId().toString();
        String preferredId = map.getMetadata() != null
                ? map.getMetadata().getString("startingLocationId") : null;
        return Uni.combine().all()
                .unis(mapService.getLocations(mapId), entityAnchor(mapId))
                .asTuple()
                .map(tuple -> chooseStartingLocation(tuple.getItem1(), preferredId, tuple.getItem2()));
    }

    /**
     * The starting node rules, kept pure so they can be checked without a
     * database. Returns null only when the map has no locations.
     */
    static MapLocation chooseStartingLocation(List<MapLocation> locations,
                                              String preferredId, String anchoredId) {
        if (locations == null || locations.isEmpty()) return null;
        MapLocation byPreference = findLocation(locations, preferredId);
        if (byPreference != null) return byPreference;
        MapLocation byAnchor = findLocation(locations, anchoredId);
        if (byAnchor != null) return byAnchor;
        return locations.stream()
                .filter(location -> Boolean.TRUE.equals(location.getIsDiscovered()))
                .filter(location -> isSettlement(location.getLocationType()))
                .findFirst()
                .orElseGet(() -> locations.stream()
                        .filter(location -> Boolean.TRUE.equals(location.getIsDiscovered()))
                        .findFirst()
                        .orElse(locations.get(0)));
    }

    private static MapLocation findLocation(List<MapLocation> locations, String locationId) {
        if (locationId == null || locationId.isBlank()) return null;
        for (MapLocation location : locations) {
            if (location.getLocationId() != null
                    && locationId.equals(location.getLocationId().toString())) {
                return location;
            }
        }
        return null;
    }

    /** The node with the most entity positions, used when the map has no start id. */
    private Uni<String> entityAnchor(String mapId) {
        return pool.preparedQuery("""
                        SELECT locationId FROM map_character_position
                        WHERE mapId = ? AND locationId IS NOT NULL
                        GROUP BY locationId
                        ORDER BY COUNT(*) DESC, MIN(id)
                        LIMIT 1
                        """)
                .execute(Tuple.of(mapId))
                .map(rows -> rows.size() == 0 ? null : rows.iterator().next().getString("locationId"))
                .onFailure().recoverWithItem((String) null);
    }

    private static boolean isSettlement(String type) {
        if (type == null) return false;
        return switch (type) {
            case "town", "village", "city", "camp", "port" -> true;
            default -> false;
        };
    }

    private LocalArea resolveArea(WorldState state, List<LocalArea> areas) {
        if (state.getCurrentAreaId() != null) {
            for (LocalArea area : areas) {
                if (area.getLocalAreaId().toString().equals(state.getCurrentAreaId().toString())) {
                    return area;
                }
            }
        }
        if (state.getCurrentArea() != null && !state.getCurrentArea().isBlank()) {
            LocalArea match = LocalMapService.findArea(areas, state.getCurrentArea());
            if (match != null) return match;
        }
        // A scene change may have written the room to currentLocation instead
        // of currentArea. Recover it before falling back to the entry area.
        if (state.getCurrentLocation() != null && !state.getCurrentLocation().isBlank()) {
            LocalArea match = LocalMapService.findArea(areas, state.getCurrentLocation());
            if (match != null) return match;
        }
        return LocalMapService.entryArea(areas);
    }

    private Uni<Void> syncWorldState(String conversationId, WorldState state,
                                     MapLocation node, LocalArea area) {
        JsonObject updates = new JsonObject();
        if (!node.getName().equals(state.getCurrentLocation())) {
            updates.put("currentLocation", node.getName());
        }
        if (state.getCurrentLocationId() == null
                || !node.getLocationId().toString().equals(state.getCurrentLocationId().toString())) {
            updates.put("currentLocationId", node.getLocationId().toString());
        }
        if (!area.getName().equals(state.getCurrentArea())) {
            updates.put("currentArea", area.getName());
        }
        if (state.getCurrentAreaId() == null
                || !area.getLocalAreaId().toString().equals(state.getCurrentAreaId().toString())) {
            updates.put("currentAreaId", area.getLocalAreaId().toString());
        }
        if (updates.isEmpty()) return Uni.createFrom().voidItem();
        return worldStateService.updateWorldState(conversationId, updates).replaceWithVoid();
    }

    private Uni<StageContext> buildStage(WorldMap map, MapLocation node, LocalArea area, LocalMap local,
                                         Set<String> visitedAreaIds) {
        List<StageExit> exits = exits(area, local);
        List<String> route = routeToGateway(area, local);
        Uni<List<StageDestination>> destinations = Boolean.TRUE.equals(area.getIsGateway())
                ? destinations(map, node)
                : Uni.createFrom().item(List.of());
        return destinations.map(found -> StageContext.builder()
                .mapId(map.getMapId().toString())
                .mapName(map.getName())
                .mapScale(map.getMapScale())
                .node(node)
                .area(area)
                .areas(local.getAreas())
                .connections(local.getConnections())
                .exits(exits)
                .destinations(found)
                .routeToGateway(route)
                .atGateway(Boolean.TRUE.equals(area.getIsGateway()))
                .visitedAreaIds(visitedAreaIds == null ? List.of() : List.copyOf(visitedAreaIds))
                .build());
    }

    private List<StageExit> exits(LocalArea area, LocalMap local) {
        List<StageExit> exits = new ArrayList<>();
        if (area == null || local.getConnections() == null) return exits;
        UUID current = area.getLocalAreaId();
        for (LocalAreaConnection connection : local.getConnections()) {
            UUID otherId = null;
            if (current.equals(connection.getFromAreaId())) otherId = connection.getToAreaId();
            else if (current.equals(connection.getToAreaId())) otherId = connection.getFromAreaId();
            if (otherId == null) continue;
            LocalArea other = findById(local.getAreas(), otherId);
            if (other == null) continue;
            exits.add(new StageExit(other.getLocalAreaId().toString(), other.getName(),
                    other.getAreaType(), other.getDescription(), connection.getLabel(),
                    compassDirection(area.getX(), area.getY(), other.getX(), other.getY())));
        }
        exits.sort(Comparator.comparing(StageExit::name, String.CASE_INSENSITIVE_ORDER));
        return exits;
    }

    private List<String> routeToGateway(LocalArea area, LocalMap local) {
        if (area == null || Boolean.TRUE.equals(area.getIsGateway())) return List.of();
        List<LocalArea> gateways = local.getAreas().stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getIsGateway()))
                .toList();
        List<UUID> best = List.of();
        for (LocalArea gateway : gateways) {
            List<UUID> route = LocalMapService.route(area.getLocalAreaId(), gateway.getLocalAreaId(),
                    local.getConnections());
            if (!route.isEmpty() && (best.isEmpty() || route.size() < best.size())) {
                best = route;
            }
        }
        List<String> names = new ArrayList<>();
        for (UUID id : best) {
            LocalArea step = findById(local.getAreas(), id);
            if (step != null) names.add(step.getName());
        }
        return names;
    }

    // ==================== Gateway destinations ====================

    private Uni<List<StageDestination>> destinations(WorldMap map, MapLocation node) {
        String mapId = map.getMapId().toString();
        Uni<List<com.eonmux.chatllm.map.models.RoadSegment>> roads = mapService.getRoads(mapId)
                .onFailure().recoverWithItem(List.of());
        return Uni.combine().all().unis(roads, mapService.getLocations(mapId)).asTuple()
                .map(tuple -> {
                    Set<String> roadConnected = new HashSet<>();
                    for (var road : tuple.getItem1()) {
                        if (road.getFromLocationId() == null || road.getToLocationId() == null) continue;
                        if (node.getLocationId().toString().equals(road.getFromLocationId())) {
                            roadConnected.add(road.getToLocationId());
                        }
                        if (node.getLocationId().toString().equals(road.getToLocationId())) {
                            roadConnected.add(road.getFromLocationId());
                        }
                    }
                    float radius = gatewayRadius(map.getMapScale());
                    List<StageDestination> found = new ArrayList<>();
                    for (MapLocation candidate : tuple.getItem2()) {
                        if (candidate.getLocationId().equals(node.getLocationId())) continue;
                        if (!Boolean.TRUE.equals(candidate.getIsDiscovered())) continue;
                        double distance = MapService.calculateDistance(
                                node.getX(), node.getY(), node.getZ(),
                                candidate.getX(), candidate.getY(), candidate.getZ());
                        boolean byRoad = roadConnected.contains(candidate.getLocationId().toString());
                        if (!byRoad && distance > radius) continue;
                        found.add(new StageDestination(
                                candidate.getLocationId().toString(),
                                candidate.getName(),
                                candidate.getLocationType(),
                                distanceLabel(distance),
                                byRoad));
                    }
                    found.sort(Comparator.comparing(StageDestination::viaRoad).reversed()
                            .thenComparing(destination -> !"a short walk".equals(destination.distanceLabel()))
                            .thenComparing(StageDestination::name, String.CASE_INSENSITIVE_ORDER));
                    return found.size() > 6 ? List.copyOf(found.subList(0, 6)) : List.copyOf(found);
                });
    }

    private static float gatewayRadius(String mapScale) {
        return Math.max(15f, MapScaleUtils.worldSizeForScale(mapScale) * 0.15f);
    }

    private static String distanceLabel(double distance) {
        if (distance < 10) return "a few steps away";
        if (distance < 25) return "a short walk";
        if (distance < 60) return "a short ride";
        if (distance < 120) return "a day's travel";
        return "a long journey";
    }

    private static LocalArea findById(List<LocalArea> areas, UUID id) {
        if (areas == null || id == null) return null;
        for (LocalArea area : areas) {
            if (id.equals(area.getLocalAreaId())) return area;
        }
        return null;
    }

    // ==================== Moving ====================

    /**
     * Applies a requested location or area change. The model supplies names;
     * this method decides whether the move is real.
     */
    public Uni<StageMovementResult> move(String conversationId, String requestedLocation,
                                         String requestedArea) {
        return getStageContext(conversationId).chain(stage -> {
            return pool.preparedQuery("SELECT battleId FROM battle WHERE conversationId = ? AND status = 'ACTIVE' LIMIT 1")
                .execute(Tuple.of(conversationId)).chain(fights -> {
            if (fights.size() > 0) return Uni.createFrom().item(rejected(stage, "The encounter must be resolved before leaving. Use a battle action to retreat."));
            if (!stage.isPresent()) {
                return Uni.createFrom().item(StageMovementResult.builder()
                        .kind(StageMovementPolicy.Kind.REJECTED)
                        .reason("There is no map or stage for this conversation.")
                        .stage(stage)
                        .build());
            }
            String areaRequest = clean(requestedArea);
            String placeRequest = clean(requestedLocation);

            boolean placeIsCurrent = placeRequest == null
                    || normalizedName(placeRequest).equals(normalizedName(stage.getNode().getName()))
                    || findLocalDestination(stage, placeRequest) != null;

            LocalArea localTarget = null;
            if (placeIsCurrent) {
                localTarget = areaRequest == null ? null
                        : findLocalDestination(stage, areaRequest);
                if (localTarget == null && placeRequest != null) {
                    localTarget = findLocalDestination(stage, placeRequest);
                }
            }
            if (localTarget != null) {
                StageMovementPolicy.Decision decision = StageMovementPolicy.forArea(
                        stage.getArea(), localTarget, stage.getConnections());
                return switch (decision.kind()) {
                    case NONE -> Uni.createFrom().item(none(stage, decision.reason()));
                    case REJECTED -> Uni.createFrom().item(rejected(stage, decision.reason()));
                    case AREA -> applyAreaMove(conversationId, stage, localTarget);
                    default -> Uni.createFrom().item(rejected(stage, "Invalid move."));
                };
            }

            if (placeRequest == null) {
                return Uni.createFrom().item(areaRequest == null
                        ? none(stage, "No move requested.")
                        : rejected(stage, "That area does not exist in this place."));
            }
            if (normalizedName(placeRequest).equals(normalizedName(stage.getNode().getName()))) {
                return Uni.createFrom().item(none(stage, "Already in that place."));
            }

            return mapService.findLocationByName(stage.getMapId(), placeRequest).chain(target -> {
                if (target == null) {
                    return Uni.createFrom().item(rejected(stage, "That place is not on the map."));
                }
                return isReachableDestination(stage, target).map(reachable -> {
                    boolean canReachGateway = stage.isAtGateway()
                            || (stage.getRouteToGateway() != null
                                && !stage.getRouteToGateway().isEmpty());
                    StageMovementPolicy.Decision decision = StageMovementPolicy.forNode(
                            canReachGateway, target, reachable);
                    if (decision.kind() == StageMovementPolicy.Kind.REJECTED) {
                        return StageMovementPolicy.Decision.rejected(decision.reason());
                    }
                    return StageMovementPolicy.Decision.node(target, null);
                }).chain(decision -> {
                    if (decision.kind() == StageMovementPolicy.Kind.REJECTED) {
                        return Uni.createFrom().item(rejected(stage, decision.reason()));
                    }
                    if (target.getLocationId().equals(stage.getNode().getLocationId())) {
                        return Uni.createFrom().item(rejected(stage,
                                "That area does not exist in this place."));
                    }
                    return applyNodeMove(conversationId, stage, decision.node(), areaRequest);
                });
            });
            });
        });
    }

    /** An exact connection label names its other endpoint, never an invented room. */
    static LocalArea findLocalDestination(StageContext stage, String requested) {
        if (requested == null || requested.isBlank()) return null;
        for (LocalArea area : stage.getAreas()) {
            if (area.getLocalAreaId().toString().equalsIgnoreCase(requested.strip())
                    || LocalMapService.normalize(area.getName()).equals(LocalMapService.normalize(requested))) return area;
        }
        Set<UUID> destinations = new HashSet<>();
        for (var connection : stage.getConnections()) {
            if (!LocalMapService.normalize(requested).equals(LocalMapService.normalize(connection.getLabel()))) continue;
            UUID current = stage.getArea().getLocalAreaId();
            if (current.equals(connection.getFromAreaId())) destinations.add(connection.getToAreaId());
            else if (current.equals(connection.getToAreaId())) destinations.add(connection.getFromAreaId());
        }
        if (destinations.size() > 1) return null;
        if (destinations.size() == 1) return stage.getAreas().stream()
                .filter(area -> destinations.contains(area.getLocalAreaId())).findFirst().orElse(null);
        return LocalMapService.findArea(stage.getAreas(), requested);
    }

    private Uni<StageMovementResult> applyAreaMove(String conversationId, StageContext stage,
                                                   LocalArea target) {
        WorldMap map = mapOf(stage);
        LocalMap local = localOf(stage);
        JsonObject updates = new JsonObject()
                .put("currentArea", target.getName())
                .put("currentAreaId", target.getLocalAreaId().toString())
                .put("currentLocation", stage.getNode().getName())
                .put("currentLocationId", stage.getNode().getLocationId().toString());
        return worldStateService.updateWorldState(conversationId, updates)
                .chain(state -> {
                    Uni<Void> visits = Uni.createFrom().voidItem();
                    for (UUID id : LocalMapService.route(stage.getArea().getLocalAreaId(), target.getLocalAreaId(), stage.getConnections())) {
                        visits = visits.chain(() -> localMapService.markAreaVisited(
                                conversationId, stage.getNode().getLocationId().toString(), id.toString()));
                    }
                    return visits;
                })
                .chain(v -> localMapService.loadVisitedAreaIds(
                        conversationId, stage.getNode().getLocationId().toString()))
                .chain(visited -> buildStage(map, stage.getNode(), target, local, visited))
                .map(next -> StageMovementResult.builder()
                        .kind(StageMovementPolicy.Kind.AREA)
                        .stage(next)
                        .reason("Moved within " + stage.getNode().getName() + ".")
                        .fromNode(stage.getNode())
                        .toNode(stage.getNode())
                        .build());
    }

    private Uni<StageMovementResult> applyNodeMove(String conversationId, StageContext stage,
                                                   MapLocation target, String requestedArea) {
        return localMapService.ensureLayout(target.getLocationId().toString()).chain(local -> {
            if (local == null || local.getAreas().isEmpty()) {
                return Uni.createFrom().item(rejected(stage, "That place has no interior layout."));
            }
            LocalArea targetArea = requestedArea == null ? null
                    : LocalMapService.findArea(local.getAreas(), requestedArea);
            if (targetArea == null) targetArea = LocalMapService.entryArea(local.getAreas());
            final LocalArea destinationArea = targetArea;
            JsonObject updates = new JsonObject()
                    .put("currentLocation", target.getName())
                    .put("currentLocationId", target.getLocationId().toString())
                    .put("currentArea", destinationArea.getName())
                    .put("currentAreaId", destinationArea.getLocalAreaId().toString());
            return worldStateService.updateWorldState(conversationId, updates)
                    .chain(state -> moveParty(conversationId, target))
                    .chain(v -> mapService.discoverNearbyLocations(stage.getMapId(),
                            target.getX(), target.getY(), target.getZ(), 15f))
                    .chain(discovered -> localMapService.markAreaVisited(
                                    conversationId, target.getLocationId().toString(),
                                    destinationArea.getLocalAreaId().toString())
                            .chain(v -> localMapService.loadVisitedAreaIds(
                                    conversationId, target.getLocationId().toString()))
                            .chain(visited -> buildStage(mapOf(stage), target, destinationArea, local, visited)
                                    .map(next -> StageMovementResult.builder()
                                            .kind(StageMovementPolicy.Kind.NODE)
                                            .stage(next)
                                            .reason("Traveled to " + target.getName() + ".")
                                            .fromNode(stage.getNode())
                                            .toNode(target)
                                            .discovered(discovered)
                                            .build())));
        });
    }

    /** Moves the onstage cast and the player to a node's map position. */
    private Uni<Void> moveParty(String conversationId, MapLocation node) {
        String mapId = node.getMapId().toString();
        String locationId = node.getLocationId().toString();
        float x = node.getX();
        float y = node.getY();
        float z = node.getZ();

        Uni<Row> ownerUni = pool.preparedQuery(
                        "SELECT ownerId FROM conversation WHERE conversationGUID = ?")
                .execute(Tuple.of(conversationId))
                .map(rows -> rows.size() == 0 ? null : rows.iterator().next());

        Uni<List<String>> castUni = pool.preparedQuery("""
                        SELECT characterId FROM conversation_character
                        WHERE conversationId = ? AND isActive = TRUE
                          AND (isIntroduced = TRUE OR isIntroduced IS NULL)
                          AND NOT EXISTS (SELECT 1 FROM `character` resident WHERE resident.characterUUID=conversation_character.characterId AND JSON_EXTRACT(resident.metadata,'$.residentOfAreaId') IS NOT NULL)
                        """)
                .execute(Tuple.of(conversationId))
                .map(rows -> {
                    List<String> ids = new ArrayList<>();
                    for (Row row : rows) ids.add(row.getString("characterId"));
                    return ids;
                });

        return Uni.combine().all().unis(ownerUni, castUni).asTuple().chain(tuple -> {
            List<Uni<Void>> moves = new ArrayList<>();
            Row owner = tuple.getItem1();
            if (owner != null) {
                String ownerId = owner.getString("ownerId");
                if (ownerId != null) {
                    moves.add(mapService.updateUserPosition(mapId, ownerId, locationId, x, y, z));
                }
            }
            for (String characterId : tuple.getItem2()) {
                moves.add(mapService.updateCharacterPosition(mapId, characterId, locationId, x, y, z));
            }
            if (moves.isEmpty()) return Uni.createFrom().voidItem();
            return Uni.combine().all().unis(moves).discardItems().replaceWithVoid();
        });
    }

    private Uni<Boolean> isReachableDestination(StageContext stage, MapLocation target) {
        return mapService.getRoads(stage.getMapId())
                .onFailure().recoverWithItem(List.of())
                .map(roads -> {
                    for (var road : roads) {
                        if (road.getFromLocationId() == null || road.getToLocationId() == null) continue;
                        boolean from = stage.getNode().getLocationId().toString().equals(road.getFromLocationId())
                                && target.getLocationId().toString().equals(road.getToLocationId());
                        boolean to = stage.getNode().getLocationId().toString().equals(road.getToLocationId())
                                && target.getLocationId().toString().equals(road.getFromLocationId());
                        if (from || to) return true;
                    }
                    double distance = MapService.calculateDistance(
                            stage.getNode().getX(), stage.getNode().getY(), stage.getNode().getZ(),
                            target.getX(), target.getY(), target.getZ());
                    return distance <= gatewayRadius(stage.getMapScale());
                });
    }

    /**
     * Records a place reached by an explicit travel action. The caller already
     * moved entity positions; this sets the local entry area.
     */
    public Uni<StageContext> placeAtNode(String conversationId, String locationId) {
        if (locationId == null) return getStageContext(conversationId);
        return mapService.getLocationById(locationId).chain(node -> {
            if (node == null) return getStageContext(conversationId);
            return localMapService.ensureLayout(locationId).chain(local -> {
                if (local == null || local.getAreas().isEmpty()) {
                    return getStageContext(conversationId);
                }
                LocalArea entry = LocalMapService.entryArea(local.getAreas());
                JsonObject updates = new JsonObject()
                        .put("currentLocation", node.getName())
                        .put("currentLocationId", node.getLocationId().toString())
                        .put("currentArea", entry.getName())
                        .put("currentAreaId", entry.getLocalAreaId().toString());
                return worldStateService.updateWorldState(conversationId, updates)
                        .chain(state -> localMapService.markAreaVisited(
                                conversationId, node.getLocationId().toString(),
                                entry.getLocalAreaId().toString()))
                        .chain(v -> localMapService.loadVisitedAreaIds(
                                conversationId, node.getLocationId().toString()))
                        .chain(visited -> mapService.getMaps(conversationId)
                                .chain(maps -> {
                                    if (maps == null || maps.isEmpty()) {
                                        return Uni.createFrom().item(StageContext.empty());
                                    }
                                    return buildStage(maps.get(0), node, entry, local, visited);
                                }));
            });
        });
    }

    /** Puts a newly introduced character at the current stage. */
    public Uni<Void> bringCharacterToStage(String conversationId, String characterId) {
        if (characterId == null) return Uni.createFrom().voidItem();
        return getStageContext(conversationId).chain(stage -> {
            if (!stage.isPresent()) return Uni.createFrom().voidItem();
            return mapService.updateCharacterPosition(stage.getMapId(), characterId,
                    stage.getNode().getLocationId().toString(),
                    stage.getNode().getX(), stage.getNode().getY(), stage.getNode().getZ());
        }).onFailure().recoverWithItem((Void) null);
    }

    // ==================== Helpers ====================

    private static StageMovementResult none(StageContext stage, String reason) {
        return StageMovementResult.builder()
                .kind(StageMovementPolicy.Kind.NONE)
                .stage(stage)
                .reason(reason)
                .build();
    }

    private static StageMovementResult rejected(StageContext stage, String reason) {
        return StageMovementResult.builder()
                .kind(StageMovementPolicy.Kind.REJECTED)
                .stage(stage)
                .reason(reason)
                .build();
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.strip();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String normalizedName(String value) {
        return LocalMapService.normalize(value);
    }

    /**
     * Compass bearing from one area to another in the layout's screen axes:
     * +x is east, +y is south (the panel renders y downward). One of the eight
     * compass points, so narration and the local map agree on direction.
     */
    public static String compassDirection(float fromX, float fromY, float toX, float toY) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        if (Math.abs(dx) < 1e-3 && Math.abs(dy) < 1e-3) return "straight ahead";
        // 0 = east, 90 = south, 180/-180 = west, -90 = north (y grows southward)
        double angle = Math.toDegrees(Math.atan2(dy, dx));
        String[] names = {"east", "southeast", "south", "southwest",
                "west", "northwest", "north", "northeast"};
        int index = (int) Math.round(angle / 45.0) % 8;
        if (index < 0) index += 8;
        return names[index];
    }

    private static WorldMap mapOf(StageContext stage) {
        return WorldMap.builder()
                .mapId(UUID.fromString(stage.getMapId()))
                .name(stage.getMapName())
                .mapScale(stage.getMapScale())
                .build();
    }

    private static LocalMap localOf(StageContext stage) {
        return LocalMap.builder()
                .mapId(UUID.fromString(stage.getMapId()))
                .locationId(stage.getNode().getLocationId())
                .areas(stage.getAreas())
                .connections(stage.getConnections())
                .build();
    }
}
