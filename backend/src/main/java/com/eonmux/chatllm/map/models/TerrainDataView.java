package com.eonmux.chatllm.map.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class TerrainDataView {
    private int width;
    private int height;
    private String heightmap;
    private String biomeGrid;
    private float waterLevel;
    private String seed;
    private String mapScale;
    private String theme;
}
