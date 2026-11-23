package com.eonmux.chatllm.map.models;

import com.eonmux.chatllm.map.generation.CulturalRegion;
import com.eonmux.chatllm.models.MapLocation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationResult {
    private TerrainData terrain;
    private List<MapLocation> locations;
    private List<RoadSegment> roads;
    private List<CulturalRegion> culturalRegions;
    private String startingLocationId;
}
