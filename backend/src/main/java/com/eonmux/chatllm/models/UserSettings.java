package com.eonmux.chatllm.models;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.eonmux.chatllm.utils.Time4JHelper;

/**
 * User settings entity for storing all user preferences and configurations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSettings {
    private UUID id;
    private Integer settingsId; // For database compatibility
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private UUID userId;
    private String userIdString; // For String compatibility
    
    // UI Settings
    @Builder.Default
    private String theme = "dark";
    @Builder.Default
    private String language = "en";
    @Builder.Default
    private String fontSize = "medium";
    @Builder.Default
    private String fontFamily = "default";
    @Builder.Default
    private Boolean compactMode = false;
    @Builder.Default
    private Boolean sidebarCollapsed = false;
    @Builder.Default
    private String sidebarPosition = "left";
    private Integer messageListHeight;
    
    // Feature Settings
    @Builder.Default
    private Boolean soundEnabled = true;
    @Builder.Default
    private Boolean notificationsEnabled = true;
    @Builder.Default
    private Boolean desktopNotifications = false;
    @Builder.Default
    private Boolean emailNotifications = false;
    @Builder.Default
    private Boolean analytics = true;
    @Builder.Default
    private Boolean improvement = true;
    @Builder.Default
    private Boolean developerMode = false;
    @Builder.Default
    private Boolean experimentalFeatures = false;
    
    // Code Settings
    @Builder.Default
    private String codeTheme = "monokai";
    @Builder.Default
    private Boolean syntaxHighlighting = true;
    @Builder.Default
    private Boolean lineNumbers = true;
    @Builder.Default
    private Boolean wordWrap = false;
    @Builder.Default
    private Integer tabSize = 4;
    @Builder.Default
    private Boolean autoIndent = true;
    
    // Chat Settings
    @Builder.Default
    private Boolean enterToSend = true;
    @Builder.Default
    private Boolean showTypingIndicator = true;
    @Builder.Default
    private Boolean showReadReceipts = false;
    @Builder.Default
    private Boolean autoScroll = true;
    @Builder.Default
    private Boolean autoSave = true;
    @Builder.Default
    private Boolean messageStreaming = true;
    @Builder.Default
    private Boolean showTimestamps = true;
    @Builder.Default
    private String timestampFormat = "relative";
    @Builder.Default
    private Boolean showAvatars = true;
    @Builder.Default
    private Integer maxMessageLength = 10000;
    
    // LLM Settings (compatibility names)
    @Builder.Default
    private String preferredModel = "default";
    @Builder.Default
    private Float llmTemperature = 0.7f;
    @Builder.Default
    private Integer llmMaxTokens = 2000;
    @Builder.Default
    private Float llmTopP = 0.9f;
    @Builder.Default
    private Integer llmTopK = 40;
    @Builder.Default
    private Float llmRepeatPenalty = 1.0f;
    @Builder.Default
    private Integer llmContextSize = 4096;
    private Integer llmSeed;
    @Builder.Default
    private String llmSystemPrompt = "You are a helpful AI assistant.";
    @Builder.Default
    private String llmTemplate = "default";
    
    // Old field names for compatibility
    @Builder.Default
    private Float temperature = 0.7f;
    @Builder.Default
    private Integer maxTokens = 2000;
    @Builder.Default
    private Float topP = 0.9f;
    @Builder.Default
    private Integer topK = 40;
    @Builder.Default
    private Float repeatPenalty = 1.0f;
    @Builder.Default
    private Boolean streamResponses = true;
    @Builder.Default
    private Integer responseTimeout = 30000; // milliseconds
    
    // Privacy Settings
    @Builder.Default
    private Boolean saveHistory = true;
    @Builder.Default
    private Boolean shareDataForImprovement = false;
    @Builder.Default
    private Boolean allowTelemetry = false;
    @Builder.Default
    private String dataRetentionDays = "30";
    
    // Accessibility Settings
    @Builder.Default
    private Boolean highContrast = false;
    @Builder.Default
    private Boolean reducedMotion = false;
    @Builder.Default
    private Boolean screenReaderMode = false;
    @Builder.Default
    private String keyboardShortcuts = "default";
    
    // Advanced Settings
    @org.eclipse.microprofile.graphql.Ignore
    @Builder.Default
    private Map<String, Object> customSettings = new HashMap<>();
    private String createdAt;
    private String updatedAt;
    
    /**
     * Get a custom setting value.
     */
    @SuppressWarnings("unchecked")
    public <T> T getCustomSetting(String key, Class<T> type, T defaultValue) {
        if (customSettings == null || !customSettings.containsKey(key)) {
            return defaultValue;
        }
        Object value = customSettings.get(key);
        if (value != null && type.isAssignableFrom(value.getClass())) {
            return (T) value;
        }
        return defaultValue;
    }
    
    /**
     * Set a custom setting value.
     */
    public void setCustomSetting(String key, Object value) {
        if (customSettings == null) {
            customSettings = new HashMap<>();
        }
        customSettings.put(key, value);
        touch();
    }
    
    /**
     * Remove a custom setting.
     */
    public void removeCustomSetting(String key) {
        if (customSettings != null) {
            customSettings.remove(key);
            touch();
        }
    }
    
    /**
     * Update the last modified timestamp.
     */
    public void touch() {
        this.updatedAt = Time4JHelper.nowUTCString();
    }
    
    /**
     * Apply default settings for a new user.
     */
    public static UserSettings createDefaultSettings(UUID userId) {
        return UserSettings.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .theme("dark")
            .language("en")
            .fontSize("medium")
            .soundEnabled(true)
            .notificationsEnabled(true)
            .analytics(true)
            .improvement(true)
            .codeTheme("monokai")
            .syntaxHighlighting(true)
            .lineNumbers(true)
            .enterToSend(true)
            .showTypingIndicator(true)
            .autoScroll(true)
            .showTimestamps(true)
            .showAvatars(true)
            .preferredModel("default")
            .temperature(0.7f)
            .maxTokens(2000)
            .topP(0.9f)
            .topK(40)
            .repeatPenalty(1.0f)
            .streamResponses(true)
            .saveHistory(true)
            .createdAt(Time4JHelper.nowUTCString())
            .updatedAt(Time4JHelper.nowUTCString())
            .build();
    }
    
    /**
     * Check if dark mode is enabled.
     */
    public boolean isDarkMode() {
        return "dark".equalsIgnoreCase(theme);
    }
    
    /**
     * Check if compact mode is enabled.
     */
    public boolean isCompactMode() {
        return Boolean.TRUE.equals(compactMode);
    }
    
    /**
     * Check if developer mode is enabled.
     */
    public boolean isDeveloperMode() {
        return Boolean.TRUE.equals(developerMode);
    }
    
    /**
     * Check if experimental features are enabled.
     */
    public boolean hasExperimentalFeatures() {
        return Boolean.TRUE.equals(experimentalFeatures);
    }
    
    /**
     * Convert to UserPreferences for backward compatibility.
     */
    public UserPreferences toUserPreferences() {
        return UserPreferences.builder()
            .theme(theme)
            .language(language)
            .fontSize(fontSize)
            .soundEnabled(soundEnabled)
            .notificationsEnabled(notificationsEnabled)
            .codeTheme(codeTheme)
            .analytics(analytics)
            .build();
    }
    
    /**
     * Update from UserPreferences.
     */
    public void updateFromUserPreferences(UserPreferences prefs) {
        if (prefs == null) return;
        
        if (prefs.getTheme() != null) this.theme = prefs.getTheme();
        if (prefs.getLanguage() != null) this.language = prefs.getLanguage();
        if (prefs.getFontSize() != null) this.fontSize = prefs.getFontSize();
        if (prefs.getSoundEnabled() != null) this.soundEnabled = prefs.getSoundEnabled();
        if (prefs.getNotificationsEnabled() != null) this.notificationsEnabled = prefs.getNotificationsEnabled();
        if (prefs.getCodeTheme() != null) this.codeTheme = prefs.getCodeTheme();
        if (prefs.getAnalytics() != null) this.analytics = prefs.getAnalytics();
        
        touch();
    }
    
    // Compatibility methods for SettingsService
    
    public Float getLlmTemperatureFloat() {
        return llmTemperature != null ? llmTemperature : 0.7f;
    }
    
    public void setLlmTemperature(Float temp) {
        this.llmTemperature = temp != null ? temp : 0.7f;
        this.temperature = this.llmTemperature;
    }
    
    public Float getLlmTopPFloat() {
        return llmTopP != null ? llmTopP : 0.9f;
    }
    
    public Float getLlmRepeatPenaltyFloat() {
        return llmRepeatPenalty != null ? llmRepeatPenalty : 1.0f;
    }
    
    // Additional boolean getters for compatibility
    public boolean isSoundEnabled() {
        return Boolean.TRUE.equals(soundEnabled);
    }
    
    public boolean isNotificationsEnabled() {
        return Boolean.TRUE.equals(notificationsEnabled);
    }
    
    public boolean isEmailNotifications() {
        return Boolean.TRUE.equals(emailNotifications);
    }
    
    public boolean isAutoSave() {
        return Boolean.TRUE.equals(autoSave);
    }
    
    public boolean isMessageStreaming() {
        return Boolean.TRUE.equals(messageStreaming);
    }
    
    public boolean isShowTypingIndicator() {
        return Boolean.TRUE.equals(showTypingIndicator);
    }
    
    public boolean isEnterToSend() {
        return Boolean.TRUE.equals(enterToSend);
    }
    
    // Sync LLM settings with normal settings
    public void syncLlmSettings() {
        this.temperature = this.llmTemperature;
        this.maxTokens = this.llmMaxTokens;
        this.topP = this.llmTopP;
        this.topK = this.llmTopK;
        this.repeatPenalty = this.llmRepeatPenalty;
    }
    
    // Additional getters for compatibility
    public Integer getContextSize() {
        return llmContextSize;
    }
    
    public void setContextSize(Integer contextSize) {
        this.llmContextSize = contextSize;
    }
    
    public Integer getSeed() {
        return llmSeed;
    }
    
    public String getSystemPrompt() {
        return llmSystemPrompt;
    }
    
    public String getPromptTemplate() {
        return llmTemplate;
    }
    
    public void setPromptTemplate(String template) {
        this.llmTemplate = template;
    }
    
    public void setPreferredModelPath(String path) {
        this.preferredModel = path;
    }
    
    public void setPreferredGpuLayers(Integer layers) {
        // Store in custom settings for now
        setCustomSetting("preferredGpuLayers", layers);
    }
    
    // String/UUID compatibility methods for userId
    // Use this when setting from database (String)
    public void setUserId(String userIdStr) {
        this.userIdString = userIdStr;
        if (userIdStr != null && !userIdStr.isEmpty()) {
            try {
                this.userId = UUID.fromString(userIdStr);
            } catch (IllegalArgumentException e) {
                // If not a valid UUID, just store as string
                this.userId = null;
            }
        }
    }
    
    // Use this when setting from model (UUID)
    public void setUserIdUuid(UUID userIdUuid) {
        this.userId = userIdUuid;
        this.userIdString = userIdUuid != null ? userIdUuid.toString() : null;
    }
    
    public UUID getUserId() {
        if (userId != null) return userId;
        if (userIdString != null && !userIdString.isEmpty()) {
            try {
                return UUID.fromString(userIdString);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
    
    public String getUserIdAsString() {
        if (userIdString != null) return userIdString;
        if (userId != null) return userId.toString();
        return null;
    }
}