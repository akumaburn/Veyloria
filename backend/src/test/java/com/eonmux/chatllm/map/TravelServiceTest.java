package com.eonmux.chatllm.map;

import com.eonmux.chatllm.map.models.RoadSegment;
import com.eonmux.chatllm.models.MapLocation;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TravelServiceTest {
    TravelService service;
    MapService maps;
    UUID mapId = UUID.randomUUID();
    MapLocation from = location(0, 0), to = location(100, 0);

    MapLocation location(float x, float y) {
        return MapLocation.builder().mapId(mapId).locationId(UUID.randomUUID()).name("Place")
            .x(x).y(y).z(0f).build();
    }

    @BeforeEach void setup() {
        service = new TravelService();
        maps = mock(MapService.class);
        service.mapService = maps;
        when(maps.getLocationById(from.getLocationId().toString())).thenReturn(Uni.createFrom().item(from));
        when(maps.getLocationById(to.getLocationId().toString())).thenReturn(Uni.createFrom().item(to));
        when(maps.getTerrainData(mapId.toString())).thenReturn(Uni.createFrom().nullItem());
    }

    RoadSegment road(MapLocation a, MapLocation b, String type, List<float[]> points) {
        return RoadSegment.builder().fromLocationId(a.getLocationId().toString())
            .toLocationId(b.getLocationId().toString()).roadType(type).waypoints(points).build();
    }

    @Test void roadBonusesAffectCostWithoutChangingDistance() {
        for (String type : List.of("main", "trail")) {
            when(maps.getRoads(mapId.toString())).thenReturn(Uni.createFrom().item(List.of(
                road(from, to, type, List.of(new float[]{0,0,0}, new float[]{100,0,0})))));
            var result = service.calculateTravel(mapId.toString(), from.getLocationId().toString(),
                to.getLocationId().toString()).await().indefinitely();
            assertEquals(100f, result.getDistance(), 0.001);
            assertEquals(type.equals("main") ? 40f : 70f, result.getEffectiveCost(), 0.001);
            assertEquals(type.equals("main") ? 4 : 7, result.getTimePeriodsRequired());
        }
    }

    @Test void choosesTheFasterRoadAndUsesItsActualWaypoints() {
        when(maps.getRoads(mapId.toString())).thenReturn(Uni.createFrom().item(List.of(
            road(from, to, "trail", List.of(new float[]{0,0,0}, new float[]{100,0,0})),
            road(from, to, "main", List.of(new float[]{0,0,0}, new float[]{50,30,0}, new float[]{100,0,0})))));
        var result = service.calculateTravel(mapId.toString(), from.getLocationId().toString(),
            to.getLocationId().toString()).await().indefinitely();
        assertEquals("main", result.getRoadType());
        assertEquals(30f, result.getRoadWaypoints().get(1)[1]);
        assertEquals(Math.hypot(50,30) * 2 * 0.4, result.getEffectiveCost(), 0.001);
    }

    @Test void rejectsCrossMapDestinations() {
        to.setMapId(UUID.randomUUID());
        when(maps.getRoads(mapId.toString())).thenReturn(Uni.createFrom().item(List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.calculateTravel(mapId.toString(),
            from.getLocationId().toString(), to.getLocationId().toString()).await().indefinitely());
    }

    @Test void ignoresRoadsWithoutAPath() {
        when(maps.getRoads(mapId.toString())).thenReturn(Uni.createFrom().item(List.of(road(from, to, "main", null))));
        var result = service.calculateTravel(mapId.toString(), from.getLocationId().toString(),
            to.getLocationId().toString()).await().indefinitely();
        assertTrue(Float.isFinite(result.getEffectiveCost()));
        assertEquals(100f, result.getDistance(), 0.001);
        assertFalse(result.isUsesRoad());
    }
}
