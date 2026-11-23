package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublishedStory {
    private UUID storyId;
    private UUID conversationId;
    private UUID userId;
    private String title;
    private String synopsis;
    private UUID coverImageId;
    private String genre;
    @Builder.Default
    private List<String> tags = new ArrayList<>();
    @Builder.Default
    private Integer wordCount = 0;
    @Builder.Default
    private Double rating = 0.0;
    @Builder.Default
    private Integer ratingCount = 0;
    @Builder.Default
    private Integer viewCount = 0;
    @Builder.Default
    private Integer likeCount = 0;
    @Builder.Default
    private Boolean isPublic = true;
    @Builder.Default
    private Boolean isFeatured = false;
    private String publishedAt;
    private String updatedAt;

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
