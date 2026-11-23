package com.eonmux.chatllm.map;

import com.eonmux.chatllm.config.RoleplayEventAddresses;
import com.eonmux.chatllm.map.models.Biome;
import com.eonmux.chatllm.map.models.RoadSegment;
import com.eonmux.chatllm.map.models.TerrainData;
import com.eonmux.chatllm.map.models.TravelResult;
import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.models.MapCharacterPosition;
import com.eonmux.chatllm.models.MapLocation;
import com.eonmux.chatllm.services.GroupChatService;
import com.eonmux.chatllm.services.SceneService;
import com.eonmux.chatllm.services.WorldStateService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles travel calculation and execution between map locations.
 * Integrates biome costs, road bonuses, time advancement, and discovery.
 */
@Slf4j
@ApplicationScoped
public class TravelService {

    private static final float BASE_SPEED = 10f;
    private static final float ARRIVAL_DISCOVERY_RADIUS = 15f;

    private static final Map<Biome, Float> BIOME_COSTS = Map.ofEntries(
        Map.entry(Biome.DEEP_WATER, 5.0f),
        Map.entry(Biome.GRASSLAND, 1.0f),
        Map.entry(Biome.FOREST, 1.5f),
        Map.entry(Biome.DENSE_FOREST, 2.0f),
        Map.entry(Biome.HILLS, 1.8f),
        Map.entry(Biome.MOUNTAIN, 3.0f),
        Map.entry(Biome.SNOW_PEAK, 4.0f),
        Map.entry(Biome.DESERT, 2.0f),
        Map.entry(Biome.SWAMP, 2.5f),
        Map.entry(Biome.SHALLOW_WATER, 3.0f),
        Map.entry(Biome.BEACH, 1.2f),
        Map.entry(Biome.RIVER, 2.5f),
        Map.entry(Biome.LAKE, 4.0f),
        Map.entry(Biome.TUNDRA, 1.8f)
    );

    private static final Map<String, Float> ROAD_MULTIPLIERS = Map.of(
        "main", 0.4f,
        "trail", 0.7f
    );

    private static final float SEA_COST_MULTIPLIER = 1.5f;
    private static final int SEA_ACCESS_CHECK_RADIUS = 2;

    private static class RoadPathEdge {
        final String targetId;
        final float distance;
        final RoadSegment road;
        RoadPathEdge(String targetId, float distance, RoadSegment road) {
            this.targetId = targetId;
            this.distance = distance;
            this.road = road;
        }
    }

    @Inject
    Vertx vertx;

    @Inject
    Pool mysqlPool;
    @Inject com.eonmux.chatllm.services.LocationResidentService residents;

    @Inject
    MapService mapService;

    @Inject
    WorldStateService worldStateService;

    @Inject
    TravelSceneService travelSceneService;

    @Inject com.eonmux.chatllm.services.StoryMessageService storyMessageService;

    @Inject
    GroupChatService groupChatService;

    @Inject
    StageService stageService;

    /**
     * Calculate travel cost between two locations without executing the travel.
     */
    public Uni<TravelResult> calculateTravel(String mapId, String fromLocationId, String toLocationId) {
        Uni<MapLocation> fromUni = mapService.getLocationById(fromLocationId);
        Uni<MapLocation> toUni = mapService.getLocationById(toLocationId);
        Uni<List<RoadSegment>> roadsUni = mapService.getRoads(mapId);
        Uni<TerrainData> terrainUni = mapService.getTerrainData(mapId);

        return Uni.combine().all().unis(fromUni, toUni, roadsUni, terrainUni)
            .asTuple()
            .map(tuple -> {
                MapLocation from = tuple.getItem1();
                MapLocation to = tuple.getItem2();
                List<RoadSegment> roads = tuple.getItem3();
                TerrainData terrain = tuple.getItem4();

                if (from == null) throw new RuntimeException("From location not found: " + fromLocationId);
                if (to == null) throw new RuntimeException("To location not found: " + toLocationId);

                if (from.getMapId() == null || to.getMapId() == null ||
                    !mapId.equals(from.getMapId().toString()) || !mapId.equals(to.getMapId().toString())) {
                    throw new IllegalArgumentException("Both locations must belong to the requested map");
                }
                return computeTravelResult(from, to, roads, terrain);
            });
    }

