package com.eonmux.chatllm.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GraphQL response type for authentication mutations (login, register, refreshToken).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
    private User user;
    private String accessToken;
    private String refreshToken;
    private Integer expiresIn;
}
