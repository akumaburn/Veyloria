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
public class UserProgression {
    private UUID userId;
    @Builder.Default
    private Long totalXP = 0L;
    @Builder.Default
    private Integer level = 1;
    @Builder.Default
    private String title = "Novice Storyteller";
    @Builder.Default
    private Integer storiesCompleted = 0;
    @Builder.Default
    private Integer totalMessages = 0;
    @Builder.Default
    private Integer totalDiceRolls = 0;
    @Builder.Default
    private Integer totalPlaytimeMin = 0;
    private String createdAt;
    private String updatedAt;

    public void addXP(long xp) {
        if (totalXP == null) totalXP = 0L;
        totalXP += xp;
        recalculateLevel();
        this.updatedAt = Time4JHelper.nowUTCString();
    }

    private void recalculateLevel() {
        // Level thresholds: 100, 300, 600, 1000, 1500, 2100, 2800, ...
        // Formula: XP needed for level N = N * 100
        int newLevel = 1;
        long xpNeeded = 0;
        while (xpNeeded + (newLevel * 100L) <= totalXP) {
            xpNeeded += newLevel * 100L;
            newLevel++;
        }
        this.level = newLevel;
        updateTitle();
    }

    private void updateTitle() {
        if (level >= 20) title = "Legendary Storyteller";
        else if (level >= 15) title = "Master Storyteller";
        else if (level >= 10) title = "Expert Storyteller";
        else if (level >= 7) title = "Seasoned Storyteller";
        else if (level >= 5) title = "Skilled Storyteller";
        else if (level >= 3) title = "Apprentice Storyteller";
        else title = "Novice Storyteller";
    }
}
