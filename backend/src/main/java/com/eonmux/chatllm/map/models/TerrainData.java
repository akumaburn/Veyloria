package com.eonmux.chatllm.map.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TerrainData {
    private int width;
    private int height;
    @org.eclipse.microprofile.graphql.Ignore
    private float[] heightmap;
    @org.eclipse.microprofile.graphql.Ignore
    private int[] biomeGrid;
    private float waterLevel;
    @org.eclipse.microprofile.graphql.Ignore
    private float[] slopeGrid;
    @org.eclipse.microprofile.graphql.Ignore
    private long seed;
    private String mapScale;
    private String theme;
}
