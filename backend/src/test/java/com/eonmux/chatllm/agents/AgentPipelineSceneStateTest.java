package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.map.StageContext;
import com.eonmux.chatllm.models.LocalArea;
import com.eonmux.chatllm.models.LocalAreaConnection;
import com.eonmux.chatllm.models.MapLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPipelineSceneStateTest {

    private static final UUID SQUARE_ID = UUID.randomUUID();
    private static final UUID WAREHOUSE_ID = UUID.randomUUID();
    private static final UUID ALLEY_ID = UUID.randomUUID();

    @Test void aRecordedMoveIsRecognized() {
        assertTrue(AgentPipeline.sceneImpliesMove(
                "The dive team moves through the warehouse lane."));
        assertTrue(AgentPipeline.sceneImpliesMove(
                "The party enters the warehouse."));
        assertTrue(AgentPipeline.sceneImpliesMove(
                "The party headed into the alley."));
    }

    @Test void planningAMoveIsNotAMove() {
        assertFalse(AgentPipeline.sceneImpliesMove(
                "The party prepares to move through the warehouse lane."));
        assertFalse(AgentPipeline.sceneImpliesMove(
                "The party heads toward the warehouse."));
        assertFalse(AgentPipeline.sceneImpliesMove(
                "The dive team holds position at Amir's order."));
    }

    @Test void theAreaIsRecoveredFromTheSceneText() {
        StageContext stage = stage();

        assertEquals("Warehouse", AgentPipeline.inferAreaFromScene(
                "The party moves through the warehouse lane.", stage));
    }

    @Test void twoNamedAreasAreAmbiguous() {
        assertNull(AgentPipeline.inferAreaFromScene(
                "The party moves through the alley and into the warehouse.", stage()));
    }

    @Test void theCurrentAreaIsNotAMove() {
        assertNull(AgentPipeline.inferAreaFromScene(
                "The party moves through the market square.", stage()));
    }

    @Test void aSceneWithNoStageCannotMove() {
        assertNull(AgentPipeline.inferAreaFromScene(
                "The party moves through the warehouse lane.", StageContext.empty()));
    }

    @Test void theSessionStartTurnIsAnOpening() {
        assertTrue(AgentPipeline.isSessionStart(AgentContext.builder()
                .triggerContent("[SESSION_START] This is the beginning of a new roleplay session.")
                .build()));
        assertFalse(AgentPipeline.isSessionStart(AgentContext.builder()
                .triggerContent("We wait by the door.")
                .build()));
        assertFalse(AgentPipeline.isSessionStart(AgentContext.builder().build()));
    }

    private static StageContext stage() {
        LocalArea square = LocalArea.builder()
                .localAreaId(SQUARE_ID).name("Market Square").build();
        LocalArea warehouse = LocalArea.builder()
                .localAreaId(WAREHOUSE_ID).name("Warehouse").build();
        LocalArea alley = LocalArea.builder()
                .localAreaId(ALLEY_ID).name("Alley").build();

        return StageContext.builder()
                .node(new MapLocation())
                .area(square)
                .areas(List.of(square, warehouse, alley))
                .connections(List.of(
                        LocalAreaConnection.builder()
                                .fromAreaId(SQUARE_ID).toAreaId(WAREHOUSE_ID).build(),
                        LocalAreaConnection.builder()
                                .fromAreaId(SQUARE_ID).toAreaId(ALLEY_ID).build()))
                .build();
    }
}
