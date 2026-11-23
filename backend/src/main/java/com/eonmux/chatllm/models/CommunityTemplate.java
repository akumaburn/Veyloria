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
public class CommunityTemplate {
    private UUID submissionId;
    private String templateId;
    private UUID userId;
    @Builder.Default
    private String status = "pending";
    @Builder.Default
    private Integer downloads = 0;
    @Builder.Default
    private Double rating = 0.0;
    @Builder.Default
    private Integer ratingCount = 0;
    private String submittedAt;
    private String reviewedAt;
    private UUID reviewedBy;

    private StoryTemplate template;
}
