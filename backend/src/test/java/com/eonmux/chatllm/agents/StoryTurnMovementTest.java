package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.map.*;
import com.eonmux.chatllm.models.*;
import com.eonmux.chatllm.services.WorldStateService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.eonmux.chatllm.testing.SqlTestSupport.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class StoryTurnMovementTest {
    Vertx vertx;
    WorldStateService world=mock(WorldStateService.class);
    StageService stageService=mock(StageService.class);
    StageContext before,after;
    AgentPipeline pipeline;
    AgentContext context;
    @BeforeEach void setup() {
        vertx=Vertx.vertx();
        var square=LocalArea.builder().localAreaId(UUID.randomUUID()).name("Market Square").build();
        var tavern=LocalArea.builder().localAreaId(UUID.randomUUID()).name("Tavern").build();
        var node=MapLocation.builder().locationId(UUID.randomUUID()).name("Crystalport").build();
        var link=LocalAreaConnection.builder().fromAreaId(square.getLocalAreaId()).toAreaId(tavern.getLocalAreaId()).label("tavern lane").build();
        before=StageContext.builder().node(node).area(square).areas(List.of(square,tavern)).connections(List.of(link)).build();
        after=StageContext.builder().node(node).area(tavern).build();
        pipeline=new AgentPipeline(vertx,null,mock(AppConfig.class),null,null,world,null,null,null,null,null,null,null,stageService,null);
        context=AgentContext.builder().conversationId("chat").triggerContent("Let's move to the tavern for now then.").stage(before).build();
        when(world.getOrCreateWorldState("chat")).thenReturn(Uni.createFrom().item(WorldState.builder().conversationId(UUID.randomUUID()).build()));
        when(world.updateWorldState(eq("chat"),any())).thenReturn(Uni.createFrom().nullItem());
    }
    @AfterEach void close() { vertx.closeAndAwait(); }
    @Test void resolvedMoveUsesTheCanonicalRoomAndActualPassage() {
        when(stageService.move("chat",null,"Tavern")).thenReturn(Uni.createFrom().item(StageMovementResult.builder().kind(StageMovementPolicy.Kind.AREA).stage(after).build()));
        pipeline.applyAccountantSceneState(context,new JsonObject().put("worldStateChanges",new JsonObject().put("currentArea","Tavern")))
            .await().indefinitely();
        assertEquals("from the Market Square to the Tavern via tavern lane",context.getStageMoveDescription());
        verify(world).updateWorldState(eq("chat"),argThat(json -> "The party arrived in the Tavern at Crystalport.".equals(json.getString("currentScene"))));
    }
    @Test void rejectedMovesCannotLeaveBehindAPhantomArrivalOrAdvanceTravelTime() {
        when(stageService.move("chat",null,"Cellar")).thenReturn(Uni.createFrom().item(StageMovementResult.builder().kind(StageMovementPolicy.Kind.REJECTED).reason("No connection").stage(before).build()));
        pipeline.applyAccountantSceneState(context,new JsonObject().put("worldStateChanges",new JsonObject().put("currentArea","Cellar").put("currentScene","The party arrived in the cellar.").put("timeAdvance",true)))
            .await().indefinitely();
        assertNull(context.getStageMoveDescription());
        assertEquals("No connection",context.getStageMoveFailure());
        verify(world,never()).updateWorldState(anyString(),any());
        verify(world,never()).progressTimeOfDay(anyString());
    }
    @Test void postDialogueWorkIsFencedByThePlayerAndLastCharacterMessage() {
        var queries=new ArrayList<String>();
        var db=pool((sql,params)-> {
            queries.add(sql);
            assertEquals("player-message",params.getString(1));
            assertEquals("last-reply",params.getString(2));
            return rows();
        });
        var guarded=new AgentPipeline(null,db,mock(AppConfig.class),null,null,null,null,null,null,null,null,null,null,null,null);
        context.setPostDialogue(true);context.setTriggerMessageId("player-message");context.setRoundLastMessageId("last-reply");
        assertThrows(IllegalStateException.class,()->guarded.requireCurrentRound(context).await().indefinitely());
        assertTrue(queries.getFirst().contains("c.currentSpeakerId IS NULL"));
    }
    @Test void dungeonDivingCheckInnCommandRepairsStaleAreaAndRequiresArrivalNarration() {
        before = PlayerMovementIntentTest.stage();
        var inn = before.getAreas().stream().filter(area -> "Inn".equals(area.getName())).findFirst().orElseThrow();
        before.setConnections(List.of(LocalAreaConnection.builder().fromAreaId(before.getArea().getLocalAreaId())
            .toAreaId(inn.getLocalAreaId()).label("inn lane").build()));
        after = StageContext.builder().node(before.getNode()).area(inn).build();
        context.setStage(before);
        context.setPostDialogue(true);
        context.setTriggerContent("Let's check the Inn first, odds are the bartender can give us the rundown.");
        when(stageService.move("chat", null, "Inn")).thenReturn(Uni.createFrom().item(StageMovementResult.builder()
            .kind(StageMovementPolicy.Kind.AREA).stage(after).build()));
        // Exact Accountant output from the failed conversation (no database mutation).
        pipeline.applyAccountantSceneState(context, new JsonObject().put("worldStateChanges", new JsonObject()
            .put("currentArea", "Village Green")
            .put("currentScene", "The party walks northwest through the narrow pathways of the Nekorian Enclave toward the Inn, following Elara's lead.")))
            .await().indefinitely();
        verify(stageService).move("chat", null, "Inn");
        verify(world).updateWorldState(eq("chat"), argThat(json ->
            "The party arrived in the Inn at Nekorian Enclave.".equals(json.getString("currentScene"))));
        assertEquals("from the Village Green to the Inn via inn lane", context.getStageMoveDescription());
        context.setStage(after); // The pipeline refreshes the authoritative stage before narration.
        var narrator = new NarratorAgent(null, null, null, null) {
            @Override protected Uni<JsonObject> callLlmProse(String system, String prompt, float temperature, int tokens, String model) {
                return Uni.createFrom().item(new JsonObject().put("choices", new io.vertx.core.json.JsonArray().add(
                    new JsonObject().put("message", new JsonObject().put("content", "[NO_NARRATION]")))));
            }
        };
        var narration = narrator.executeWithFeedback(context, null, "test").await().indefinitely();
        assertTrue(narration.getContent().contains("Village Green to the Inn via inn lane"));
    }
    @Test void explicitTravelIsRecoveredEvenWhenAccountantOmitsWorldStateChanges() {
        context.setPostDialogue(true);
        when(stageService.move("chat", null, "Tavern")).thenReturn(Uni.createFrom().item(StageMovementResult.builder()
            .kind(StageMovementPolicy.Kind.AREA).stage(after).build()));
        pipeline.applyAccountantSceneState(context, new JsonObject()).await().indefinitely();
        verify(stageService).move("chat", null, "Tavern");
        assertNotNull(context.getStageMoveDescription());
    }
    @Test void establishedBlockerPreventsTheTravelFallback() {
        context.setPostDialogue(true);
        pipeline.applyAccountantSceneState(context, new JsonObject().put("worldStateChanges", new JsonObject()
            .put("currentArea", "Tavern").put("currentScene", "The party arrives in the Tavern.")
            .put("movementBlockedReason", "The established gate is locked.").put("timeAdvance", true)))
            .await().indefinitely();
        verifyNoInteractions(stageService);
        verify(world, never()).updateWorldState(anyString(), any());
        verify(world, never()).progressTimeOfDay(anyString());
        assertNull(context.getStageMoveDescription());
        assertEquals("The established gate is locked.", context.getStageMoveFailure());
    }
    @Test void recoveredPlayerTravelStillUsesTheNormalRouteRejection() {
        context.setPostDialogue(true);
        when(stageService.move("chat", null, "Tavern")).thenReturn(Uni.createFrom().item(StageMovementResult.builder()
            .kind(StageMovementPolicy.Kind.REJECTED).stage(before).reason("Route is disconnected").build()));
        pipeline.applyAccountantSceneState(context, new JsonObject()).await().indefinitely();
        assertNull(context.getStageMoveDescription());
        assertEquals("Route is disconnected", context.getStageMoveFailure());
        verify(world, never()).updateWorldState(anyString(), any());
    }

    @Test void aRepeatedSourceAreaCannotSuppressAnActualRecordedThresholdCrossing() {
        context.setTriggerContent("I follow Elara.");
        when(stageService.move("chat", null, "Tavern")).thenReturn(Uni.createFrom().item(StageMovementResult.builder()
            .kind(StageMovementPolicy.Kind.AREA).stage(after).build()));
        pipeline.applyAccountantSceneState(context, new JsonObject().put("worldStateChanges", new JsonObject()
            .put("currentArea", "Market Square").put("currentScene", "The party enters the Tavern.")))
            .await().indefinitely();
        verify(stageService).move("chat", null, "Tavern");
        assertNotNull(context.getStageMoveDescription());
    }

}
