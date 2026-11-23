package com.eonmux.chatllm.services;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates settings values to ensure data integrity and prevent invalid configurations.
 * Implements all validation rules from settings-trace.md analysis.
 */
public class SettingsValidator {

    // Valid enum values
    private static final Set<String> VALID_THEMES = new HashSet<>(Arrays.asList("dark", "light", "auto", "system"));
    private static final Set<String> VALID_FONT_SIZES = new HashSet<>(Arrays.asList("small", "medium", "large"));
    private static final Set<String> VALID_SIDEBAR_POSITIONS = new HashSet<>(Arrays.asList("left", "right"));
    private static final Set<String> VALID_MESSAGE_LAYOUTS = new HashSet<>(Arrays.asList("bubble", "standard"));
    private static final Set<String> VALID_PROMPT_TEMPLATES = new HashSet<>(Arrays.asList(
        "chatml", "alpaca", "vicuna", "llama", "llama2", "llama3", "mistral",
        "phi", "zephyr", "deepseek", "command-r", "custom"
    ));
    private static final Set<String> VALID_NOTIFICATION_SCHEDULES = new HashSet<>(Arrays.asList("always", "daytime", "workHours", "custom", "silent", "never"));
    private static final Set<String> VALID_EXPORT_FORMATS = new HashSet<>(Arrays.asList("json", "csv", "markdown", "txt"));

