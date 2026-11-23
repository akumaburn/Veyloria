package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The interior layout of one world-map location, together with the area the
 * party currently occupies.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocalMap {
    private UUID mapId;
    private UUID locationId;
    private String locationName;
    private String locationType;
    private String currentAreaId;
    private String currentAreaName;
    /** Areas of this node the party has already been in. Drives the map's fog of war. */
    @Builder.Default
    private List<String> visitedAreaIds = new ArrayList<>();
    @Builder.Default
    private List<LocalArea> areas = new ArrayList<>();
    @Builder.Default
    private List<LocalAreaConnection> connections = new ArrayList<>();
}
