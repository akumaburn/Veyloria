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
@Builder
public class MapItemPosition {
    private UUID id;
    private UUID mapId;
    private UUID conversationId;
    private String itemName;
    private String itemDescription;
    private String itemType;
    private Float x;
    private Float y;
    private Float z;
    private UUID linkedLocationId;
    @Builder.Default
    private Boolean isDiscovered = false;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject metadata;
    private String createdAt;
    private String updatedAt;
}
