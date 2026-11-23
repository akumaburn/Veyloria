package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GraphQL response type for admin actions (updateUserRole, banUser, unbanUser).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminActionResult {
    private Boolean success;
    private User user;
    private String previousRole;
    private String newRole;
    private Boolean isBanned;
    private String bannedAt;
    private String bannedReason;
    private String message;
}
