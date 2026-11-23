package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DistanceResult {
    private float distance;
    private float fromX;
    private float fromY;
    private float fromZ;
    private float toX;
    private float toY;
    private float toZ;
}
