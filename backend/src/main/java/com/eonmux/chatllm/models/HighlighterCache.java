package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import io.vertx.core.json.JsonArray;

import java.util.UUID;

import com.eonmux.chatllm.utils.Time4JHelper;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HighlighterCache {
    private UUID cacheId;
    private UUID conversationId;
    private JsonArray entries;
    private UUID lastMessageId;
    private Integer messageCount;
    @Builder.Default
    private Integer version = 1;
    private String createdAt;
    private String updatedAt;

    public void incrementVersion() {
        this.version = (this.version != null ? this.version : 0) + 1;
        this.updatedAt = Time4JHelper.nowUTCString();
    }
}