    /** Cost a new route from an actual roadside position; never snap back to a named node. */
    public Uni<TravelResult> calculateTravelFromPosition(String mapId, MapLocation origin, String toLocationId) {
        return Uni.combine().all().unis(mapService.getLocationById(toLocationId), mapService.getTerrainData(mapId)).asTuple().map(state -> {
            MapLocation destination = state.getItem1();
            if (origin == null || destination == null || !mapId.equals(destination.getMapId().toString()))
                throw new IllegalArgumentException("Destination does not belong to this map");
            return computeTravelResult(origin, destination, List.of(), state.getItem2());
        });
    }

    /**
     * Execute travel: move entities, advance time, discover locations, handle scene links.
     */
    public Uni<TravelResult> executeTravelAndAdvanceTime(String conversationId, String mapId,
                                                             String toLocationId, String userId,
                                                             List<String> characterIds) {
        // Find current position from entity positions
        return (residents == null ? Uni.createFrom().item(characterIds == null ? List.<String>of() : characterIds) : residents.partyCharacters(conversationId,characterIds))
            .chain(travelingCharacters -> mapService.getEntityPositions(mapId)
            .chain(positions -> {
                // Find user's current location
                MapCharacterPosition userPos = positions.stream()
                    .filter(p -> p.getUserId() != null && p.getUserId().toString().equals(userId))
                    .findFirst()
                    .orElse(null);

                String fromLocationId;
                if (userPos != null && userPos.getLocationId() != null) {
                    fromLocationId = userPos.getLocationId().toString();
                } else {
                    return Uni.createFrom().failure(new RuntimeException("User has no current position on this map"));
                }

                if (fromLocationId.equals(toLocationId)) {
                    return Uni.createFrom().failure(new RuntimeException("Already at destination"));
                }

                return calculateTravel(mapId, fromLocationId, toLocationId)
                    .chain(travelResult -> {
                        return mapService.getLocationById(toLocationId).chain(destLoc -> {
                            if (destLoc == null) {
                                return Uni.createFrom().failure(new RuntimeException("Destination not found"));
                            }

                            float dx = destLoc.getX();
                            float dy = destLoc.getY();
                            float dz = destLoc.getZ();

                            // Move all entities to destination
                            List<Uni<Void>> moveUnis = new ArrayList<>();
                            moveUnis.add(mapService.updateUserPosition(mapId, userId, toLocationId, dx, dy, dz));
                            if (travelingCharacters != null) {
                                for (String charId : travelingCharacters) {
                                    moveUnis.add(mapService.updateCharacterPosition(mapId, charId, toLocationId, dx, dy, dz));
                                }
                            }

                            return Uni.combine().all().unis(moveUnis).discardItems()
                                .chain(v -> {
                                    // Discover destination + nearby locations
                                    return mapService.discoverNearbyLocations(mapId, dx, dy, dz, ARRIVAL_DISCOVERY_RADIUS);
                                })
                                .chain(discoveredLocs -> {
                                    // Also discover the destination itself if not already
                                    Uni<Void> discoverDest = destLoc.getIsDiscovered() != null && destLoc.getIsDiscovered()
                                        ? Uni.createFrom().voidItem()
                                        : mysqlPool.preparedQuery("UPDATE map_location SET isDiscovered = TRUE WHERE locationId = ?")
                                            .execute(Tuple.of(toLocationId)).replaceWithVoid();

                                    return discoverDest.map(v -> discoveredLocs);
                                })
                                .chain(discoveredLocs -> {
                                    List<String> discoveredIds = discoveredLocs.stream()
                                        .map(l -> l.getLocationId().toString())
                                        .collect(Collectors.toList());

                                    // Advance time N periods
                                    Uni<Void> timeUni = Uni.createFrom().voidItem();
                                    for (int i = 0; i < travelResult.getTimePeriodsRequired(); i++) {
                                        timeUni = timeUni.chain(v ->
                                            worldStateService.progressTimeOfDay(conversationId));
                                    }

                                    return timeUni.chain(v -> {
                                        // Update world state and put the party in
                                        // the destination's entry area.
                                        return stageService.placeAtNode(conversationId, toLocationId);
                                    }).chain(ws -> {
                                        // Build final result with discovered IDs
                                        TravelResult finalResult = TravelResult.builder()
                                            .distance(travelResult.getDistance())
                                            .effectiveCost(travelResult.getEffectiveCost())
                                            .timePeriodsRequired(travelResult.getTimePeriodsRequired())
                                            .usesRoad(travelResult.isUsesRoad())
                                            .roadType(travelResult.getRoadType())
                                            .dominantBiome(travelResult.getDominantBiome())
                                            .fromLocationId(travelResult.getFromLocationId())
                                            .toLocationId(travelResult.getToLocationId())
                                            .fromLocationName(travelResult.getFromLocationName())
                                            .toLocationName(travelResult.getToLocationName())
                                            .discoveredLocationIds(discoveredIds)
                                            .build();

                                        // Publish events (fire-and-forget)
                                        publishTravelEvents(conversationId, mapId, finalResult, discoveredLocs, destLoc, userId, travelingCharacters);

                                        String arrivalKey="fast-travel:"+java.util.UUID.randomUUID();
                                        String arrival="The party traveled from "+finalResult.getFromLocationName()+" to "+finalResult.getToLocationName()+".";
                                        return (residents == null ? Uni.createFrom().<Void>voidItem() : residents.leave(conversationId))
                                            .chain(() -> travelSceneService.enter(conversationId, destLoc, userId, travelingCharacters))
                                            .chain(() -> storyMessageService.narrate(conversationId,arrivalKey,arrival,null))
                                            .chain(() -> {
                                                Uni<Void> welcome=travelSceneService.welcome(conversationId,userId,arrivalKey,arrival);
                                                return welcome == null ? Uni.createFrom().voidItem() : welcome;
                                            })
                                            .invoke(() -> triggerCharacterResponseOnArrival(conversationId, userId))
                                            .replaceWith(finalResult);
                                    });
                                });
                        });
                    });
            }));
    }

