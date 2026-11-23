package com.eonmux.chatllm.services;

import jakarta.annotation.PostConstruct;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.Settings;
import com.eonmux.chatllm.utils.ServiceHelper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import io.smallrye.mutiny.Uni;

import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.redis.client.RedisAPI;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Service for user settings and preferences management.
 * Uses JSON-based storage in the settings table for flexible schema.
 * Uses Gson for JSON serialization/deserialization.
 */
@ApplicationScoped
@Slf4j
public class SettingsService {

    private static final int CACHE_TTL = 300; // 5 minutes
    private static final Gson gson = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    @Inject
    RedisAPI redis;
    // EventBus obtained from vertx.eventBus()
    @Inject
    ServiceHelper serviceHelper;

    @PostConstruct
    void init() {
    }

    /**
     * Get user settings.
     */
    public Uni<Settings> getUserSettings(String userId) {

        // Try cache first
        String cacheKey = "settings:v2:" + userId;

        return getCachedSettings(cacheKey)
            .chain(cached -> {
                if (cached != null) {
                    return Uni.createFrom().item(cached);
                }

                // Load from database
                String query = """
                    SELECT settingsId, userId,
                           llmSettings, uiSettings, notificationSettings,
                           privacySettings, keyboardSettings,
                           created, updated
                    FROM settings
                    WHERE userId = ?
                    """;

                return pool.preparedQuery(query)
                    .execute(Tuple.of(userId))
                    .map(rows -> {
                        if (rows.size() == 0) {
                            // Return default settings (will be saved on first update)
                            return Settings.createDefault(userId);
                        }

                        Row row = rows.iterator().next();
                        Settings settings = mapRowToSettings(row);

                        // Cache the settings
                        cacheSettings(cacheKey, settings);

                        return settings;
                    });
            })

            .onFailure().invoke(error -> {
                log.error("Failed to get settings for user {}: {}", userId, error.getMessage());
            });
    }

    /**
     * Update user settings (supports both nested and flat structure).
     */
    public Uni<Settings> updateSettings(String userId, JsonObject updates) {

        if (updates == null || updates.isEmpty()) {
            throw new RuntimeException("No settings to update");
    }

        // Get current settings
        return getUserSettings(userId)
            .chain(current -> {
                // Apply updates to current settings
                applyUpdates(current, updates);
                current.touch();

                // Check if settings record exists
                return settingsExist(userId)
                    .chain(exists -> {
                        if (exists) {
                            return updateSettingsInDb(current);
                        } else {
                            return insertSettingsInDb(current);
                        }
                    });
            })
            .map(settings -> {
                // Invalidate cache
                invalidateSettingsCache(userId);

                // Publish settings update event
                publishSettingsEvent("updated", userId, updates);

                // Track metric
                serviceHelper.trackMetric("metrics.settings.updated", new JsonObject()
                    .put("userId", userId));

                // Enforce ephemeral chat mode: purge message history when saveHistory is disabled
                JsonObject privacyUpdates = updates.getJsonObject("privacy");
                if (privacyUpdates != null && Boolean.FALSE.equals(privacyUpdates.getBoolean("saveHistory"))) {
                    purgeMessageHistory(userId);
                }

                return settings;
            })

            .onFailure().invoke(error -> {
                log.error("Failed to update settings for user {}: {}", userId, error.getMessage());
            });
    }

    /**
     * Purge all message history for a user (ephemeral chat mode enforcement).
     * Deletes messages from all conversations owned by the user.
     */
    private void purgeMessageHistory(String userId) {
        String deleteQuery = """
            DELETE m FROM message m
            JOIN conversation c ON m.conversationId = c.conversationGUID
            WHERE c.ownerId = ?
            """;
        pool.preparedQuery(deleteQuery)
            .execute(Tuple.of(userId))
            .invoke(result -> log.info("Ephemeral mode: purged {} messages for user {}", result.rowCount(), userId))
            .onFailure().invoke(err -> log.warn("Failed to purge messages for ephemeral mode: {}", err.getMessage()))
            .subscribe().with(v -> {}, err -> {});
    }

    /**
     * Reset settings to defaults.
     */
    public Uni<Settings> resetToDefaults(String userId) {

        Settings defaults = Settings.createDefault(userId);

        return settingsExist(userId)
            .chain(exists -> {
                if (exists) {
                    return updateSettingsInDb(defaults);
                } else {
                    return insertSettingsInDb(defaults);
                }
            })
            .map(settings -> {
                // Invalidate cache
                invalidateSettingsCache(userId);

                // Publish reset event
                publishSettingsEvent("reset", userId, null);

                return settings;
            })

            ;
    }

    /**
     * Export user settings as JSON.
     */
    public Uni<JsonObject> exportSettings(String userId) {
        return getUserSettings(userId)
            .map(settings -> {
                String json = gson.toJson(settings);
                return new JsonObject()
                    .put("version", "2.0")
                    .put("exportedAt", LocalDateTime.now(ZoneOffset.UTC).toString())
                    .put("userId", userId)
                    .put("settings", new JsonObject(json));
            });
    }

