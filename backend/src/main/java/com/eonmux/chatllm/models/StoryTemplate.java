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
public class StoryTemplate {
    private String templateId;
    private String name;
    private String genre;
    private String description;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject defaultWorldState;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject suggestedCharacterArchetypes;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject rules;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject moodPresets;
    @Builder.Default
    private Boolean isBuiltIn = false;
    @Builder.Default
    private Boolean isPublic = true;
    private UUID ownerId;
    private String createdAt;
    private String updatedAt;
}
