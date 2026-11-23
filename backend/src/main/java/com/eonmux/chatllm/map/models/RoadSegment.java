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
public class RoadSegment {
    private String roadId;
    private String fromLocationId;
    private String toLocationId;
    private List<float[]> waypoints;
    private String roadType;
}
