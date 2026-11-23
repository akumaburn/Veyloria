package com.eonmux.chatllm.map.models;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

import com.eonmux.chatllm.utils.Time4JHelper;

/**
 * Full state of an active or completed travel journey.
 * Stored in-memory (ConcurrentHashMap) during travel and persisted to DB for crash recovery.
 */
@Data
@Builder(toBuilder = true)
public class JourneyState {
    private final String journeyId;
    private final String conversationId;
    private final String mapId;
    private final String userId;
    private final List<String> characterIds;
    private final String fromLocationId;
    private final String fromLocationName;
    private final String toLocationId;
    private final String toLocationName;
    @org.eclipse.microprofile.graphql.Ignore
    private final List<JourneyWaypoint> waypoints;
    private final int totalSteps;
    private final float distance;
    @Builder.Default
    private final List<JourneyStepResult> stepResults = new ArrayList<>();
    private final float effectiveCost;
    private final int timePeriodsRequired;
    private final boolean usesRoad;

    // Mutable step state — updated via toBuilder() for immutability
    @Builder.Default
    private final int currentStep = 0;
    @Builder.Default
    private final JourneyStatus status = JourneyStatus.TRAVELING;
    @Builder.Default
    private final String interruptReason = null;

    // Vert.x timer ID for the step scheduler (not persisted)
    @Builder.Default
    @org.eclipse.microprofile.graphql.Ignore
    private final long timerId = -1;

    @Builder.Default
    private final String createdAt = Time4JHelper.nowUTCString();
    @Builder.Default
    private final String updatedAt = Time4JHelper.nowUTCString();
}
