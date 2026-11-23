package com.eonmux.chatllm.map.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CulturalRegion {
    private String culture;
    private String name;
    private int centerX;
    private int centerY;
    private float radius;
    private float worldX;
    private float worldY;
}
