package com.eonmux.chatllm.map;

import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.models.inputs.UpdateEntityPositionInput;
import com.eonmux.chatllm.models.inputs.WorldMapInput;
import com.eonmux.chatllm.services.auth.AuthContext;
import com.eonmux.chatllm.utils.ServiceHelper;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MapResolverAccessTest {
    MapResolver resolver;
    UUID userId = UUID.randomUUID();

    @BeforeEach void setup() {
        resolver = new MapResolver();
        resolver.graphQLAuthContext = mock(GraphQLAuthContext.class);
        AuthContext auth = mock(AuthContext.class);
        when(auth.getUserId()).thenReturn(userId);
        when(resolver.graphQLAuthContext.requireAuth()).thenReturn(auth);
        resolver.serviceHelper = mock(ServiceHelper.class);
        resolver.mapService = mock(MapService.class);
        resolver.travelService = mock(TravelService.class);
        resolver.journeyService = mock(JourneyService.class);
        when(resolver.serviceHelper.verifyMapAccess(anyString(), anyString())).thenReturn(Uni.createFrom().item(false));
        when(resolver.serviceHelper.verifyConversationAccess(anyString(), anyString())).thenReturn(Uni.createFrom().item(false));
    }

    @Test void rejectsUnauthorizedMapReadsAndTravelBeforeCallingServices() {
        assertThrows(SecurityException.class, () -> resolver.worldMaps("conversation").await().indefinitely());
        assertThrows(SecurityException.class, () -> resolver.worldMap("map").await().indefinitely());
        assertThrows(SecurityException.class, () -> resolver.terrainData("map").await().indefinitely());
        assertThrows(SecurityException.class, () -> resolver.entityPositions("map").await().indefinitely());
        assertThrows(SecurityException.class, () -> resolver.travelPreview("map", "from", "to").await().indefinitely());
        assertThrows(SecurityException.class, () -> resolver.beginJourney("map", "to", null).await().indefinitely());
        assertThrows(SecurityException.class, () -> resolver.cancelJourney("conversation").await().indefinitely());
        verifyNoInteractions(resolver.mapService, resolver.travelService, resolver.journeyService);
    }

    @Test void checksConversationAccessBeforeCreatingMaps() {
        WorldMapInput input = new WorldMapInput();
        input.setConversationId("conversation");
        assertThrows(SecurityException.class, () -> resolver.createWorldMap(input).await().indefinitely());
    }

    @Test void allowsAuthorizedMapReads() {
        when(resolver.serviceHelper.verifyMapAccess("map", userId.toString())).thenReturn(Uni.createFrom().item(true));
        when(resolver.mapService.getRoads("map")).thenReturn(Uni.createFrom().item(java.util.List.of()));
        assertTrue(resolver.mapRoads("map").await().indefinitely().isEmpty());
    }

    @Test void rejectsItemConversationAndLocationMismatches() {
        when(resolver.serviceHelper.verifyMapAccess("map", userId.toString())).thenReturn(Uni.createFrom().item(true));
        resolver.mysqlPool = com.eonmux.chatllm.testing.SqlTestSupport.pool((sql, parameters) ->
            com.eonmux.chatllm.testing.SqlTestSupport.rows());
        var input = new com.eonmux.chatllm.models.inputs.AddMapItemPositionInput();
        input.setMapId("map");
        input.setConversationId("another-conversation");
        input.setLinkedLocationId("another-location");
        assertThrows(IllegalArgumentException.class, () -> resolver.addMapItemPosition(input).await().indefinitely());
        verifyNoInteractions(resolver.mapService);
    }

    @Test void refusesAmbiguousEntitiesAndMovingOtherPlayers() {
        UpdateEntityPositionInput input = new UpdateEntityPositionInput();
        input.setMapId("map");
        assertThrows(IllegalArgumentException.class, () -> resolver.updateEntityPosition(input).await().indefinitely());
        input.setUserId(UUID.randomUUID().toString());
        assertThrows(SecurityException.class, () -> resolver.updateEntityPosition(input).await().indefinitely());
        input.setCharacterId("character");
        assertThrows(IllegalArgumentException.class, () -> resolver.updateEntityPosition(input).await().indefinitely());
        verifyNoInteractions(resolver.mapService);
    }
}