    // Regex patterns
    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]?[0-9]|2[0-3]):[0-5][0-9]$");

    /**
     * Validate LLM settings
     */
    public static class LLMValidation {

        public static void validateTemperature(Double temperature) {
            if (temperature == null) return;
            if (temperature < 0.0 || temperature > 2.0) {
                throw new IllegalArgumentException("temperature must be between 0.0 and 2.0, got: " + temperature);
            }
        }

        public static void validateMaxTokens(Integer maxTokens) {
            if (maxTokens == null) return;
            if (maxTokens != -1 && maxTokens <= 0) {
                throw new IllegalArgumentException("maxTokens must be positive or -1 for unlimited, got: " + maxTokens);
            }
        }

        public static void validateTopP(Double topP) {
            if (topP == null) return;
            if (topP < 0.0 || topP > 1.0) {
                throw new IllegalArgumentException("topP must be between 0.0 and 1.0, got: " + topP);
            }
        }

        public static void validateTopK(Integer topK) {
            if (topK == null) return;
            if (topK <= 0) {
                throw new IllegalArgumentException("topK must be positive, got: " + topK);
            }
        }

        public static void validateRepeatPenalty(Double repeatPenalty) {
            if (repeatPenalty == null) return;
            if (repeatPenalty < 1.0) {
                throw new IllegalArgumentException("repeatPenalty must be >= 1.0, got: " + repeatPenalty);
            }
        }

        public static void validatePresencePenalty(Double presencePenalty) {
            if (presencePenalty == null) return;
            if (presencePenalty < -2.0 || presencePenalty > 2.0) {
                throw new IllegalArgumentException("presencePenalty must be between -2.0 and 2.0, got: " + presencePenalty);
            }
        }

        public static void validateFrequencyPenalty(Double frequencyPenalty) {
            if (frequencyPenalty == null) return;
            if (frequencyPenalty < -2.0 || frequencyPenalty > 2.0) {
                throw new IllegalArgumentException("frequencyPenalty must be between -2.0 and 2.0, got: " + frequencyPenalty);
            }
        }

        public static void validateMinP(Double minP) {
            if (minP == null) return;
            if (minP < 0.0 || minP > 1.0) {
                throw new IllegalArgumentException("minP must be between 0.0 and 1.0, got: " + minP);
            }
        }

        public static void validateTfsZ(Double tfsZ) {
            if (tfsZ == null) return;
            if (tfsZ <= 0.0) {
                throw new IllegalArgumentException("tfsZ must be positive, got: " + tfsZ);
            }
        }

        public static void validateTypicalP(Double typicalP) {
            if (typicalP == null) return;
            if (typicalP < 0.0 || typicalP > 1.0) {
                throw new IllegalArgumentException("typicalP must be between 0.0 and 1.0, got: " + typicalP);
            }
        }

        public static void validateNPredict(Integer nPredict) {
            if (nPredict == null) return;
            if (nPredict <= 0) {
                throw new IllegalArgumentException("nPredict must be positive, got: " + nPredict);
            }
        }

        public static void validateNCtx(Integer nCtx) {
            if (nCtx == null) return;
            if (nCtx < 512 || nCtx > 32768) {
                throw new IllegalArgumentException("nCtx must be between 512 and 32768, got: " + nCtx);
            }
            // Should be power of 2, but we'll be lenient
        }

        public static void validateNBatch(Integer nBatch) {
            if (nBatch == null) return;
            if (nBatch <= 0) {
                throw new IllegalArgumentException("nBatch must be positive, got: " + nBatch);
            }
        }

        public static void validateNThreads(Integer nThreads) {
            if (nThreads == null) return;
            if (nThreads <= 0) {
                throw new IllegalArgumentException("nThreads must be positive, got: " + nThreads);
            }
            int maxThreads = Runtime.getRuntime().availableProcessors();
            if (nThreads > maxThreads * 2) {
                throw new IllegalArgumentException("nThreads exceeds reasonable limit (2x CPU cores), got: " + nThreads);
            }
        }

        public static void validateNGpuLayers(Integer nGpuLayers) {
            if (nGpuLayers == null) return;
            if (nGpuLayers < 0) {
                throw new IllegalArgumentException("nGpuLayers must be non-negative, got: " + nGpuLayers);
            }
        }

        public static void validateMainGpu(Integer mainGpu) {
            if (mainGpu == null) return;
            if (mainGpu < 0) {
                throw new IllegalArgumentException("mainGpu must be non-negative, got: " + mainGpu);
            }
        }

        public static void validatePromptTemplate(String template) {
            if (template == null || template.isEmpty()) return;
            if (!VALID_PROMPT_TEMPLATES.contains(template)) {
                throw new IllegalArgumentException("promptTemplate must be one of " + VALID_PROMPT_TEMPLATES + ", got: " + template);
            }
        }

        public static void validateMirostat(Integer mirostat) {
            if (mirostat == null) return;
            if (mirostat < 0 || mirostat > 2) {
                throw new IllegalArgumentException("mirostat must be 0, 1, or 2, got: " + mirostat);
            }
        }

        public static void validateMirostatTau(Double mirostatTau) {
            if (mirostatTau == null) return;
            if (mirostatTau <= 0.0) {
                throw new IllegalArgumentException("mirostatTau must be positive, got: " + mirostatTau);
            }
        }

        public static void validateMirostatEta(Double mirostatEta) {
            if (mirostatEta == null) return;
            if (mirostatEta < 0.0 || mirostatEta > 1.0) {
                throw new IllegalArgumentException("mirostatEta must be between 0.0 and 1.0, got: " + mirostatEta);
            }
        }
    }

    /**
     * Validate UI settings
     */
    public static class UIValidation {

        public static void validateTheme(String theme) {
            if (theme == null || theme.isEmpty()) return;
            if (!VALID_THEMES.contains(theme)) {
                throw new IllegalArgumentException("theme must be one of " + VALID_THEMES + ", got: " + theme);
            }
        }

        public static void validateFontSize(String fontSize) {
            if (fontSize == null || fontSize.isEmpty()) return;
            if (!VALID_FONT_SIZES.contains(fontSize)) {
                throw new IllegalArgumentException("fontSize must be one of " + VALID_FONT_SIZES + ", got: " + fontSize);
            }
        }

        public static void validateAutoSaveInterval(Integer interval) {
            if (interval == null) return;
            if (interval <= 0 || interval > 300) {
                throw new IllegalArgumentException("autoSaveInterval must be between 1 and 300 seconds, got: " + interval);
            }
        }

        public static void validateSidebarPosition(String position) {
            if (position == null || position.isEmpty()) return;
            if (!VALID_SIDEBAR_POSITIONS.contains(position)) {
                throw new IllegalArgumentException("sidebarPosition must be one of " + VALID_SIDEBAR_POSITIONS + ", got: " + position);
            }
        }

        public static void validateMessageLayout(String layout) {
            if (layout == null || layout.isEmpty()) return;
            if (!VALID_MESSAGE_LAYOUTS.contains(layout)) {
                throw new IllegalArgumentException("messageLayout must be one of " + VALID_MESSAGE_LAYOUTS + ", got: " + layout);
            }
        }
    }

    /**
     * Validate notification settings
     */
    public static class NotificationValidation {

        public static void validateSchedule(String schedule) {
            if (schedule == null || schedule.isEmpty()) return;
            if (!VALID_NOTIFICATION_SCHEDULES.contains(schedule)) {
                throw new IllegalArgumentException("schedule must be one of " + VALID_NOTIFICATION_SCHEDULES + ", got: " + schedule);
            }
        }

        public static void validateTime(String time, String fieldName) {
            if (time == null || time.isEmpty()) return;
            if (!TIME_PATTERN.matcher(time).matches()) {
                throw new IllegalArgumentException(fieldName + " must be in HH:mm format, got: " + time);
            }
        }

        public static void validateDaysOfWeek(List<Integer> days) {
            if (days == null || days.isEmpty()) {
                throw new IllegalArgumentException("daysOfWeek cannot be empty");
            }
            for (Integer day : days) {
                if (day == null || day < 1 || day > 7) {
                    throw new IllegalArgumentException("daysOfWeek must contain values 1-7 (Monday-Sunday), got: " + day);
                }
            }
        }

        public static void validateScheduleTimes(String startTime, String endTime) {
            if (startTime == null || endTime == null) return;

            // Parse times
            String[] start = startTime.split(":");
            String[] end = endTime.split(":");

            int startMinutes = Integer.parseInt(start[0]) * 60 + Integer.parseInt(start[1]);
            int endMinutes = Integer.parseInt(end[0]) * 60 + Integer.parseInt(end[1]);

            if (endMinutes <= startMinutes) {
                throw new IllegalArgumentException("endTime must be after startTime, got: " + startTime + " to " + endTime);
            }
        }
    }

    /**
     * Validate privacy settings
     */
    public static class PrivacyValidation {

        public static void validateExportFormat(String format) {
            if (format == null || format.isEmpty()) return;
            if (!VALID_EXPORT_FORMATS.contains(format)) {
                throw new IllegalArgumentException("exportFormat must be one of " + VALID_EXPORT_FORMATS + ", got: " + format);
            }
        }

        public static void validateDataRetentionDays(Integer days) {
            if (days == null) return;
            // -1 or 0 means "keep forever"
            if (days < -1) {
                throw new IllegalArgumentException("dataRetentionDays must be -1 (forever), 0 (forever), or positive, got: " + days);
            }
            if (days > 3650) { // 10 years max
                throw new IllegalArgumentException("dataRetentionDays exceeds maximum of 3650 days (10 years), got: " + days);
            }
        }

        /**
         * CRITICAL: Validate that privacy settings that aren't implemented are prevented
         */
        public static void validateUnimplementedFeatures(Boolean encryptMessages, Boolean saveHistory) {
            if (encryptMessages != null && encryptMessages) {
                throw new IllegalArgumentException("Message encryption is not yet implemented. This feature is coming soon.");
            }
            // saveHistory is enforced in SettingsService.java:138-166 (ephemeral purge logic)
        }
    }

    /**
     * Validate keyboard shortcuts
     */
    public static class KeyboardValidation {

        // Updated pattern to allow punctuation characters (comma, period, slash, brackets, etc.)
        // Matches: Modifier+ (optional, repeatable) followed by either:
        // - Word characters (a-z, A-Z, 0-9, _)
        // - Special named keys (Enter, Escape, etc.)
        // - Punctuation/symbols (comma, period, semicolon, brackets, etc.)
        private static final Pattern KEY_COMBO_PATTERN = Pattern.compile(
            "^(Ctrl\\+|Alt\\+|Shift\\+|Meta\\+)*(" +
            "\\w+|" +                                    // Alphanumeric keys
            "Enter|Escape|Tab|Space|Backspace|Delete|" + // Special keys
            "Up|Down|Left|Right|" +                      // Arrow keys
            "Home|End|PageUp|PageDown|Insert|" +         // Navigation keys
            "F[0-9]{1,2}|" +                             // Function keys F1-F12
            "[,\\.;'/\\[\\]\\\\`\\-=]" +                // Punctuation: , . ; ' / [ ] \ ` - =
            ")$"
        );

        public static void validateKeyCombo(String combo, String actionName) {
            if (combo == null || combo.isEmpty()) {
                throw new IllegalArgumentException("Keyboard shortcut for " + actionName + " cannot be empty");
            }
            if (!KEY_COMBO_PATTERN.matcher(combo).matches()) {
                throw new IllegalArgumentException("Invalid keyboard shortcut format for " + actionName + ": " + combo);
            }
        }

        public static void validateNoConflicts(java.util.Map<String, String> shortcuts) {
            // Check for duplicate shortcuts
            Set<String> usedCombos = new HashSet<>();
            for (java.util.Map.Entry<String, String> entry : shortcuts.entrySet()) {
                String combo = entry.getValue();
                if (usedCombos.contains(combo)) {
                    throw new IllegalArgumentException("Duplicate keyboard shortcut: " + combo + " is assigned to multiple actions");
                }
                usedCombos.add(combo);
            }
        }
    }
}
