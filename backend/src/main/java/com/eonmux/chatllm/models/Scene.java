package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.eonmux.chatllm.utils.Time4JHelper;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Scene {
    private UUID sceneId;
    private UUID conversationId;
    private String name;
    private String location;
    private String description;
    @Builder.Default
    private Boolean isActive = true;
    private UUID parentSceneId;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject metadata;
    private String createdAt;
    private String endedAt;

    @Builder.Default
    private List<Character> characters = new ArrayList<>();

    public void end() {
        this.isActive = false;
        this.endedAt = Time4JHelper.nowUTCString();
    }
}
