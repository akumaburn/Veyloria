package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorldMap {
    private UUID mapId;
    private UUID conversationId;
    private String name;
    @Builder.Default
    private String mapType = "world";
    @Builder.Default
    private String mapScale = "region";
    private UUID imageId;
    private UUID parentMapId;
    @Builder.Default
    private Integer gridWidth = 20;
    @Builder.Default
    private Integer gridHeight = 20;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject metadata;
    private String createdAt;

    @Builder.Default
    @org.eclipse.microprofile.graphql.Ignore
    private List<MapLocation> locations = new ArrayList<>();
}
