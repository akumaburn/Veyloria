package com.eonmux.chatllm.map;

import com.eonmux.chatllm.map.models.TravelResult;
import com.eonmux.chatllm.map.models.JourneyState;
import com.eonmux.chatllm.map.models.JourneyWaypoint;
import com.eonmux.chatllm.models.MapCharacterPosition;
import com.eonmux.chatllm.models.MapLocation;
import com.eonmux.chatllm.models.WorldState;
import com.eonmux.chatllm.services.WorldStateService;
import com.eonmux.chatllm.testing.SqlTestSupport;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.UniEmitter;
import java.util.function.Consumer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JourneyServiceTest {
    JourneyService service;
    EventBus bus;
    String conversation = UUID.randomUUID().toString(), map = UUID.randomUUID().toString(), user = UUID.randomUUID().toString();
    MapLocation from, to;
    AtomicReference<Consumer<Long>> timer = new AtomicReference<>();
    AtomicReference<UniEmitter<? super Message<JsonObject>>> response = new AtomicReference<>();

    @BeforeEach void setup() {
        service = new JourneyService();
        service.mapService = mock(MapService.class);
        service.travelService = mock(TravelService.class);
        service.battleService = mock(com.eonmux.chatllm.services.BattleService.class);
        service.encounterDecision = mock(com.eonmux.chatllm.agents.SceneDecisionAgent.class);
        when(service.encounterDecision.run(any())).thenReturn(Uni.createFrom().item(com.eonmux.chatllm.agents.AgentResult.builder()
            .status("success").structuredOutput(new JsonObject().put("encounter",false)).build()));
        when(service.battleService.requirePeace(anyString())).thenReturn(Uni.createFrom().voidItem());
        when(service.battleService.getActiveBattle(anyString())).thenReturn(Uni.createFrom().nullItem());
        service.storyMessageService = mock(com.eonmux.chatllm.services.StoryMessageService.class);
        when(service.storyMessageService.narrate(anyString(), anyString(), anyString(), any())).thenReturn(Uni.createFrom().voidItem());
        service.stageService = mock(StageService.class);
        service.travelSceneService = mock(TravelSceneService.class);
        service.groupChatService = mock(com.eonmux.chatllm.services.GroupChatService.class);
        service.worldStateService = mock(WorldStateService.class);
        service.vertx = mock(Vertx.class);
        bus = mock(EventBus.class);
        when(service.vertx.eventBus()).thenReturn(bus);
        when(service.vertx.setTimer(anyLong(), any())).thenAnswer(invocation -> {
            timer.set(invocation.getArgument(1));
            return 1L;
        });
        when(bus.<JsonObject>request(eq("llm.agent.completion"), any(), any(DeliveryOptions.class)))
            .thenReturn(Uni.createFrom().<Message<JsonObject>>emitter(response::set));
        service.mysqlPool = SqlTestSupport.pool((sql, parameters) -> SqlTestSupport.rows());
        from = MapLocation.builder().mapId(UUID.fromString(map)).locationId(UUID.randomUUID()).name("Start").x(0f).y(0f).z(0f).build();
        to = MapLocation.builder().mapId(UUID.fromString(map)).locationId(UUID.randomUUID()).name("End").x(100f).y(0f).z(0f).build();
        when(service.mapService.getEntityPositions(map)).thenReturn(Uni.createFrom().item(List.of(
            MapCharacterPosition.builder().id(UUID.randomUUID()).mapId(UUID.fromString(map)).userId(UUID.fromString(user))
                .locationId(from.getLocationId()).x(0f).y(0f).z(0f).build())));
        when(service.mapService.getLocationById(from.getLocationId().toString())).thenReturn(Uni.createFrom().item(from));
        when(service.mapService.getLocationById(to.getLocationId().toString())).thenReturn(Uni.createFrom().item(to));
        when(service.mapService.getTerrainData(map)).thenReturn(Uni.createFrom().nullItem());
        when(service.mapService.getLocations(map)).thenReturn(Uni.createFrom().item(List.of(from, to)));
        when(service.travelService.calculateTravel(map, from.getLocationId().toString(), to.getLocationId().toString()))
            .thenReturn(Uni.createFrom().item(TravelResult.builder().timePeriodsRequired(3).build()));
        when(service.worldStateService.updateWorldState(eq(conversation), any())).thenReturn(Uni.createFrom().item(WorldState.builder().build()));
        when(service.worldStateService.getOrCreateWorldState(conversation)).thenReturn(Uni.createFrom().item(WorldState.builder().build()));
    }

    void startAndWaitForNarration() {
        service.startJourney(conversation, map, user, to.getLocationId().toString(), List.of()).await().indefinitely();
        timer.get().accept(1L);
        assertNotNull(response.get());
    }

    @Test void cancelledJourneyDoesNotResumeAfterDelayedNarration() {
        startAndWaitForNarration();
        service.cancelJourney(conversation).await().indefinitely();
        clearInvocations(service.mapService);
        Message<JsonObject> reply = mock(Message.class);
        when(reply.body()).thenReturn(new JsonObject().put("content", "The party arrived. [ARRIVED]"));
        response.get().complete(reply);
        assertNull(service.getActiveJourney(conversation).await().indefinitely());
        verify(service.mapService, never()).updateUserPosition(anyString(), anyString(), any(), anyFloat(), anyFloat(), anyFloat());
        verify(service.vertx, times(1)).setTimer(anyLong(), any());
    }

    @Test void arrivalTagsCannotSkipWaypointsAndCompletedStepsRemainAvailable() {
        when(service.mapService.updateUserPosition(anyString(), anyString(), any(), anyFloat(), anyFloat(), anyFloat()))
            .thenReturn(Uni.createFrom().voidItem());
        when(service.mapService.discoverNearbyLocations(anyString(), anyFloat(), anyFloat(), anyFloat(), anyFloat()))
            .thenReturn(Uni.createFrom().item(List.of()));
        when(service.worldStateService.progressTimeOfDay(conversation)).thenReturn(Uni.createFrom().voidItem());
        startAndWaitForNarration();
        Message<JsonObject> reply = mock(Message.class);
        when(reply.body()).thenReturn(new JsonObject().put("choices", new io.vertx.core.json.JsonArray().add(new JsonObject().put("message", new JsonObject().put("content", "[ARRIVED]")))));
        response.get().complete(reply);
        var journey = service.getActiveJourney(conversation).await().indefinitely();
        assertNotNull(journey);
        assertEquals(1, journey.getCurrentStep());
        assertEquals(1, journey.getStepResults().size());
        assertFalse(journey.getStepResults().getFirst().isJourneyComplete());
        assertTrue(journey.getStepResults().getFirst().getX() > 0f);
        verify(service.vertx, times(2)).setTimer(anyLong(), any());
    }

    @Test void roundedStepDurationsAddUpToTheOriginalTravelTime() throws Exception {
        when(service.worldStateService.progressTimeOfDay(conversation)).thenReturn(Uni.createFrom().voidItem());
        var advance = JourneyService.class.getDeclaredMethod("advanceTimeForStep", com.eonmux.chatllm.map.models.JourneyState.class);
        advance.setAccessible(true);
        for (int step = 0; step < 30; step++) {
            var state = com.eonmux.chatllm.map.models.JourneyState.builder()
                .conversationId(conversation).totalSteps(30).timePeriodsRequired(31).currentStep(step).build();
            ((Uni<?>) advance.invoke(service, state)).await().indefinitely();
        }
        verify(service.worldStateService, times(31)).progressTimeOfDay(conversation);
    }

    @Test void persistenceFailureReleasesJourneySlot() {
        service.mysqlPool = SqlTestSupport.pool((sql, parameters) -> { throw new IllegalStateException("Database unavailable"); });
        assertThrows(IllegalStateException.class, () -> service.startJourney(conversation, map, user,
            to.getLocationId().toString(), List.of()).await().indefinitely());
        service.mysqlPool = SqlTestSupport.pool((sql, parameters) -> SqlTestSupport.rows());
        assertDoesNotThrow(() -> service.startJourney(conversation, map, user,
            to.getLocationId().toString(), List.of()).await().indefinitely());
    }
    @Test void proseCombatTagsHaveNoAuthorityAndOnlyRolledEncountersRequestAJudgment() {
        var state=JourneyState.builder().conversationId(conversation).userId(user).build();
        var waypoint=JourneyWaypoint.builder().eventType("none").build();
        String text="A merchant offered directions. <combat>{\"enemyName\":\"Road guard\",\"enemyHp\":24}</combat> [CONTINUE]";
        assertNull(service.judgeEncounter(state,waypoint,text).await().indefinitely());
        verifyNoInteractions(service.encounterDecision);
        waypoint=JourneyWaypoint.builder().eventType("encounter").build();
        assertNull(service.judgeEncounter(state,waypoint,text).await().indefinitely());
        verify(service.encounterDecision).run(argThat(context -> !context.getTriggerContent().contains("<combat>")
            && !context.getTriggerContent().contains("[CONTINUE]") && context.getTriggerContent().contains("merchant offered directions")));
    }

    @Test void aFightStartedDuringNarrationPausesWithoutMovingTheParty() {
        startAndWaitForNarration();
        var battle = com.eonmux.chatllm.models.Battle.builder().battleId(UUID.randomUUID()).build();
        when(service.battleService.getActiveBattle(conversation)).thenReturn(Uni.createFrom().item(battle));
        Message<JsonObject> reply = mock(Message.class);
        when(reply.body()).thenReturn(new JsonObject().put("choices", new io.vertx.core.json.JsonArray()
            .add(new JsonObject().put("message", new JsonObject().put("content", "The party continued. [CONTINUE]")))));
        response.get().complete(reply);
        var journey = service.getActiveJourney(conversation).await().indefinitely();
        assertEquals(com.eonmux.chatllm.map.models.JourneyStatus.PAUSED, journey.getStatus());
        assertEquals(0, journey.getCurrentStep());
        assertEquals(battle.getBattleId().toString(), journey.getStepResults().getFirst().getBattleId());
        verify(service.mapService, never()).updateUserPosition(anyString(), anyString(), any(), anyFloat(), anyFloat(), anyFloat());
        verify(service.vertx, times(1)).setTimer(anyLong(), any());
    }

    @Test void roadsideOriginKeepsActualCoordinatesWithoutSnappingToANode() {
        var position = MapCharacterPosition.builder().mapId(UUID.fromString(map)).x(35f).y(10f).z(4f).build();
        var origin = JourneyService.roadsideOrigin(position, List.of(from, to));
        assertEquals(35f, origin.getX()); assertEquals(10f, origin.getY()); assertEquals(4f, origin.getZ());
        assertEquals(from.getLocationId(), origin.getLocationId()); // persistence anchor only
        assertEquals("the roadside near Start", origin.getName());
    }

    @Test void aPeacefulEncounterPausesAndResumesTheRemainingRouteWithoutCombat() throws Exception {
        when(service.mapService.updateUserPosition(anyString(), anyString(), any(), anyFloat(), anyFloat(), anyFloat()))
            .thenReturn(Uni.createFrom().voidItem());
        when(service.mapService.discoverNearbyLocations(anyString(), anyFloat(), anyFloat(), anyFloat(), anyFloat()))
            .thenReturn(Uni.createFrom().item(List.of()));
        when(service.worldStateService.progressTimeOfDay(conversation)).thenReturn(Uni.createFrom().voidItem());
        when(service.battleService.canManage(conversation, user)).thenReturn(Uni.createFrom().item(false));
        service.mysqlPool = SqlTestSupport.pool((sql, parameters) -> SqlTestSupport.affected(1));
        var journey = service.startJourney(conversation, map, user, to.getLocationId().toString(), List.of()).await().indefinitely();
        journey = service.getActiveJourney(conversation).await().indefinitely();
        var waypoint = journey.getWaypoints().getFirst().toBuilder().eventType("encounter").build();
        var process = JourneyService.class.getDeclaredMethod("processNarratedStep", com.eonmux.chatllm.map.models.JourneyState.class,
            com.eonmux.chatllm.map.models.JourneyWaypoint.class, String.class);
        process.setAccessible(true);
        ((Uni<?>) process.invoke(service, journey, waypoint, "A traveler asked for directions. [CONTINUE]")).await().indefinitely();
        var paused = service.getActiveJourney(conversation).await().indefinitely();
        assertEquals(com.eonmux.chatllm.map.models.JourneyStatus.PAUSED, paused.getStatus());
        assertEquals(1, paused.getCurrentStep()); assertTrue(paused.getStepResults().getFirst().isJourneyPaused());
        verify(service.battleService, never()).initiateBattle(any(), any(), any(), any(), any(), any());
        verify(service.vertx, times(1)).setTimer(anyLong(), any());
        var resumed = service.resumeJourney(conversation, user).await().indefinitely();
        assertEquals(com.eonmux.chatllm.map.models.JourneyStatus.TRAVELING, resumed.getStatus());
        assertEquals(1, resumed.getCurrentStep()); assertFalse(resumed.getStepResults().getFirst().isJourneyPaused());
        verify(service.vertx, times(2)).setTimer(anyLong(), any());
    }

}
