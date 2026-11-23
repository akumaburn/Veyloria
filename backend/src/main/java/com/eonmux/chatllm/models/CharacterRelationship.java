package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import io.vertx.core.json.JsonObject;

import java.util.UUID;

import com.eonmux.chatllm.utils.Time4JHelper;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CharacterRelationship {
    private UUID relationshipId;
    private UUID conversationId;
    private UUID fromCharacterId;
    private UUID toCharacterId;
    private String relationshipType;
    @Builder.Default
    private Double intensity = 0.5;
    private String description;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject history;
    @Builder.Default
    private Boolean isReciprocal = false;
    private String createdAt;
    private String updatedAt;

    public void modifyIntensity(double delta) {
        if (intensity == null) intensity = 0.5;
        intensity = Math.max(0.0, Math.min(1.0, intensity + delta));
        this.updatedAt = Time4JHelper.nowUTCString();
    }
}
