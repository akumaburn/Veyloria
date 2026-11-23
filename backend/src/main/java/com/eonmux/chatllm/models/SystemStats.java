package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemStats {
    @Builder.Default
    private int totalUsers = 0;
    @Builder.Default
    private int activeUsers = 0;
    @Builder.Default
    private int totalConversations = 0;
    @Builder.Default
    private int totalMessages = 0;
    @Builder.Default
    private int totalCharacters = 0;
    @Builder.Default
    private int messagesLast24h = 0;
    @Builder.Default
    private int newUsersLast24h = 0;
    @Builder.Default
    private long serverUptime = 0;
    @Builder.Default
    private long memoryUsed = 0;
    @Builder.Default
    private long memoryTotal = 0;
    @Builder.Default
    private long databaseSize = 0;
}
