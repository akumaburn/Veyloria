package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StreamingMessage {
    private String id;
    private String content;
    private String streamingContent;
    private String role;
    @Builder.Default
    private Boolean isComplete = false;
    private Integer tokenCount;
    private String error;
    private String createdAt;
    private String characterId;
    private Character character;
    private String displayName;
    private String conversationId;
    private String messageType;
    private String battleId;
    private GenerationStats generationStats;
}