    private TravelResult computeTravelResult(MapLocation from, MapLocation to,
                                              List<RoadSegment> roads, TerrainData terrain) {
        float dx = to.getX() - from.getX();
        float dy = to.getY() - from.getY();
        float dz = to.getZ() - from.getZ();
        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        String fromId = from.getLocationId().toString();
        String toId = to.getLocationId().toString();

        // Stage 1: Road-assisted path — use roads as far as they get closer to the goal,
        // then finish with off-road trekking (even if total distance is longer)
        TravelResult roadResult = computeRoadAssistedTravel(from, to, roads, terrain);
        if (roadResult != null) {
            return roadResult;
        }

        // Stage 2: Sea route — both locations accessible by water
        if (hasSeaAccess(from, terrain) && hasSeaAccess(to, terrain)) {
            float seaCost = distance * SEA_COST_MULTIPLIER;
            int timePeriods = Math.max(1, Math.round(seaCost / BASE_SPEED));

            return TravelResult.builder()
                .distance(distance)
                .effectiveCost(seaCost)
                .timePeriodsRequired(timePeriods)
                .usesRoad(false)
                .roadType(null)
                .dominantBiome("SHALLOW_WATER")
                .fromLocationId(fromId)
                .toLocationId(toId)
                .fromLocationName(from.getName())
                .toLocationName(to.getName())
                .roadWaypoints(null)
                .build();
        }

        // Stage 3: Pure terrain trekking (straight-line with biome costs)
        RoadSegment connectingRoad = findConnectingRoad(fromId, toId, roads);
        boolean usesRoad = connectingRoad != null && Float.isFinite(computeSegmentLength(connectingRoad));
        String roadType = usesRoad ? connectingRoad.getRoadType() : null;
        float roadMultiplier = usesRoad
            ? ROAD_MULTIPLIERS.getOrDefault(roadType, 0.7f)
            : 1.0f;

        float effectiveCost = distance * sampleBiomeCostAlongPath(from.getX(), from.getY(), to.getX(), to.getY(), terrain) * roadMultiplier;
        int timePeriods = Math.max(1, Math.round(effectiveCost / BASE_SPEED));

        String dominantBiome = determineDominantBiome(from, to, terrain);

        List<float[]> orientedRoadWaypoints = null;
        if (usesRoad && connectingRoad.getWaypoints() != null && connectingRoad.getWaypoints().size() >= 2) {
            orientedRoadWaypoints = new ArrayList<>(connectingRoad.getWaypoints());
            if (toId.equals(connectingRoad.getFromLocationId())) {
                Collections.reverse(orientedRoadWaypoints);
            }
        }

        return TravelResult.builder()
            .distance(distance)
            .effectiveCost(effectiveCost)
            .timePeriodsRequired(timePeriods)
            .usesRoad(usesRoad)
            .roadType(roadType)
            .dominantBiome(dominantBiome)
            .fromLocationId(fromId)
            .toLocationId(toId)
            .fromLocationName(from.getName())
            .toLocationName(to.getName())
            .roadWaypoints(orientedRoadWaypoints)
            .build();
    }

