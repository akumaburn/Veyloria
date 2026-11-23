package com.eonmux.chatllm.map;

import com.eonmux.chatllm.models.LocalArea;
import com.eonmux.chatllm.models.LocalAreaConnection;
import com.eonmux.chatllm.models.LocalMap;
import com.eonmux.chatllm.models.MapLocation;
import com.eonmux.chatllm.utils.Time4JHelper;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stores and loads the interior layout of world-map nodes.
 *
 * <p>Layouts are generated on first entry and cached in
 * {@code local_area}/{@code local_area_connection}. Ids derive from the node
 * id and the area key, so two concurrent requests write the same rows instead
 * of duplicating the layout.</p>
 */
@ApplicationScoped
@Slf4j
public class LocalMapService {

    @Inject
    Pool pool;

    @Inject
    MapService mapService;

    /** Returns the layout for a node, generating it when absent. */
    public Uni<LocalMap> ensureLayout(String locationId) {
        if (locationId == null) return Uni.createFrom().nullItem();
        return loadAreas(locationId)
                .chain(areas -> areas.isEmpty()
                        ? generateAndStore(locationId)
                        : getLayout(locationId));
    }

    /** Returns the layout for a node, or null when the node does not exist. */
    public Uni<LocalMap> getLayout(String locationId) {
        if (locationId == null) return Uni.createFrom().nullItem();
        return mapService.getLocationById(locationId)
                .chain(node -> {
                    if (node == null) return Uni.createFrom().nullItem();
                    return Uni.combine().all()
                            .unis(loadAreas(locationId), loadConnections(locationId))
                            .asTuple()
                            .map(tuple -> LocalMap.builder()
                                    .mapId(node.getMapId())
                                    .locationId(node.getLocationId())
                                    .locationName(node.getName())
                                    .locationType(node.getLocationType())
                                    .areas(tuple.getItem1())
                                    .connections(tuple.getItem2())
                                    .build());
                });
    }

    public Uni<List<LocalArea>> loadAreas(String locationId) {
        return pool.preparedQuery("""
                        SELECT * FROM local_area
                        WHERE locationId = ?
                        ORDER BY sortOrder, name
                        """)
                .execute(Tuple.of(locationId))
                .map(rows -> {
                    List<LocalArea> areas = new ArrayList<>();
                    for (Row row : rows) areas.add(mapArea(row));
                    return areas;
                });
    }

    public Uni<List<LocalAreaConnection>> loadConnections(String locationId) {
        return pool.preparedQuery("""
                        SELECT * FROM local_area_connection
                        WHERE locationId = ?
                        ORDER BY createdAt, connectionId
                        """)
                .execute(Tuple.of(locationId))
                .map(rows -> {
                    List<LocalAreaConnection> connections = new ArrayList<>();
                    for (Row row : rows) connections.add(mapConnection(row));
                    return connections;
                });
    }

    public Uni<LocalArea> getEntryArea(String locationId) {
        return loadAreas(locationId).map(areas -> entryArea(areas));
    }

    // ==================== Exploration state ====================

    /**
     * Records that this conversation's party has been in an area.
     * Idempotent: re-entering an area only refreshes nothing, the row already
     * exists. The local map reveals areas exactly when this row appears.
     */
    public Uni<Void> markAreaVisited(String conversationId, String locationId, String localAreaId) {
        if (conversationId == null || locationId == null || localAreaId == null) {
            return Uni.createFrom().voidItem();
        }
        return pool.preparedQuery("""
                        INSERT INTO local_area_visit (conversationId, locationId, localAreaId)
                        VALUES (?, ?, ?)
                        ON DUPLICATE KEY UPDATE visitedAt = visitedAt
                        """)
                .execute(Tuple.of(conversationId, locationId, localAreaId))
                .replaceWithVoid();
    }

    /** Every area this conversation's party has been in, for one location. */
    public Uni<Set<String>> loadVisitedAreaIds(String conversationId, String locationId) {
        if (conversationId == null || locationId == null) return Uni.createFrom().item(new HashSet<>());
        return pool.preparedQuery("""
                        SELECT localAreaId FROM local_area_visit
                        WHERE conversationId = ? AND locationId = ?
                        """)
                .execute(Tuple.of(conversationId, locationId))
                .map(rows -> {
                    Set<String> ids = new HashSet<>();
                    for (Row row : rows) ids.add(row.getString("localAreaId"));
                    return ids;
                });
    }

