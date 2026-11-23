package com.eonmux.chatllm.map.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationConfig {
    private long seed;
    private String mapScale;
    private int gridResolution;
    private float waterLevel;
    private int locationCount;
    private String theme;
    /**
     * World-map location types that fit the story's opening scene, most
     * specific first. Empty keeps the normal settlement preference.
     */
    private List<String> preferredStartingTypes;
}
