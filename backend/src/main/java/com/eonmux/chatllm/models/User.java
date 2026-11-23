package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * User entity matching the GraphQL schema and frontend expectations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    private UUID id;
    private String username;
    private String passwordHash;
    private String email;
    private String displayName;
    @Builder.Default
    private String role = "USER"; // New field for role
    private String assistantName;
    private String language;
    private Boolean notifications;
    private Boolean sounds;
    private String fontSize;
    private String codeTheme;
    private Boolean analytics;
    private Boolean improvement;
    
    // User status
    private Boolean admin;
    private Boolean emailVerified;
    private String lastLogin;
    private String profilePicture;
    private String bio;
    @Builder.Default
    private String theme = "dark";
    private String keyboardShortcuts;

    // Ban management
    @Builder.Default
    private Boolean isBanned = false;
    private String bannedAt;
    private String bannedReason;
    private UUID bannedBy;

    // Notification preferences
    @Builder.Default
    private Boolean desktopNotifications = false;
    @Builder.Default
    private Boolean emailNotifications = false;
    @Builder.Default
    private Boolean messageAlerts = true;
    @Builder.Default
    private Boolean systemAlerts = true;
    
    // Model preferences
    private String preferredModelPath;
    private Integer preferredGpuLayers;
    
    // Rate limits
    private Long apiLimits;
    private Long chatLimits;
    
    // Timestamps
    private String createdAt;
    private String updatedAt;
    
    // Relationships (IDs only for GraphQL)
    private UUID currentConversationId;
    @Builder.Default
    private Set<UUID> conversationIds = new HashSet<>();
    @Builder.Default
    private Set<UUID> characterIds = new HashSet<>();
    @Builder.Default
    private Set<UUID> sessionIds = new HashSet<>();
    
    // Nested preferences object for GraphQL
    private UserPreferences preferences;
    
    // Transient fields
    @Builder.Default
    private transient Set<String> roles = new HashSet<>();
    @org.eclipse.microprofile.graphql.Ignore
    private transient UserSettings settings; // Resolved via UserResolver @Source method
    
    /**
     * Initialize preferences from individual fields.
     */
    public UserPreferences getPreferences() {
        if (preferences == null) {
            preferences = new UserPreferences();
            preferences.setTheme(theme);
            preferences.setLanguage(language);
            preferences.setFontSize(fontSize);
            preferences.setSoundEnabled(sounds);
            preferences.setNotificationsEnabled(notifications);
            preferences.setCodeTheme(codeTheme);
            preferences.setAnalytics(analytics);
        }
        return preferences;
    }
    
    /**
     * Get role for GraphQL.
     */
    public String getRole() {
        if (role != null) return role;
        if (Boolean.TRUE.equals(admin)) return "ADMIN";
        return "USER";
    }
    
    /**
     * Check if user has admin role.
     */
    public boolean hasAdminRole() {
        return "ADMIN".equals(getRole()) || roles.contains("ADMIN");
    }
    
    /**
     * Check if user is admin.
     */
    public boolean isAdminUser() {
        return Boolean.TRUE.equals(admin) || hasAdminRole();
    }
    
    /**
     * Get display name or username if not set.
     */
    public String getEffectiveDisplayName() {
        return displayName != null && !displayName.isEmpty() ? displayName : username;
    }
    
    /**
     * Get assistant name or default if not set.
     */
    public String getEffectiveAssistantName() {
        return assistantName != null && !assistantName.isEmpty() ? assistantName : "Assistant";
    }
    
    /**
     * Check if user has unlimited API calls.
     */
    public boolean hasUnlimitedApiCalls() {
        return apiLimits == null || apiLimits < 0;
    }
    
    /**
     * Check if user has unlimited chat messages.
     */
    public boolean hasUnlimitedChatMessages() {
        return chatLimits == null || chatLimits < 0;
    }
}