    /**
     * Import user settings from JSON.
     */
    public Uni<Settings> importSettings(String userId, JsonObject importData) {

        if (importData == null || !importData.containsKey("settings")) {
            throw new RuntimeException("Invalid import data");
    }

        try {
            JsonObject settingsJson = importData.getJsonObject("settings");
            Settings settings = gson.fromJson(settingsJson.encode(), Settings.class);
            settings.setUserId(userId);
            settings.setSettingsId(UUID.randomUUID().toString());
            settings.touch();

            return updateSettings(userId, JsonObject.mapFrom(settings));
        } catch (JsonSyntaxException e) {
            return Uni.createFrom().failure(new RuntimeException("Failed to parse import data: " + e.getMessage(), e));
        }
    }

    /**
     * Get available themes.
     */
    public Uni<JsonObject> getAvailableThemes() {

        JsonObject themes = new JsonObject()
            .put("themes", new io.vertx.core.json.JsonArray()
                .add(new JsonObject()
                    .put("id", "light")
                    .put("name", "Light")
                    .put("description", "Default light theme"))
                .add(new JsonObject()
                    .put("id", "dark")
                    .put("name", "Dark")
                    .put("description", "Dark theme for low-light environments"))
                .add(new JsonObject()
                    .put("id", "auto")
                    .put("name", "Auto")
                    .put("description", "Automatically switch based on system preference"))
                .add(new JsonObject()
                    .put("id", "system")
                    .put("name", "System")
                    .put("description", "Follow system theme")));

        return Uni.createFrom().item(themes);
    }

    /**
     * Get available languages.
     */
    public Uni<JsonObject> getAvailableLanguages() {

        JsonObject languages = new JsonObject()
            .put("languages", new io.vertx.core.json.JsonArray()
                .add(new JsonObject()
                    .put("code", "en")
                    .put("name", "English")
                    .put("nativeName", "English")));

        return Uni.createFrom().item(languages);
    }

    /**
     * Get LLM presets.
     */
    public Uni<JsonObject> getLLMPresets() {

        JsonObject presets = new JsonObject()
            .put("presets", new io.vertx.core.json.JsonArray()
                .add(new JsonObject()
                    .put("id", "creative")
                    .put("name", "Creative")
                    .put("description", "Higher temperature for more creative responses")
                    .put("settings", new JsonObject()
                        .put("temperature", 1.2)
                        .put("topP", 0.95)
                        .put("topK", 50)))
                .add(new JsonObject()
                    .put("id", "balanced")
                    .put("name", "Balanced")
                    .put("description", "Default balanced settings")
                    .put("settings", new JsonObject()
                        .put("temperature", 0.7)
                        .put("topP", 0.9)
                        .put("topK", 40)))
                .add(new JsonObject()
                    .put("id", "precise")
                    .put("name", "Precise")
                    .put("description", "Lower temperature for more focused responses")
                    .put("settings", new JsonObject()
                        .put("temperature", 0.3)
                        .put("topP", 0.85)
                        .put("topK", 20))));

        return Uni.createFrom().item(presets);
    }

    // ==================== Private Helper Methods ====================

    /**
     * Check if settings exist for a user.
     */
    private Uni<Boolean> settingsExist(String userId) {
        String query = "SELECT COUNT(*) as count FROM settings WHERE userId = ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(userId))
            .map(rows -> {
                Row row = rows.iterator().next();
                return row.getInteger("count") > 0;
            });
    }

