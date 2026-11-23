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
public class CharacterStat {
    private UUID statId;
    private UUID conversationId;
    private UUID characterId;
    private String statName;
    @Builder.Default
    private Double statValue = 0.0;
    private Double statMax;
    @Builder.Default
    private String statType = "numeric";
    private String statCategory;
    private String textValue;
    @Builder.Default
    private Boolean isVisible = true;
    private String createdAt;
    private String updatedAt;

    public boolean isAtMax() {
        return statMax != null && statValue != null && statValue >= statMax;
    }

    public boolean isAtMin() {
        return statValue != null && statValue <= 0;
    }

    public void modify(double delta) {
        if (statValue == null) statValue = 0.0;
        statValue += delta;
        if (statMax != null && statValue > statMax) statValue = statMax;
        if (statValue < 0) statValue = 0.0;
        this.updatedAt = Time4JHelper.nowUTCString();
    }
}
