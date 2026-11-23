package com.eonmux.chatllm.map;

import com.eonmux.chatllm.models.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StageDestinationResolutionTest {
    LocalArea square = area("Market Square"), tavern = area("Tavern"), alley = area("Alley");
    LocalArea area(String name) { return LocalArea.builder().localAreaId(UUID.randomUUID()).name(name).build(); }
    LocalAreaConnection link(LocalArea target, String label) {
        return LocalAreaConnection.builder().fromAreaId(square.getLocalAreaId()).toAreaId(target.getLocalAreaId()).label(label).build();
    }
    StageContext stage(List<LocalAreaConnection> links) {
        return StageContext.builder().node(MapLocation.builder().locationId(UUID.randomUUID()).name("Crystalport").build())
            .area(square).areas(List.of(square,tavern,alley)).connections(links).build();
    }
    @Test void passageLabelsResolveToTheActualAreaRatherThanCreatingATavernLaneRoom() {
        var stage = stage(List.of(link(tavern,"tavern lane")));
        assertEquals(tavern,StageService.findLocalDestination(stage,"Tavern Lane"));
        assertEquals(tavern,StageService.findLocalDestination(stage,tavern.getLocalAreaId().toString()));
        assertEquals(tavern,StageService.findLocalDestination(stage,"the Tavern"));
        assertEquals(StageMovementPolicy.Kind.AREA,StageMovementPolicy.forArea(square,tavern,stage.getConnections()).kind());
    }
    @Test void ambiguousPassageLabelsRequireARealDestination() {
        var stage = stage(List.of(link(tavern,"side passage"),link(alley,"side passage")));
        assertNull(StageService.findLocalDestination(stage,"side passage"));
        assertNull(StageService.findLocalDestination(stage,"unknown cellar"));
    }
    @Test void knowingARoomDoesNotPermitCrossingDisconnectedAreas() {
        var stage=stage(List.of(link(alley,"alley mouth")));
        assertEquals(StageMovementPolicy.Kind.REJECTED,StageMovementPolicy.forArea(square,
            StageService.findLocalDestination(stage,"Tavern"),stage.getConnections()).kind());
    }
}