    public Uni<LocalArea> getArea(String locationId, String areaId) {
        if (locationId == null || areaId == null) return Uni.createFrom().nullItem();
        return loadAreas(locationId).map(areas -> areas.stream()
                .filter(area -> area.getLocalAreaId().toString().equals(areaId))
                .findFirst()
                .orElse(null));
    }

    /**
     * Resolves a spoken or written place name to one area. Exact matches win.
     * A unique prefix or suffix match covers shortened names like "the hall".
     */
    public Uni<LocalArea> findArea(String locationId, String requested) {
        return loadAreas(locationId).map(areas -> findArea(areas, requested));
    }

    public static LocalArea findArea(List<LocalArea> areas, String requested) {
        if (areas == null || areas.isEmpty() || requested == null || requested.isBlank()) return null;
        for (LocalArea area : areas) {
            if (area.getLocalAreaId() != null && area.getLocalAreaId().toString().equalsIgnoreCase(requested.strip())) return area;
        }
        String wanted = normalize(requested);
        if (wanted.isBlank()) return null;
        for (LocalArea area : areas) {
            if (normalize(area.getName()).equals(wanted)) return area;
        }
        List<LocalArea> matches = new ArrayList<>();
        for (LocalArea area : areas) {
            String name = normalize(area.getName());
            if (name.startsWith(wanted) || wanted.startsWith(name)
                    || name.contains(wanted) || wanted.contains(name)) {
                matches.add(area);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    public static LocalArea entryArea(List<LocalArea> areas) {
        if (areas == null || areas.isEmpty()) return null;
        for (LocalArea area : areas) {
            if (Boolean.TRUE.equals(area.getIsEntry())) return area;
        }
        for (LocalArea area : areas) {
            if (!Boolean.TRUE.equals(area.getIsGateway())) return area;
        }
        return areas.get(0);
    }

    /** Returns every area reachable from {@code fromId} without leaving the node. */
    public static Set<UUID> reachableAreas(UUID fromId, List<LocalAreaConnection> connections) {
        Set<UUID> visited = new HashSet<>();
        if (fromId == null || connections == null) return visited;
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        queue.add(fromId);
        visited.add(fromId);
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            for (LocalAreaConnection connection : connections) {
                UUID other = null;
                if (current.equals(connection.getFromAreaId())) other = connection.getToAreaId();
                else if (current.equals(connection.getToAreaId())) other = connection.getFromAreaId();
                if (other != null && visited.add(other)) queue.addLast(other);
            }
        }
        return visited;
    }

    /** Shortest route between two areas, expressed as area ids including both ends. */
    public static List<UUID> route(UUID fromId, UUID toId, List<LocalAreaConnection> connections) {
        if (fromId == null || toId == null || connections == null) return List.of();
        if (fromId.equals(toId)) return List.of(fromId);
        Map<UUID, UUID> previous = new HashMap<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        Set<UUID> seen = new HashSet<>();
        queue.add(fromId);
        seen.add(fromId);
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            for (LocalAreaConnection connection : connections) {
                UUID other = null;
                if (current.equals(connection.getFromAreaId())) other = connection.getToAreaId();
                else if (current.equals(connection.getToAreaId())) other = connection.getFromAreaId();
                if (other == null || !seen.add(other)) continue;
                previous.put(other, current);
                if (other.equals(toId)) {
                    List<UUID> path = new ArrayList<>();
                    UUID cursor = toId;
                    while (cursor != null) {
                        path.add(0, cursor);
                        cursor = previous.get(cursor);
                    }
                    return path;
                }
                queue.addLast(other);
            }
        }
        return List.of();
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String text = value.toLowerCase(Locale.ROOT).strip();
        if (text.startsWith("the ")) text = text.substring(4).strip();
        return text.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
    }

    // ==================== Generation ====================

    private Uni<LocalMap> generateAndStore(String locationId) {
        return mapService.getLocationById(locationId).chain(node -> {
            if (node == null) return Uni.createFrom().nullItem();
            LocalLayoutFactory.Layout plan = LocalLayoutFactory.generate(
                    locationId, node.getName(), node.getLocationType(), node.getDescription());
            return store(node, plan).chain(v -> getLayout(locationId));
        });
    }

    private Uni<Void> store(MapLocation node, LocalLayoutFactory.Layout plan) {
        String locationId = node.getLocationId().toString();
        String mapId = node.getMapId().toString();
        Map<String, String> ids = new HashMap<>();
        for (LocalLayoutFactory.AreaDraft area : plan.areas()) {
            ids.put(area.key(), stableId(locationId + ":area:" + area.key()));
        }

        return pool.withTransaction(client -> {
            Uni<Void> chain = Uni.createFrom().voidItem();
            int order = 0;
            for (LocalLayoutFactory.AreaDraft area : plan.areas()) {
                int sortOrder = order++;
                String areaId = ids.get(area.key());
                chain = chain.chain(v -> client.preparedQuery("""
                                INSERT INTO local_area
                                    (localAreaId, mapId, locationId, name, description, areaType,
                                     x, y, isGateway, isEntry, sortOrder)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                ON DUPLICATE KEY UPDATE
                                    name = VALUES(name), description = VALUES(description),
                                    areaType = VALUES(areaType), x = VALUES(x), y = VALUES(y),
                                    isGateway = VALUES(isGateway), isEntry = VALUES(isEntry),
                                    sortOrder = VALUES(sortOrder)
                                """)
                        .execute(Tuple.tuple().addValue(areaId).addValue(mapId).addValue(locationId)
                                .addValue(area.name()).addValue(area.description())
                                .addValue(area.areaType()).addValue(area.x()).addValue(area.y())
                                .addValue(area.gateway()).addValue(area.entry()).addValue(sortOrder))
                        .replaceWithVoid());
            }
            for (LocalLayoutFactory.ConnectionDraft link : plan.connections()) {
                String connectionId = stableId(locationId + ":link:" + link.fromKey() + ":" + link.toKey());
                String fromId = ids.get(link.fromKey());
                String toId = ids.get(link.toKey());
                chain = chain.chain(v -> client.preparedQuery("""
                                INSERT INTO local_area_connection
                                    (connectionId, mapId, locationId, fromAreaId, toAreaId, label)
                                VALUES (?, ?, ?, ?, ?, ?)
                                ON DUPLICATE KEY UPDATE label = VALUES(label)
                                """)
                        .execute(Tuple.of(connectionId, mapId, locationId, fromId, toId, link.label()))
                        .replaceWithVoid());
            }
            return chain.invoke(() -> log.info(
                    "[local-map] Created layout for '{}' ({}) with {} areas and {} connections",
                    node.getName(), locationId, plan.areas().size(), plan.connections().size()));
        });
    }

    private static String stableId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ==================== Row mapping ====================

    private LocalArea mapArea(Row row) {
        return LocalArea.builder()
                .localAreaId(UUID.fromString(row.getString("localAreaId")))
                .mapId(UUID.fromString(row.getString("mapId")))
                .locationId(UUID.fromString(row.getString("locationId")))
                .name(row.getString("name"))
                .description(row.getString("description"))
                .areaType(row.getString("areaType"))
                .x(row.getFloat("x") == null ? 0f : row.getFloat("x"))
                .y(row.getFloat("y") == null ? 0f : row.getFloat("y"))
                .isGateway(row.getBoolean("isGateway"))
                .isEntry(row.getBoolean("isEntry"))
                .sortOrder(row.getInteger("sortOrder") == null ? 0 : row.getInteger("sortOrder"))
                .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                .build();
    }

    private LocalAreaConnection mapConnection(Row row) {
        return LocalAreaConnection.builder()
                .connectionId(UUID.fromString(row.getString("connectionId")))
                .mapId(UUID.fromString(row.getString("mapId")))
                .locationId(UUID.fromString(row.getString("locationId")))
                .fromAreaId(UUID.fromString(row.getString("fromAreaId")))
                .toAreaId(UUID.fromString(row.getString("toAreaId")))
                .label(row.getString("label"))
                .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                .build();
    }
}
