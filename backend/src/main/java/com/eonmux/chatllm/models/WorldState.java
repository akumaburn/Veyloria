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
public class WorldState {
    private UUID stateId;
    private UUID conversationId;
    private String templateId;
    private String currentScene;
    private String currentLocation;
    private UUID currentLocationId;
    private String currentArea;
    private UUID currentAreaId;
    private String timeOfDay;
    @Builder.Default
    private Integer dayNumber = 1;
    private String weather;
    private String mood;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject globalFlags;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject locations;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject activeEffects;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject narratorNotes;
    private String createdAt;
    private String updatedAt;

    public void advanceTime(String newTimeOfDay, Integer dayIncrement) {
        this.timeOfDay = newTimeOfDay;
        if (dayIncrement != null && dayIncrement > 0) {
            this.dayNumber = (this.dayNumber != null ? this.dayNumber : 1) + dayIncrement;
        }
        this.updatedAt = Time4JHelper.nowUTCString();
    }

    public void setFlag(String key, Object value) {
        if (this.globalFlags == null) this.globalFlags = new JsonObject();
        this.globalFlags.put(key, value);
    }

    public Object getFlag(String key) {
        return this.globalFlags != null ? this.globalFlags.getValue(key) : null;
    }
}
