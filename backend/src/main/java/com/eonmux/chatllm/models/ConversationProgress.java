package com.eonmux.chatllm.models;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ConversationProgress {
    private String conversationId;
    private String runId;
    private long sequence;
    private String stage;
    private String label;
    private boolean active;
    private String updatedAt;
}
