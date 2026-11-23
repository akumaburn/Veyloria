package com.eonmux.chatllm.map.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Per-step result published to the frontend via subscription.
 * Each step of the journey produces one of these.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class JourneyStepResult {
    private String journeyId;
    private int stepNumber;
    private int totalSteps;
    private String narration;
    private String eventType;
    private float x;
    private float y;
    private float z;
    private String biome;
    private String timeOfDay;
    private int dayNumber;
    private boolean journeyComplete;
    private boolean journeyInterrupted;
    private boolean journeyPaused;
    private String battleId;
    private String interruptReason;
    @Builder.Default
    private List<String> discoveredLocationIds = List.of();
}
