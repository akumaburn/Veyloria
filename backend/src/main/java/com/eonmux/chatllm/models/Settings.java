package com.eonmux.chatllm.models;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

import com.eonmux.chatllm.utils.Time4JHelper;

/**
 * Main Settings entity that matches the JSON structure in the database.
 * Uses Gson for JSON serialization/deserialization.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Settings {
    private String settingsId;
    private String userId;

    @Builder.Default
    private LLMSettings llm = new LLMSettings();

    @Builder.Default
    private UISettings ui = new UISettings();

    @Builder.Default
    private NotificationSettings notifications = new NotificationSettings();

    @Builder.Default
    private PrivacySettings privacy = new PrivacySettings();

    @Builder.Default
    private KeyboardSettings keyboard = new KeyboardSettings();

    private String created;
    private String updated;

    /**
     * LLM Settings nested class
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LLMSettings {
        private String model;

        @Builder.Default
        private Double temperature = 0.7;

        @Builder.Default
        private Integer maxTokens = -1;

        @Builder.Default
        private Double topP = 0.9;

        @Builder.Default
        private Integer topK = 40;

        @Builder.Default
        private Double repeatPenalty = 1.1;

        @Builder.Default
        private Double presencePenalty = 0.0;

        @Builder.Default
        private Double frequencyPenalty = 0.0;

        @Builder.Default
        private Double minP = 0.05;

        @Builder.Default
        private Double tfsZ = 1.0;

        @Builder.Default
        private Double typicalP = 1.0;

        private Integer seed;

        @Builder.Default
        private List<String> stopSequences = new ArrayList<>();

        @Builder.Default
        private Integer nPredict = 512;

        @Builder.Default
        private Integer nCtx = 4096;

        @Builder.Default
        private Integer nBatch = 512;

        @Builder.Default
        private Integer nThreads = 8;

        @Builder.Default
        private Integer nGpuLayers = 32;

        @Builder.Default
        private Integer mainGpu = 0;

        @Builder.Default
        private String promptTemplate = "chatml";

        private String systemPrompt;

        @Builder.Default
        private String chatFormat = "chatml";

        @Builder.Default
        private Boolean stream = true;

        @Builder.Default
        private Integer mirostat = 0;

        @Builder.Default
        private Double mirostatTau = 5.0;

        @Builder.Default
        private Double mirostatEta = 0.1;

        // Legacy compatibility fields
        public Boolean getStreamingEnabled() {
            return stream;
        }

        public void setStreamingEnabled(Boolean streaming) {
            this.stream = streaming;
        }

        public Integer getContextSize() {
            return nCtx;
        }

        public void setContextSize(Integer contextSize) {
            this.nCtx = contextSize;
        }

        public List<String> getTemplates() {
            return Arrays.asList("chatml", "alpaca", "llama", "custom");
        }

        public String getCurrentTemplate() {
            return promptTemplate;
        }
    }

    /**
     * UI Settings nested class
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    
    public static class UISettings {
        @Builder.Default
        private String theme = "dark";

        @Builder.Default
        private String fontSize = "medium";

        @Builder.Default
        private String fontFamily = "Inter";

        @Builder.Default
        private Boolean compactMode = false;

        @Builder.Default
        private Boolean showTimestamps = true;

        @Builder.Default
        private Boolean showTokenCount = true;

        @Builder.Default
        private Boolean showModelInfo = true;

        @Builder.Default
        private Boolean enterToSend = true;

        @Builder.Default
        private Boolean soundEnabled = false;

        @Builder.Default
        private Boolean notificationsEnabled = true;

        @Builder.Default
        private Boolean autoScroll = true;

        @Builder.Default
        private Boolean messageGrouping = true;

        @Builder.Default
        private Boolean syntaxHighlighting = true;

        @Builder.Default
        private Boolean markdownRendering = true;

        @Builder.Default
        private Boolean emojiPicker = true;

        @Builder.Default
        private Boolean spellCheck = true;

        @Builder.Default
        private Boolean autoSave = true;

        @Builder.Default
        private Integer autoSaveInterval = 30;

        @Builder.Default
        private String sidebarPosition = "left";

        @Builder.Default
        private Boolean sidebarCollapsed = false;

        @Builder.Default
        private String messageLayout = "bubble";

        // Legacy compatibility fields
        @Builder.Default
        private String language = "en";

        private String codeTheme;
        private String editorTheme;
        private Boolean showLineNumbers;
        private Boolean wordWrap;

        public String getCodeTheme() {
            return syntaxHighlighting != null && syntaxHighlighting ? "default" : "none";
        }

        public String getEditorTheme() {
            return theme;
        }

        public Boolean getShowLineNumbers() {
            return true;
        }

        public Boolean getWordWrap() {
            return true;
        }
    }

    /**
     * Notification Settings nested class
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    
    public static class NotificationSettings {
        @Builder.Default
        private Boolean desktop = true;

        @Builder.Default
        private Boolean sound = false;

        @Builder.Default
        private Boolean vibration = false;

        @Builder.Default
        private Boolean mentions = true;

        @Builder.Default
        private Boolean replies = true;

        @Builder.Default
        private Boolean newConversations = false;

        @Builder.Default
        private Boolean systemAlerts = true;

        @Builder.Default
        private String schedule = "always";

        private NotificationSchedule customSchedule;

        // Legacy compatibility fields
        public Boolean getEnabled() {
            return desktop;
        }

        public void setEnabled(Boolean enabled) {
            this.desktop = enabled;
        }

        public Boolean getEmail() {
            return false;
        }

        public Boolean getMessageAlerts() {
            return mentions != null ? mentions : replies;
        }

        public void setMessageAlerts(Boolean alerts) {
            if (alerts != null) {
                this.mentions = alerts;
                this.replies = alerts;
            }
        }
    }

    /**
     * Notification Schedule nested class
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    
    public static class NotificationSchedule {
        @Builder.Default
        private String startTime = "09:00";

        @Builder.Default
        private String endTime = "18:00";

        @Builder.Default
        private List<Integer> daysOfWeek = Arrays.asList(1, 2, 3, 4, 5);
    }

    /**
     * Privacy Settings nested class
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    
    public static class PrivacySettings {
        @Builder.Default
        private Boolean shareAnalytics = false;

        @Builder.Default
        private Boolean saveHistory = true;

        @Builder.Default
        private Boolean encryptMessages = false;

        @Builder.Default
        private Boolean clearOnLogout = false;

        @Builder.Default
        private String exportFormat = "json";

        @Builder.Default
        private Integer dataRetentionDays = 30;

        // Legacy compatibility fields
        public Boolean getAnalytics() {
            return shareAnalytics;
        }

        public void setAnalytics(Boolean analytics) {
            this.shareAnalytics = analytics;
        }

        public Boolean getShareData() {
            return shareAnalytics;
        }

        public void setShareData(Boolean shareData) {
            this.shareAnalytics = shareData;
        }

        public Boolean getPublicProfile() {
            return false;
        }

        public Boolean getShowOnlineStatus() {
            return false;
        }
    }

    /**
     * Keyboard Settings nested class
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    
    public static class KeyboardSettings {
        @Builder.Default
        private Map<String, String> shortcuts = createDefaultShortcuts();

        public static Map<String, String> createDefaultShortcuts() {
            Map<String, String> defaults = new LinkedHashMap<>();
            defaults.put("newConversation", "Ctrl+N");
            defaults.put("searchConversations", "Ctrl+K");
            defaults.put("toggleSidebar", "Ctrl+B");
            defaults.put("focusInput", "Ctrl+L");
            defaults.put("sendMessage", "Enter");
            defaults.put("editLastMessage", "Up");
            defaults.put("deleteLastMessage", "Ctrl+D");
            defaults.put("regenerateResponse", "Ctrl+R");
            defaults.put("stopGeneration", "Escape");
            defaults.put("toggleTheme", "Ctrl+Shift+T");
            defaults.put("toggleSettings", "Ctrl+,");
            defaults.put("exportChat", "Ctrl+E");
            return defaults;
        }
    }

    /**
     * Create default settings for a new user
     */
    public static Settings createDefault(String userId) {
        return Settings.builder()
            .settingsId(UUID.randomUUID().toString())
            .userId(userId)
            .llm(LLMSettings.builder().build())
            .ui(UISettings.builder().build())
            .notifications(NotificationSettings.builder().build())
            .privacy(PrivacySettings.builder().build())
            .keyboard(KeyboardSettings.builder().build())
            .created(Time4JHelper.nowUTCString())
            .updated(Time4JHelper.nowUTCString())
            .build();
    }

    /**
     * Touch the updated timestamp
     */
    public void touch() {
        this.updated = Time4JHelper.nowUTCString();
    }
}