    /**
     * Find the best route that uses roads as far as they take us closer to the
     * destination, then finishes off-road. Returns null if roads don't help
     * (i.e., no road node is closer to the destination than the origin is).
     */
    private TravelResult computeRoadAssistedTravel(MapLocation from, MapLocation to,
                                                     List<RoadSegment> roads, TerrainData terrain) {
        if (roads == null || roads.isEmpty()) return null;

        String fromId = from.getLocationId().toString();
        String toId = to.getLocationId().toString();

        // Build adjacency + position lookup from road segment waypoints
        Map<String, List<RoadPathEdge>> adjacency = new HashMap<>();
        Map<String, float[]> nodePositions = new HashMap<>();
        nodePositions.put(fromId, new float[]{from.getX(), from.getY(), from.getZ()});
        nodePositions.put(toId, new float[]{to.getX(), to.getY(), to.getZ()});

        for (RoadSegment road : roads) {
            String a = road.getFromLocationId();
            String b = road.getToLocationId();
            float segDist = computeSegmentLength(road);
            if (!Float.isFinite(segDist) || segDist <= 0) continue;
            adjacency.computeIfAbsent(a, k -> new ArrayList<>())
                .add(new RoadPathEdge(b, segDist, road));
            adjacency.computeIfAbsent(b, k -> new ArrayList<>())
                .add(new RoadPathEdge(a, segDist, road));
            if (road.getWaypoints() != null && road.getWaypoints().size() >= 2) {
                float[] first = road.getWaypoints().get(0);
                float[] last = road.getWaypoints().get(road.getWaypoints().size() - 1);
                nodePositions.putIfAbsent(a, new float[]{first[0], first[1], first.length > 2 ? first[2] : 0});
                nodePositions.putIfAbsent(b, new float[]{last[0], last[1], last.length > 2 ? last[2] : 0});
            }
        }

        // Cannot use roads if origin isn't on the road graph (no way to reach any road)
        if (!adjacency.containsKey(fromId)) return null;

        // Dijkstra from origin to all reachable road nodes
        Map<String, Double> roadDist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        Map<String, RoadSegment> prevRoad = new HashMap<>();

        PriorityQueue<Object[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> (Double) a[0]));
        pq.add(new Object[]{0.0, fromId});
        roadDist.put(fromId, 0.0);

        while (!pq.isEmpty()) {
            Object[] entry = pq.poll();
            String current = (String) entry[1];
            double curDist = (Double) entry[0];
            if (curDist > roadDist.getOrDefault(current, Double.MAX_VALUE)) continue;

            for (RoadPathEdge edge : adjacency.getOrDefault(current, List.of())) {
                double newDist = curDist + edge.distance * ROAD_MULTIPLIERS.getOrDefault(edge.road.getRoadType(), 0.7f);
                if (newDist < roadDist.getOrDefault(edge.targetId, Double.MAX_VALUE)) {
                    roadDist.put(edge.targetId, newDist);
                    prev.put(edge.targetId, current);
                    prevRoad.put(edge.targetId, edge.road);
                    pq.add(new Object[]{newDist, edge.targetId});
                }
            }
        }

