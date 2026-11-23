package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.UUID;

import com.eonmux.chatllm.utils.Time4JHelper;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAchievement {
    private UUID id;
    private UUID userId;
    private String achievementId;
    @Builder.Default
    private Double progress = 0.0;
    @Builder.Default
    private Boolean isComplete = false;
    private String completedAt;
    private UUID conversationId;
    private String createdAt;
    private String updatedAt;

    private Achievement achievement;

    public void updateProgress(double newProgress) {
        this.progress = Math.max(0.0, Math.min(1.0, newProgress));
        if (this.progress >= 1.0 && !Boolean.TRUE.equals(this.isComplete)) {
            this.isComplete = true;
            this.completedAt = Time4JHelper.nowUTCString();
        }
        this.updatedAt = Time4JHelper.nowUTCString();
    }
}