    /**
     * Insert new settings into database.
     */
    private Uni<Settings> insertSettingsInDb(Settings settings) {
        String query = """
            INSERT INTO settings (settingsId, userId, llmSettings, uiSettings,
                                  notificationSettings, privacySettings, keyboardSettings,
                                  created, updated)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        String llmJson = gson.toJson(settings.getLlm());
        String uiJson = gson.toJson(settings.getUi());
        String notificationJson = gson.toJson(settings.getNotifications());
        String privacyJson = gson.toJson(settings.getPrivacy());
        String keyboardJson = gson.toJson(settings.getKeyboard());

        return pool.preparedQuery(query)
            .execute(Tuple.tuple()
                .addValue(settings.getSettingsId())
                .addValue(settings.getUserId())
                .addValue(llmJson)
                .addValue(uiJson)
                .addValue(notificationJson)
                .addValue(privacyJson)
                .addValue(keyboardJson)
                .addValue(settings.getCreated())
                .addValue(settings.getUpdated()))
            .map(result -> settings);
    }

    /**
     * Update existing settings in database.
     */
    private Uni<Settings> updateSettingsInDb(Settings settings) {
        String query = """
            UPDATE settings
            SET llmSettings = ?, uiSettings = ?, notificationSettings = ?,
                privacySettings = ?, keyboardSettings = ?, updated = ?
            WHERE userId = ?
            """;

        String llmJson = gson.toJson(settings.getLlm());
        String uiJson = gson.toJson(settings.getUi());
        String notificationJson = gson.toJson(settings.getNotifications());
        String privacyJson = gson.toJson(settings.getPrivacy());
        String keyboardJson = gson.toJson(settings.getKeyboard());

        return pool.preparedQuery(query)
            .execute(Tuple.tuple()
                .addValue(llmJson)
                .addValue(uiJson)
                .addValue(notificationJson)
                .addValue(privacyJson)
                .addValue(keyboardJson)
                .addValue(settings.getUpdated())
                .addValue(settings.getUserId()))
            .map(result -> settings);
    }

    /**
     * Apply updates from JsonObject to Settings object.
     * Supports both nested (GraphQL) and flat (legacy) structures.
     */
    private void applyUpdates(Settings settings, JsonObject updates) {
        // LLM Settings - nested structure
        if (updates.containsKey("llm")) {
            JsonObject llm = updates.getJsonObject("llm");
            if (llm != null) {
                applyLlmUpdates(settings.getLlm(), llm);
            }
        }

        // UI Settings - nested structure
        if (updates.containsKey("ui")) {
            JsonObject ui = updates.getJsonObject("ui");
            if (ui != null) {
                applyUiUpdates(settings.getUi(), ui);
            }
        }

        // Notification Settings - nested structure
        if (updates.containsKey("notifications")) {
            JsonObject notifications = updates.getJsonObject("notifications");
            if (notifications != null) {
                applyNotificationUpdates(settings.getNotifications(), notifications);
            }
        }

        // Privacy Settings - nested structure
        if (updates.containsKey("privacy")) {
            JsonObject privacy = updates.getJsonObject("privacy");
            if (privacy != null) {
                applyPrivacyUpdates(settings.getPrivacy(), privacy);
            }
        }

        // Keyboard Settings - nested structure
        if (updates.containsKey("keyboard")) {
            JsonObject keyboard = updates.getJsonObject("keyboard");
            if (keyboard != null) {
                applyKeyboardUpdates(settings.getKeyboard(), keyboard);
            }
        }
    }

    private void applyLlmUpdates(Settings.LLMSettings llm, JsonObject updates) {
        if (updates.containsKey("model")) {
            llm.setModel(updates.getString("model"));
        }
        if (updates.containsKey("temperature")) {
            Double temperature = updates.getDouble("temperature");
            SettingsValidator.LLMValidation.validateTemperature(temperature);
            llm.setTemperature(temperature);
        }
        if (updates.containsKey("maxTokens")) {
            Integer maxTokens = updates.getInteger("maxTokens");
            SettingsValidator.LLMValidation.validateMaxTokens(maxTokens);
            llm.setMaxTokens(maxTokens);
        }
        if (updates.containsKey("topP")) {
            Double topP = updates.getDouble("topP");
            SettingsValidator.LLMValidation.validateTopP(topP);
            llm.setTopP(topP);
        }
        if (updates.containsKey("topK")) {
            Integer topK = updates.getInteger("topK");
            SettingsValidator.LLMValidation.validateTopK(topK);
            llm.setTopK(topK);
        }
        if (updates.containsKey("repeatPenalty")) {
            Double repeatPenalty = updates.getDouble("repeatPenalty");
            SettingsValidator.LLMValidation.validateRepeatPenalty(repeatPenalty);
            llm.setRepeatPenalty(repeatPenalty);
        }
        if (updates.containsKey("presencePenalty")) {
            Double presencePenalty = updates.getDouble("presencePenalty");
            SettingsValidator.LLMValidation.validatePresencePenalty(presencePenalty);
            llm.setPresencePenalty(presencePenalty);
        }
        if (updates.containsKey("frequencyPenalty")) {
            Double frequencyPenalty = updates.getDouble("frequencyPenalty");
            SettingsValidator.LLMValidation.validateFrequencyPenalty(frequencyPenalty);
            llm.setFrequencyPenalty(frequencyPenalty);
        }
        if (updates.containsKey("minP")) {
            Double minP = updates.getDouble("minP");
            SettingsValidator.LLMValidation.validateMinP(minP);
            llm.setMinP(minP);
        }
        if (updates.containsKey("tfsZ")) {
            Double tfsZ = updates.getDouble("tfsZ");
            SettingsValidator.LLMValidation.validateTfsZ(tfsZ);
            llm.setTfsZ(tfsZ);
        }
        if (updates.containsKey("typicalP")) {
            Double typicalP = updates.getDouble("typicalP");
            SettingsValidator.LLMValidation.validateTypicalP(typicalP);
            llm.setTypicalP(typicalP);
        }
        if (updates.containsKey("seed")) {
            llm.setSeed(updates.getInteger("seed"));
        }
        if (updates.containsKey("stopSequences")) {
            io.vertx.core.json.JsonArray arr = updates.getJsonArray("stopSequences");
            if (arr != null) {
                llm.setStopSequences(arr.getList());
            } else {
                llm.setStopSequences(new java.util.ArrayList<>());
            }
        }
        if (updates.containsKey("nPredict")) {
            Integer nPredict = updates.getInteger("nPredict");
            SettingsValidator.LLMValidation.validateNPredict(nPredict);
            llm.setNPredict(nPredict);
        }
        if (updates.containsKey("nCtx")) {
            Integer nCtx = updates.getInteger("nCtx");
            SettingsValidator.LLMValidation.validateNCtx(nCtx);
            llm.setNCtx(nCtx);
        }
        if (updates.containsKey("contextSize")) {
            Integer contextSize = updates.getInteger("contextSize");
            SettingsValidator.LLMValidation.validateNCtx(contextSize);
            llm.setContextSize(contextSize);
        }
        if (updates.containsKey("nBatch")) {
            Integer nBatch = updates.getInteger("nBatch");
            SettingsValidator.LLMValidation.validateNBatch(nBatch);
            llm.setNBatch(nBatch);
        }
        if (updates.containsKey("nThreads")) {
            Integer nThreads = updates.getInteger("nThreads");
            SettingsValidator.LLMValidation.validateNThreads(nThreads);
            llm.setNThreads(nThreads);
        }
        if (updates.containsKey("nGpuLayers")) {
            Integer nGpuLayers = updates.getInteger("nGpuLayers");
            SettingsValidator.LLMValidation.validateNGpuLayers(nGpuLayers);
            llm.setNGpuLayers(nGpuLayers);
        }
        if (updates.containsKey("mainGpu")) {
            Integer mainGpu = updates.getInteger("mainGpu");
            SettingsValidator.LLMValidation.validateMainGpu(mainGpu);
            llm.setMainGpu(mainGpu);
        }
        if (updates.containsKey("promptTemplate")) {
            String template = updates.getString("promptTemplate");
            SettingsValidator.LLMValidation.validatePromptTemplate(template);
            llm.setPromptTemplate(template);
        }
        if (updates.containsKey("systemPrompt")) {
            llm.setSystemPrompt(updates.getString("systemPrompt"));
        }
        if (updates.containsKey("chatFormat")) {
            llm.setChatFormat(updates.getString("chatFormat"));
        }
        if (updates.containsKey("streamingEnabled")) {
            llm.setStream(updates.getBoolean("streamingEnabled"));
        }
        if (updates.containsKey("stream")) {
            llm.setStream(updates.getBoolean("stream"));
        }
        if (updates.containsKey("mirostat")) {
            Integer mirostat = updates.getInteger("mirostat");
            SettingsValidator.LLMValidation.validateMirostat(mirostat);
            llm.setMirostat(mirostat);
        }
        if (updates.containsKey("mirostatTau")) {
            Double mirostatTau = updates.getDouble("mirostatTau");
            SettingsValidator.LLMValidation.validateMirostatTau(mirostatTau);
            llm.setMirostatTau(mirostatTau);
        }
        if (updates.containsKey("mirostatEta")) {
            Double mirostatEta = updates.getDouble("mirostatEta");
            SettingsValidator.LLMValidation.validateMirostatEta(mirostatEta);
            llm.setMirostatEta(mirostatEta);
        }
    }

    private void applyUiUpdates(Settings.UISettings ui, JsonObject updates) {
        if (updates.containsKey("theme")) {
            String theme = updates.getString("theme");
            SettingsValidator.UIValidation.validateTheme(theme);
            ui.setTheme(theme);
        }
        if (updates.containsKey("fontSize")) {
            String fontSize = updates.getString("fontSize");
            SettingsValidator.UIValidation.validateFontSize(fontSize);
            ui.setFontSize(fontSize);
        }
        if (updates.containsKey("fontFamily")) {
            ui.setFontFamily(updates.getString("fontFamily"));
        }
        if (updates.containsKey("compactMode")) {
            ui.setCompactMode(updates.getBoolean("compactMode"));
        }
        if (updates.containsKey("showTimestamps")) {
            ui.setShowTimestamps(updates.getBoolean("showTimestamps"));
        }
        if (updates.containsKey("showTokenCount")) {
            ui.setShowTokenCount(updates.getBoolean("showTokenCount"));
        }
        if (updates.containsKey("showModelInfo")) {
            ui.setShowModelInfo(updates.getBoolean("showModelInfo"));
        }
        if (updates.containsKey("enterToSend")) {
            ui.setEnterToSend(updates.getBoolean("enterToSend"));
        }
        if (updates.containsKey("soundEnabled")) {
            ui.setSoundEnabled(updates.getBoolean("soundEnabled"));
        }
        if (updates.containsKey("notificationsEnabled")) {
            ui.setNotificationsEnabled(updates.getBoolean("notificationsEnabled"));
        }
        if (updates.containsKey("autoScroll")) {
            ui.setAutoScroll(updates.getBoolean("autoScroll"));
        }
        if (updates.containsKey("messageGrouping")) {
            ui.setMessageGrouping(updates.getBoolean("messageGrouping"));
        }
        if (updates.containsKey("syntaxHighlighting")) {
            ui.setSyntaxHighlighting(updates.getBoolean("syntaxHighlighting"));
        }
        if (updates.containsKey("markdownRendering")) {
            ui.setMarkdownRendering(updates.getBoolean("markdownRendering"));
        }
        if (updates.containsKey("emojiPicker")) {
            ui.setEmojiPicker(updates.getBoolean("emojiPicker"));
        }
        if (updates.containsKey("spellCheck")) {
            ui.setSpellCheck(updates.getBoolean("spellCheck"));
        }
        if (updates.containsKey("autoSave")) {
            ui.setAutoSave(updates.getBoolean("autoSave"));
        }
        if (updates.containsKey("autoSaveInterval")) {
            Integer interval = updates.getInteger("autoSaveInterval");
            SettingsValidator.UIValidation.validateAutoSaveInterval(interval);
            ui.setAutoSaveInterval(interval);
        }
        if (updates.containsKey("sidebarPosition")) {
            String position = updates.getString("sidebarPosition");
            SettingsValidator.UIValidation.validateSidebarPosition(position);
            ui.setSidebarPosition(position);
        }
        if (updates.containsKey("sidebarCollapsed")) {
            ui.setSidebarCollapsed(updates.getBoolean("sidebarCollapsed"));
        }
        if (updates.containsKey("messageLayout")) {
            String layout = updates.getString("messageLayout");
            SettingsValidator.UIValidation.validateMessageLayout(layout);
            ui.setMessageLayout(layout);
        }
        if (updates.containsKey("language")) {
            ui.setLanguage(updates.getString("language"));
        }
        if (updates.containsKey("codeTheme")) {
            ui.setCodeTheme(updates.getString("codeTheme"));
        }
    }

    private void applyNotificationUpdates(Settings.NotificationSettings notifications, JsonObject updates) {
        if (updates.containsKey("desktop")) {
            notifications.setDesktop(updates.getBoolean("desktop"));
        }
        if (updates.containsKey("enabled")) {
            notifications.setEnabled(updates.getBoolean("enabled"));
        }
        if (updates.containsKey("sound")) {
            notifications.setSound(updates.getBoolean("sound"));
        }
        if (updates.containsKey("vibration")) {
            notifications.setVibration(updates.getBoolean("vibration"));
        }
        if (updates.containsKey("mentions")) {
            notifications.setMentions(updates.getBoolean("mentions"));
        }
        if (updates.containsKey("replies")) {
            notifications.setReplies(updates.getBoolean("replies"));
        }
        if (updates.containsKey("newConversations")) {
            notifications.setNewConversations(updates.getBoolean("newConversations"));
        }
        if (updates.containsKey("systemAlerts")) {
            notifications.setSystemAlerts(updates.getBoolean("systemAlerts"));
        }
        if (updates.containsKey("schedule")) {
            String schedule = updates.getString("schedule");
            SettingsValidator.NotificationValidation.validateSchedule(schedule);
            notifications.setSchedule(schedule);
        }
        if (updates.containsKey("messageAlerts")) {
            notifications.setMessageAlerts(updates.getBoolean("messageAlerts"));
        }

        if (updates.containsKey("customSchedule")) {
            JsonObject schedule = updates.getJsonObject("customSchedule");

            // Null check - customSchedule might be explicitly set to null to clear it
            if (schedule != null) {
                Settings.NotificationSchedule customSchedule = new Settings.NotificationSchedule();

                String startTime = null;
                String endTime = null;

                if (schedule.containsKey("startTime")) {
                    startTime = schedule.getString("startTime");
                    SettingsValidator.NotificationValidation.validateTime(startTime, "startTime");
                    customSchedule.setStartTime(startTime);
                }
                if (schedule.containsKey("endTime")) {
                    endTime = schedule.getString("endTime");
                    SettingsValidator.NotificationValidation.validateTime(endTime, "endTime");
                    customSchedule.setEndTime(endTime);
                }

                // Validate that endTime is after startTime
                if (startTime != null && endTime != null) {
                    SettingsValidator.NotificationValidation.validateScheduleTimes(startTime, endTime);
                }

                if (schedule.containsKey("daysOfWeek")) {
                    // GraphQL numbers arrive as BigDecimal, so a direct
                    // JsonArray.getList() cast to Integer fails at runtime.
                    List<Integer> daysOfWeek = schedule.getJsonArray("daysOfWeek").stream()
                            .filter(Number.class::isInstance)
                            .map(value -> ((Number) value).intValue())
                            .toList();
                    SettingsValidator.NotificationValidation.validateDaysOfWeek(daysOfWeek);
                    customSchedule.setDaysOfWeek(daysOfWeek);
                }

                notifications.setCustomSchedule(customSchedule);
            } else {
                // Explicitly set to null - clear custom schedule
                notifications.setCustomSchedule(null);
            }
        }
    }

    private void applyPrivacyUpdates(Settings.PrivacySettings privacy, JsonObject updates) {
        // Check for unimplemented features first
        Boolean encryptMessages = updates.containsKey("encryptMessages") ? updates.getBoolean("encryptMessages") : null;
        Boolean saveHistory = updates.containsKey("saveHistory") ? updates.getBoolean("saveHistory") : null;
        SettingsValidator.PrivacyValidation.validateUnimplementedFeatures(encryptMessages, saveHistory);

        if (updates.containsKey("shareAnalytics")) {
            privacy.setShareAnalytics(updates.getBoolean("shareAnalytics"));
        }
        if (updates.containsKey("analytics")) {
            privacy.setAnalytics(updates.getBoolean("analytics"));
        }
        if (updates.containsKey("shareData")) {
            privacy.setShareData(updates.getBoolean("shareData"));
        }
        if (updates.containsKey("saveHistory")) {
            privacy.setSaveHistory(updates.getBoolean("saveHistory"));
        }
        if (updates.containsKey("encryptMessages")) {
            // Validation above ensures this is false
            privacy.setEncryptMessages(updates.getBoolean("encryptMessages"));
        }
        if (updates.containsKey("clearOnLogout")) {
            privacy.setClearOnLogout(updates.getBoolean("clearOnLogout"));
        }
        if (updates.containsKey("exportFormat")) {
            String format = updates.getString("exportFormat");
            SettingsValidator.PrivacyValidation.validateExportFormat(format);
            privacy.setExportFormat(format);
        }
        if (updates.containsKey("dataRetentionDays")) {
            Integer days = updates.getInteger("dataRetentionDays");
            SettingsValidator.PrivacyValidation.validateDataRetentionDays(days);
            privacy.setDataRetentionDays(days);
        }
    }

    private void applyKeyboardUpdates(Settings.KeyboardSettings keyboard, JsonObject updates) {
        if (updates.containsKey("shortcuts")) {
            Object shortcutsObj = updates.getValue("shortcuts");
            java.util.Map<String, String> stringMap = new java.util.HashMap<>();

            if (shortcutsObj instanceof JsonArray) {
                // Wire format from KeyboardSettingsInput.shortcuts: List<ShortcutEntry>
                // Each element is a {key, value} object.
                JsonArray arr = (JsonArray) shortcutsObj;
                for (int i = 0; i < arr.size(); i++) {
                    Object elem = arr.getValue(i);
                    if (!(elem instanceof JsonObject)) continue;
                    JsonObject entry = (JsonObject) elem;
                    String key = entry.getString("key");
                    String value = entry.getString("value");
                    if (key == null || value == null) continue;
                    SettingsValidator.KeyboardValidation.validateKeyCombo(value, key);
                    stringMap.put(key, value);
                }
            } else if (shortcutsObj instanceof JsonObject) {
                // Legacy format: bare object { keyName: "Ctrl+K", ... }
                java.util.Map<String, Object> rawMap = ((JsonObject) shortcutsObj).getMap();
                rawMap.forEach((key, value) -> {
                    if (value != null) {
                        String combo = value.toString();
                        SettingsValidator.KeyboardValidation.validateKeyCombo(combo, key);
                        stringMap.put(key, combo);
                    }
                });
            } else if (shortcutsObj instanceof java.util.List) {
                // Already-deserialized list of entries (shouldn't normally hit this
                // path since JsonObject.mapFrom returns JsonArray, but defensive).
                java.util.List<?> list = (java.util.List<?>) shortcutsObj;
                for (Object elem : list) {
                    if (!(elem instanceof java.util.Map)) continue;
                    java.util.Map<?, ?> entry = (java.util.Map<?, ?>) elem;
                    Object keyObj = entry.get("key");
                    Object valueObj = entry.get("value");
                    if (keyObj == null || valueObj == null) continue;
                    String key = keyObj.toString();
                    String value = valueObj.toString();
                    SettingsValidator.KeyboardValidation.validateKeyCombo(value, key);
                    stringMap.put(key, value);
                }
            } else if (shortcutsObj instanceof java.util.Map) {
                java.util.Map<?, ?> rawMap = (java.util.Map<?, ?>) shortcutsObj;
                rawMap.forEach((key, value) -> {
                    if (key != null && value != null) {
                        String combo = value.toString();
                        SettingsValidator.KeyboardValidation.validateKeyCombo(combo, key.toString());
                        stringMap.put(key.toString(), combo);
                    }
                });
            }

            // Check for duplicate shortcuts
            SettingsValidator.KeyboardValidation.validateNoConflicts(stringMap);

            keyboard.setShortcuts(stringMap);
        }
    }

    /**
     * Map database row to Settings object.
     */
    private Settings mapRowToSettings(Row row) {
        try {
            Settings settings = new Settings();
            settings.setSettingsId(row.getString("settingsId"));
            settings.setUserId(row.getString("userId"));

            // Parse JSON columns
            String llmJson = ServiceHelper.safeGetJsonText(row, "llmSettings");
            String uiJson = ServiceHelper.safeGetJsonText(row, "uiSettings");
            String notificationJson = ServiceHelper.safeGetJsonText(row, "notificationSettings");
            String privacyJson = ServiceHelper.safeGetJsonText(row, "privacySettings");
            String keyboardJson = ServiceHelper.safeGetJsonText(row, "keyboardSettings");

            if (llmJson != null) {
                settings.setLlm(gson.fromJson(llmJson, Settings.LLMSettings.class));
            }
            if (uiJson != null) {
                settings.setUi(gson.fromJson(uiJson, Settings.UISettings.class));
            }
            if (notificationJson != null) {
                settings.setNotifications(gson.fromJson(notificationJson, Settings.NotificationSettings.class));
            }
            if (privacyJson != null) {
                settings.setPrivacy(gson.fromJson(privacyJson, Settings.PrivacySettings.class));
            }
            if (keyboardJson != null) {
                settings.setKeyboard(gson.fromJson(keyboardJson, Settings.KeyboardSettings.class));
            }

            settings.setCreated(Time4JHelper.localDateTimeToString(row.getLocalDateTime("created")));
            settings.setUpdated(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updated")));

            // Ensure all nested objects have default values (Gson doesn't apply @Builder.Default)
            ensureDefaults(settings);

            return settings;
        } catch (JsonSyntaxException e) {
            log.error("Failed to map row to settings: {}", e.getMessage());
            throw new RuntimeException("Failed to parse settings from database", e);
        }
    }

    /**
     * Ensure all settings have default values for null fields.
     * This is needed because Gson doesn't apply @Builder.Default annotations.
     */
    private void ensureDefaults(Settings settings) {
        // Ensure top-level objects exist
        if (settings.getLlm() == null) {
            settings.setLlm(new Settings.LLMSettings());
        }
        if (settings.getUi() == null) {
            settings.setUi(new Settings.UISettings());
        }
        if (settings.getNotifications() == null) {
            settings.setNotifications(new Settings.NotificationSettings());
        }
        if (settings.getPrivacy() == null) {
            settings.setPrivacy(new Settings.PrivacySettings());
        }
        if (settings.getKeyboard() == null) {
            settings.setKeyboard(new Settings.KeyboardSettings());
        }

        // Ensure LLM settings defaults
        Settings.LLMSettings llm = settings.getLlm();
        if (llm.getTemperature() == null) llm.setTemperature(0.7);
        if (llm.getMaxTokens() == null) llm.setMaxTokens(-1);
        if (llm.getTopP() == null) llm.setTopP(0.9);
        if (llm.getTopK() == null) llm.setTopK(40);
        if (llm.getRepeatPenalty() == null) llm.setRepeatPenalty(1.1);
        if (llm.getPresencePenalty() == null) llm.setPresencePenalty(0.0);
        if (llm.getFrequencyPenalty() == null) llm.setFrequencyPenalty(0.0);
        if (llm.getMinP() == null) llm.setMinP(0.05);
        if (llm.getTfsZ() == null) llm.setTfsZ(1.0);
        if (llm.getTypicalP() == null) llm.setTypicalP(1.0);
        if (llm.getStopSequences() == null) llm.setStopSequences(new java.util.ArrayList<>());
        if (llm.getNPredict() == null) llm.setNPredict(512);
        if (llm.getNCtx() == null) llm.setNCtx(4096);
        if (llm.getNBatch() == null) llm.setNBatch(512);
        if (llm.getNThreads() == null) llm.setNThreads(8);
        if (llm.getNGpuLayers() == null) llm.setNGpuLayers(32);
        if (llm.getMainGpu() == null) llm.setMainGpu(0);
        if (llm.getPromptTemplate() == null) llm.setPromptTemplate("chatml");
        if (llm.getChatFormat() == null) llm.setChatFormat("chatml");
        if (llm.getStream() == null) llm.setStream(true);
        if (llm.getMirostat() == null) llm.setMirostat(0);
        if (llm.getMirostatTau() == null) llm.setMirostatTau(5.0);
        if (llm.getMirostatEta() == null) llm.setMirostatEta(0.1);

        // Ensure UI settings defaults
        Settings.UISettings ui = settings.getUi();
        if (ui.getTheme() == null) ui.setTheme("dark");
        if (ui.getFontSize() == null) ui.setFontSize("medium");
        if (ui.getFontFamily() == null) ui.setFontFamily("Inter");
        if (ui.getCompactMode() == null) ui.setCompactMode(false);
        if (ui.getShowTimestamps() == null) ui.setShowTimestamps(true);
        if (ui.getShowTokenCount() == null) ui.setShowTokenCount(true);
        if (ui.getShowModelInfo() == null) ui.setShowModelInfo(true);
        if (ui.getEnterToSend() == null) ui.setEnterToSend(true);
        if (ui.getSoundEnabled() == null) ui.setSoundEnabled(false);
        if (ui.getNotificationsEnabled() == null) ui.setNotificationsEnabled(true);
        if (ui.getAutoScroll() == null) ui.setAutoScroll(true);
        if (ui.getMessageGrouping() == null) ui.setMessageGrouping(true);
        if (ui.getSyntaxHighlighting() == null) ui.setSyntaxHighlighting(true);
        if (ui.getMarkdownRendering() == null) ui.setMarkdownRendering(true);
        if (ui.getEmojiPicker() == null) ui.setEmojiPicker(true);
        if (ui.getSpellCheck() == null) ui.setSpellCheck(true);
        if (ui.getAutoSave() == null) ui.setAutoSave(true);
        if (ui.getAutoSaveInterval() == null) ui.setAutoSaveInterval(30);
        if (ui.getSidebarPosition() == null) ui.setSidebarPosition("left");
        if (ui.getSidebarCollapsed() == null) ui.setSidebarCollapsed(false);
        if (ui.getMessageLayout() == null) ui.setMessageLayout("bubble");
        if (ui.getLanguage() == null) ui.setLanguage("en");

        // Ensure Notification settings defaults
        Settings.NotificationSettings notifications = settings.getNotifications();
        if (notifications.getDesktop() == null) notifications.setDesktop(true);
        if (notifications.getSound() == null) notifications.setSound(false);
        if (notifications.getVibration() == null) notifications.setVibration(false);
        if (notifications.getMentions() == null) notifications.setMentions(true);
        if (notifications.getReplies() == null) notifications.setReplies(true);
        if (notifications.getNewConversations() == null) notifications.setNewConversations(false);
        if (notifications.getSystemAlerts() == null) notifications.setSystemAlerts(true);
        if (notifications.getSchedule() == null) notifications.setSchedule("always");

        // Ensure Privacy settings defaults
        Settings.PrivacySettings privacy = settings.getPrivacy();
        if (privacy.getShareAnalytics() == null) privacy.setShareAnalytics(false);
        if (privacy.getSaveHistory() == null) privacy.setSaveHistory(true);
        if (privacy.getEncryptMessages() == null) privacy.setEncryptMessages(false);
        if (privacy.getClearOnLogout() == null) privacy.setClearOnLogout(false);
        if (privacy.getExportFormat() == null) privacy.setExportFormat("json");
        if (privacy.getDataRetentionDays() == null) privacy.setDataRetentionDays(30);

        // Ensure Keyboard settings defaults
        Settings.KeyboardSettings keyboard = settings.getKeyboard();
        if (keyboard.getShortcuts() == null) {
            keyboard.setShortcuts(Settings.KeyboardSettings.createDefaultShortcuts());
        }
    }

    /**
     * Get cached settings.
     */
    private Uni<Settings> getCachedSettings(String cacheKey) {

        return vertx.eventBus().<JsonObject>request("redis.cache.get", new JsonObject().put("key", cacheKey))
            .map(reply -> {
                JsonObject result = (JsonObject) reply.body();
                if (result != null && result.getValue("data") != null) {
                    try {
                        JsonObject data = result.getJsonObject("data");
                        Settings settings = gson.fromJson(data.encode(), Settings.class);
                        // Ensure defaults are applied (Gson doesn't apply @Builder.Default)
                        ensureDefaults(settings);
                        return settings;
                    } catch (JsonSyntaxException e) {
                        log.warn("Failed to deserialize cached settings: {}", e.getMessage());
                        return (Settings) null;
                    }
                } else {
                    return (Settings) null;
                }
            })
            .onFailure().recoverWithItem(error -> {
                log.warn("Failed to get cached settings: {}", error.getMessage());
                return null;
            });
    }

    /**
     * Cache settings.
     */
    private void cacheSettings(String cacheKey, Settings settings) {
        String json = gson.toJson(settings);
        vertx.eventBus().send("redis.cache.set", new JsonObject()
            .put("key", cacheKey)
            .put("data", new JsonObject(json))
            .put("ttl", CACHE_TTL));
    }

    /**
     * Invalidate settings cache.
     */
    private void invalidateSettingsCache(String userId) {
        String pattern = "settings:*:" + userId;
        vertx.eventBus().send("redis.cache.invalidate", new JsonObject()
            .put("pattern", pattern));
    }

    /**
     * Publish settings event to event bus.
     */
    private void publishSettingsEvent(String eventType, String userId, JsonObject data) {
        JsonObject event = new JsonObject()
            .put("type", eventType)
            .put("userId", userId)
            .put("timestamp", LocalDateTime.now(ZoneOffset.UTC).toString());

        if (data != null) {
            event.put("data", data);
        }

        // Publish to Redis pub/sub
        vertx.eventBus().send("redis.publish", new JsonObject()
            .put("channel", "settings:events")
            .put("message", event.encode()));

        // Also publish to EventBus for local subscribers
        vertx.eventBus().publish("settings:updates", event);
    }
}