        float origToGoalDist = (float) Math.sqrt(
            (to.getX() - from.getX()) * (to.getX() - from.getX()) +
            (to.getY() - from.getY()) * (to.getY() - from.getY()));

        // Find the reachable road node closest to the destination (Euclidean distance).
        // If a road gets us closer to the goal than starting from origin, we ALWAYS use it,
        // even if the total distance is longer — roads are preferred over terrain trekking.
        String bestNode = fromId;
        float bestDistToGoal = origToGoalDist;

        for (Map.Entry<String, Double> entry : roadDist.entrySet()) {
            String nodeId = entry.getKey();
            if (nodeId.equals(fromId)) continue;
            float[] nPos = nodePositions.get(nodeId);
            if (nPos == null) continue;

            float ndx = to.getX() - nPos[0];
            float ndy = to.getY() - nPos[1];
            float nodeToGoalDist = (float) Math.sqrt(ndx * ndx + ndy * ndy);

            if (nodeToGoalDist < bestDistToGoal) {
                bestDistToGoal = nodeToGoalDist;
                bestNode = nodeId;
            }
        }

        // If no road node gets closer to the destination than origin, roads don't help
        if (bestNode.equals(fromId)) return null;

        // Compute the actual cost: road cost (with road multiplier) + off-road cost (with biome cost)
        double roadCost = roadDist.get(bestNode);
        float[] bestNodePos = nodePositions.get(bestNode);
        float offRoadDist = (float) Math.sqrt(
            (to.getX() - bestNodePos[0]) * (to.getX() - bestNodePos[0]) +
            (to.getY() - bestNodePos[1]) * (to.getY() - bestNodePos[1]));
        float offRoadBiomeCost = sampleBiomeCostAlongPath(
            bestNodePos[0], bestNodePos[1], to.getX(), to.getY(), terrain);
        float effectiveCost = (float) (roadCost + offRoadDist * offRoadBiomeCost);

        // Reconstruct road path from origin to bestNode
        List<String> path = new ArrayList<>();
        String cur = bestNode;
        while (cur != null && !cur.equals(fromId)) {
            path.add(cur);
            cur = prev.get(cur);
        }
        path.add(fromId);
        Collections.reverse(path);

        // Build concatenated waypoints and road type info
        List<float[]> waypoints = new ArrayList<>();
        List<String> roadTypes = new ArrayList<>();
        float totalRoadDist = 0;

        for (int i = 0; i < path.size() - 1; i++) {
            String a = path.get(i);
            String b = path.get(i + 1);
            RoadSegment seg = prevRoad.get(b);
            if (seg == null) continue;

            float segDist = computeSegmentLength(seg);
            totalRoadDist += segDist;
            roadTypes.add(seg.getRoadType());

            List<float[]> segWps = seg.getWaypoints();
            if (segWps == null || segWps.size() < 2) continue;

            List<float[]> oriented;
            if (a.equals(seg.getFromLocationId())) {
                oriented = new ArrayList<>(segWps);
            } else {
                oriented = new ArrayList<>(segWps);
                Collections.reverse(oriented);
            }

            if (waypoints.isEmpty()) {
                waypoints.addAll(oriented);
            } else {
                waypoints.addAll(oriented.subList(1, oriented.size()));
            }
        }

        // Append destination as the final waypoint (off-road segment)
        waypoints.add(new float[]{to.getX(), to.getY(), to.getZ()});

        int timePeriods = Math.max(1, Math.round(effectiveCost / BASE_SPEED));
        String dominantBiome = determineDominantBiome(from, to, terrain);

