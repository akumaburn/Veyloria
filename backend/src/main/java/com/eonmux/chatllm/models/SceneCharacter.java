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
public class SceneCharacter {
    private UUID id;
    private UUID sceneId;
    private UUID characterId;
    private String joinedAt;
    private String leftAt;

    public boolean isPresent() {
        return leftAt == null;
    }

    public void leave() {
        this.leftAt = Time4JHelper.nowUTCString();
    }
}
