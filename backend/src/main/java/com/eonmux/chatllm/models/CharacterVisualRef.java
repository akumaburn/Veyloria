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
public class CharacterVisualRef {
    private UUID refId;
    private UUID characterId;
    private String referenceType;
    private String referencePath;
    private String promptFragment;
    private String negativeFragment;
    @Builder.Default
    private Double weight = 1.0;
    @Builder.Default
    private Boolean isActive = true;
    private String createdAt;
}
