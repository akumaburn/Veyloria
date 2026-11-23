package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionInfo {
    private String id;
    private String userId;
    private String expiresAt;
    private String lastActivity;
    private String ipAddress;
}
