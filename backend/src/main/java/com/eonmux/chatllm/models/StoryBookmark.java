package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryBookmark {
    private UUID bookmarkId;
    private UUID conversationId;
    private UUID userId;
    private UUID messageId;
    private String label;
    private String description;
    @org.eclipse.microprofile.graphql.Ignore
    private JsonObject worldStateSnapshot;
    @Builder.Default
    private List<String> tags = new ArrayList<>();
    private String createdAt;

    public void setTagsFromString(String tagsString) {
        if (tagsString == null || tagsString.isEmpty()) {
            this.tags = new ArrayList<>();
        } else {
            this.tags = List.of(tagsString.split(","));
        }
    }

    public String getTagsAsString() {
        if (tags == null || tags.isEmpty()) return "";
        return String.join(",", tags);
    }
}
