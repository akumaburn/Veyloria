package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserStatistics {
    @Builder.Default
    private int totalConversations = 0;
    @Builder.Default
    private int totalMessages = 0;
    @Builder.Default
    private int totalCharacters = 0;
    @Builder.Default
    private int totalTokens = 0;
    @Builder.Default
    private long storageUsed = 0;
    @Builder.Default
    private int apiCallsToday = 0;
    @Builder.Default
    private int messagesToday = 0;
    @Builder.Default
    private double avgTokensPerSecond = 0.0;
}
