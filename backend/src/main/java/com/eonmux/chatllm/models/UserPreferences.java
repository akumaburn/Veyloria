package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * User preferences matching the GraphQL schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPreferences {
    private String theme;
    private String language;
    private String fontSize;
    private Boolean soundEnabled;
    private Boolean notificationsEnabled;
    private String codeTheme;
    private Boolean analytics;
}