        return TravelResult.builder()
            .distance(totalRoadDist + offRoadDist)
            .effectiveCost(effectiveCost)
            .timePeriodsRequired(timePeriods)
            .usesRoad(true)
            .roadType(dominantRoadType(roadTypes))
            .dominantBiome(dominantBiome)
            .fromLocationId(fromId)
            .toLocationId(toId)
            .fromLocationName(from.getName())
            .toLocationName(to.getName())
            .roadWaypoints(waypoints)
            .build();
    }

    private float sampleBiomeCostAlongPath(float fromX, float fromY, float toX, float toY, TerrainData terrain) {
        if (terrain == null || terrain.getBiomeGrid() == null) {
            return 1.0f;
        }

        int width = terrain.getWidth();
        int height = terrain.getHeight();
        int[] biomeGrid = terrain.getBiomeGrid();
        int samples = 10;
        float totalCost = 0f;

        for (int i = 0; i <= samples; i++) {
            float t = i / (float) samples;
            float wx = fromX + t * (toX - fromX);
            float wy = fromY + t * (toY - fromY);

            float ws = worldSizeForScale(terrain.getMapScale());
            float wo = ws * 0.5f;
            int gx = Math.round((wx + wo) / ws * width);
            int gy = Math.round((wy + wo) / ws * height);
            gx = Math.max(0, Math.min(width - 1, gx));
            gy = Math.max(0, Math.min(height - 1, gy));

            int biomeId = biomeGrid[gy * width + gx];
            Biome biome = Biome.fromId(biomeId);
            totalCost += BIOME_COSTS.getOrDefault(biome, 1.0f);
        }

        return totalCost / (samples + 1);
    }

    private String determineDominantBiome(MapLocation from, MapLocation to, TerrainData terrain) {
        if (terrain == null || terrain.getBiomeGrid() == null) {
            return "GRASSLAND";
        }

        int width = terrain.getWidth();
        int height = terrain.getHeight();
        int[] biomeGrid = terrain.getBiomeGrid();

        // Sample midpoint biome
        float mx = (from.getX() + to.getX()) / 2f;
        float my = (from.getY() + to.getY()) / 2f;
        float ws = worldSizeForScale(terrain.getMapScale());
        float wo = ws * 0.5f;
        int gx = Math.round((mx + wo) / ws * width);
        int gy = Math.round((my + wo) / ws * height);
        gx = Math.max(0, Math.min(width - 1, gx));
        gy = Math.max(0, Math.min(height - 1, gy));

        int biomeId = biomeGrid[gy * width + gx];
        return Biome.fromId(biomeId).name();
    }

    // worldSizeForScale() is now centralized in MapScaleUtils
    private static float worldSizeForScale(String scale) {
        return MapScaleUtils.worldSizeForScale(scale);
    }

    private RoadSegment findConnectingRoad(String fromId, String toId, List<RoadSegment> roads) {
        if (roads == null) return null;
        for (RoadSegment road : roads) {
            if ((fromId.equals(road.getFromLocationId()) && toId.equals(road.getToLocationId()))
                || (toId.equals(road.getFromLocationId()) && fromId.equals(road.getToLocationId()))) {
                return road;
            }
        }
        return null;
    }

    private float computeSegmentLength(RoadSegment road) {
        List<float[]> wps = road.getWaypoints();
        if (wps == null || wps.size() < 2) return Float.NaN;
        float len = 0;
        for (int i = 1; i < wps.size(); i++) {
            float[] a = wps.get(i - 1);
            float[] b = wps.get(i);
            float dx = b[0] - a[0];
            float dy = b[1] - a[1];
            float dz = (a.length > 2 && b.length > 2) ? b[2] - a[2] : 0;
            len += (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return len;
    }

    private String dominantRoadType(List<String> roadTypes) {
        if (roadTypes == null || roadTypes.isEmpty()) return null;
        Map<String, Float> distByType = new HashMap<>();
        for (String type : roadTypes) {
            distByType.merge(type, 1.0f, Float::sum);
        }
        return distByType.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    private boolean hasSeaAccess(MapLocation loc, TerrainData terrain) {
        if (terrain == null || terrain.getBiomeGrid() == null) return false;
        if ("port".equals(loc.getLocationType())) return true;

        int width = terrain.getWidth();
        int height = terrain.getHeight();
        float ws = worldSizeForScale(terrain.getMapScale());
        float wo = ws * 0.5f;
        int gx = Math.round((loc.getX() + wo) / ws * width);
        int gy = Math.round((loc.getY() + wo) / ws * height);
        gx = Math.max(0, Math.min(width - 1, gx));
        gy = Math.max(0, Math.min(height - 1, gy));

        for (int dy = -SEA_ACCESS_CHECK_RADIUS; dy <= SEA_ACCESS_CHECK_RADIUS; dy++) {
            for (int dx = -SEA_ACCESS_CHECK_RADIUS; dx <= SEA_ACCESS_CHECK_RADIUS; dx++) {
                int sx = Math.max(0, Math.min(width - 1, gx + dx));
                int sy = Math.max(0, Math.min(height - 1, gy + dy));
                Biome b = Biome.fromId(terrain.getBiomeGrid()[sy * width + sx]);
                if (b == Biome.SHALLOW_WATER || b == Biome.DEEP_WATER || b == Biome.BEACH) {
                    return true;
                }
            }
        }
        return false;
    }

    private void publishTravelEvents(String conversationId, String mapId, TravelResult result,
                                      List<MapLocation> discoveredLocs, MapLocation destination,
                                      String userId, List<String> characterIds) {
        // Publish entity moved -- fetch updated rows so payload matches MapCharacterPosition schema
        mapService.getEntityPositions(mapId)
            .subscribe().with(
                positions -> {
                    for (MapCharacterPosition pos : positions) {
                        boolean isMovedEntity = false;
                        if (pos.getUserId() != null && pos.getUserId().toString().equals(userId)) {
                            isMovedEntity = true;
                        }
                        if (characterIds != null && pos.getCharacterId() != null) {
                            for (String charId : characterIds) {
                                if (pos.getCharacterId().toString().equals(charId)) {
                                    isMovedEntity = true;
                                    break;
                                }
                            }
                        }
                        if (isMovedEntity) {
                            JsonObject moveEvent = new JsonObject()
                                .put("id", pos.getId().toString())
                                .put("mapId", pos.getMapId().toString())
                                .put("characterId", pos.getCharacterId() != null ? pos.getCharacterId().toString() : null)
                                .put("userId", pos.getUserId() != null ? pos.getUserId().toString() : null)
                                .put("locationId", pos.getLocationId() != null ? pos.getLocationId().toString() : null)
                                .put("x", pos.getX())
                                .put("y", pos.getY())
                                .put("z", pos.getZ());
                            vertx.eventBus().publish(RoleplayEventAddresses.MAP_ENTITY_MOVED + mapId, moveEvent);
                        }
                    }
                },
                err -> log.warn("[travel] Failed to fetch positions for move events: {}", err.getMessage())
            );

        // Publish location discovered for each newly discovered location
        for (MapLocation loc : discoveredLocs) {
            JsonObject discoverEvent = new JsonObject()
                .put("locationId", loc.getLocationId().toString())
                .put("mapId", loc.getMapId().toString())
                .put("name", loc.getName())
                .put("description", loc.getDescription())
                .put("x", loc.getX())
                .put("y", loc.getY())
                .put("z", loc.getZ())
                .put("icon", loc.getIcon())
                .put("locationType", loc.getLocationType())
                .put("isDiscovered", true);
            vertx.eventBus().publish(
                RoleplayEventAddresses.MAP_LOCATION_DISCOVERED + conversationId, discoverEvent);
        }

        // Publish world state changed
        worldStateService.getOrCreateWorldState(conversationId)
            .subscribe().with(
                ws -> vertx.eventBus().publish(
                    RoleplayEventAddresses.WORLD_STATE_CHANGED + conversationId,
                    WorldStateService.toGraphQL(ws)),
                err -> log.warn("[travel] Failed to publish world state: {}", err.getMessage())
            );
    }

    /**
     * Handles scene linkage when arriving at a destination.
     * If the destination already has a linkedSceneId, activates that scene.
     * If not, creates a new scene and links it to the location.
     */
    private void triggerCharacterResponseOnArrival(String conversationId, String userId) {
        groupChatService.triggerArrivalResponse(conversationId, userId)
            .subscribe().with(
                v -> {},
                err -> log.warn("[travel] Failed to trigger character response on arrival: {}",
                    err.getMessage())
            );
    }


}
