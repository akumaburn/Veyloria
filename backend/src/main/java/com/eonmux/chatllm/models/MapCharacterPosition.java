package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MapCharacterPosition {
    private UUID id;
    private UUID mapId;
    private UUID characterId;
    private UUID userId;
    private UUID locationId;
    private Float x;
    private Float y;
    private Float z;
    private String updatedAt;
}
