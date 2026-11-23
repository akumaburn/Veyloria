package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import io.vertx.core.json.JsonObject;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MapLocation {
    private UUID locationId;
    private UUID mapId;
    private String name;
    private String description;
    private Float x;
    private Float y;
    private Float z;
    private String icon;
    private String locationType;
    private UUID linkedSceneId;
    @Builder.Default
    private Boolean isDiscovered = false;
    @Builder.Default
    private Float movementCost = 1.0f;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject metadata;
    private String createdAt;
}
