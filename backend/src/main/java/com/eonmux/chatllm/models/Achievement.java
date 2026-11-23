package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import io.vertx.core.json.JsonObject;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Achievement {
    private String achievementId;
    private String name;
    private String description;
    private String icon;
    private String category;
    @Builder.Default
    private String tier = "bronze";
    @Builder.Default
    private Integer xpReward = 0;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject triggerCondition;
    @Builder.Default
    private Boolean isHidden = false;
    @Builder.Default
    private Boolean isBuiltIn = true;
    private String createdAt;
}
