package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import io.vertx.core.json.JsonArray;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeEntry {
    private UUID entryId;
    private UUID conversationId;
    private String summary;
    @org.eclipse.microprofile.graphql.Ignore
    private Object visibility; // "ALL" or JsonArray of character IDs
    private UUID sceneId;
    @Builder.Default
    private Double importance = 0.5;
    private UUID sourceMessageId;
    private Integer dayNumber;
    @Builder.Default
    private List<String> tags = new ArrayList<>();
    @Builder.Default
    private Boolean isActive = true;
    private Integer sourceHighlighterVersion;
    private String summaryHash;
    private String createdAt;

    public boolean isVisibleTo(String characterId) {
        if (visibility == null || "ALL".equals(visibility)) return true;
        if (visibility instanceof JsonArray arr) {
            return arr.contains(characterId);
        }
        return false;
    }

    public void setTagsFromString(String tagsString) {
        if (tagsString == null || tagsString.isEmpty()) {
            this.tags = new ArrayList<>();
        } else {
            this.tags = new ArrayList<>(List.of(tagsString.split(",")));
        }
    }
}
