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
public class StoryRating {
    private UUID id;
    private UUID storyId;
    private UUID userId;
    private Integer rating;
    private String review;
    private String createdAt;
}
