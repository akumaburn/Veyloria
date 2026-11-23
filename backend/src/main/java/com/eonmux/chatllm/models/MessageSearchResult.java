package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageSearchResult {
    private UUID id;
    private String content;
    private String role;
    private String displayName;
    private Conversation conversation;
    private String highlightedContent;
    private String createdAt;
}
