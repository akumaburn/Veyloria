package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import io.vertx.core.json.JsonObject;

import java.util.UUID;

/**
 * One room, chamber, street, or clearing inside a world-map location.
 *
 * <p>A world-map location is a node at map scale. A local area is where the
 * scene actually happens inside that node. Characters can move between local
 * areas through recorded connections, never by naming a new place.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocalArea {
    private UUID localAreaId;
    private UUID mapId;
    private UUID locationId;
    private String name;
    private String description;
    private String areaType;
    @Builder.Default
    private Float x = 0f;
    @Builder.Default
    private Float y = 0f;
    @Builder.Default
    private Boolean isGateway = false;
    @Builder.Default
    private Boolean isEntry = false;
    @Builder.Default
    private Integer sortOrder = 0;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject metadata;
    private String createdAt;
}
