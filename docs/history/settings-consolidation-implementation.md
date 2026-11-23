> **LEGACY DOCUMENTATION** — This is an implementation guide from the settings consolidation effort (2026-01-14). The consolidated settings system is live. For the current implementation, see `SettingsService.java`, `Settings.java`, and `frontend/src/stores/settingsStore.ts`. Retained for historical reference only.

# Settings Consolidation Implementation Guide

## Overview
This document describes the complete consolidation of settings from the old fragmented approach (using `user` and `user_settings` tables) to a new JSON-based approach using the `settings` table.

## Changes Made

### 1. Database Schema

**Existing `settings` table** (already in schema.sql):
```sql
CREATE TABLE settings (
    settingsId VARCHAR(36) PRIMARY KEY,
    userId VARCHAR(36) NOT NULL UNIQUE,
    llmSettings JSON,
    uiSettings JSON,
    notificationSettings JSON,
    privacySettings JSON,
    keyboardSettings JSON,
    created DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

**Migration Script**: `backend/scripts/migrate-settings-to-json.sql`
- Migrates all existing settings from `user` + `user_settings` tables to `settings` table
- Preserves all user preferences
- Uses INSERT IGNORE to avoid overwriting existing settings

### 2. Backend Models

**New Model**: `backend/src/main/java/com/eonmux/chatllm/models/Settings.java`
- Main `Settings` class with nested classes for each category
- `LLMSettings` - All 27 LLM parameters
- `UISettings` - All 21 UI preferences
- `NotificationSettings` - All 9+ notification preferences with schedule support
- `PrivacySettings` - All 6 privacy preferences
- `KeyboardSettings` - Keyboard shortcuts as Map
- Includes legacy field compatibility methods

**Preserved**: `backend/src/main/java/com/eonmux/chatllm/models/UserSettings.java`
- Kept for backward compatibility with existing code
- Will be deprecated gradually

### 3. Backend Services

**New Service**: `backend/src/main/java/com/eonmux/chatllm/services/SettingsServiceNew.java`
- Full JSON-based CRUD operations
- Redis caching with 5-minute TTL
- EventBus event publishing
- Supports partial updates (merge semantics)
- Export/import functionality
- Methods:
  - `getUserSettings(userId)` - Get settings with cache
  - `updateSettings(userId, updates)` - Partial or full update
  - `resetToDefaults(userId)` - Reset to defaults
  - `exportSettings(userId)` - Export as JSON
  - `importSettings(userId, data)` - Import from JSON

**Preserved**: `backend/src/main/java/com/eonmux/chatllm/services/SettingsService.java`
- Old service kept for backward compatibility
- Should be deprecated in future release

### 4. GraphQL Schema Updates

**File**: `backend/src/main/resources/schema.graphqls`

**Enhanced Types**:
```graphql
type LLMSettings {
    # Now includes ALL 27 fields from frontend
    model: String
    temperature: Float!
    maxTokens: Int!
    topP: Float!
    topK: Int!
    repeatPenalty: Float!
    presencePenalty: Float!
    frequencyPenalty: Float!
    minP: Float!
    tfsZ: Float!
    typicalP: Float!
    seed: Int
    stopSequences: [String!]!
    nPredict: Int!
    nCtx: Int!
    nBatch: Int!
    nThreads: Int!
    nGpuLayers: Int!
    mainGpu: Int!
    promptTemplate: String!
    systemPrompt: String
    chatFormat: String!
    streamingEnabled: Boolean!
    mirostat: Int!
    mirostatTau: Float!
    mirostatEta: Float!
    # Legacy fields for compatibility
    contextSize: Int!
    templates: [String!]!
    currentTemplate: String!
}

type UISettings {
    # Now includes ALL 21 fields from frontend
    theme: String!
    fontSize: String!
    fontFamily: String!
    compactMode: Boolean!
    showTimestamps: Boolean!
    showTokenCount: Boolean!
    showModelInfo: Boolean!
    enterToSend: Boolean!
    soundEnabled: Boolean!
    notificationsEnabled: Boolean!
    autoScroll: Boolean!
    messageGrouping: Boolean!
    syntaxHighlighting: Boolean!
    markdownRendering: Boolean!
    emojiPicker: Boolean!
    spellCheck: Boolean!
    autoSave: Boolean!
    autoSaveInterval: Int!
    sidebarPosition: String!
    sidebarCollapsed: Boolean!
    messageLayout: String!
    # Legacy fields
    language: String!
    codeTheme: String!
    editorTheme: String!
    showLineNumbers: Boolean!
    wordWrap: Boolean!
}

type NotificationSettings {
    # Now includes schedule support
    desktop: Boolean!
    sound: Boolean!
    vibration: Boolean!
    mentions: Boolean!
    replies: Boolean!
    newConversations: Boolean!
    systemAlerts: Boolean!
    schedule: String!
    customSchedule: NotificationSchedule
    # Legacy fields
    enabled: Boolean!
    email: Boolean!
    messageAlerts: Boolean!
}

type NotificationSchedule {
    startTime: String!
    endTime: String!
    daysOfWeek: [Int!]!
}

type PrivacySettings {
    # Now includes all frontend fields
    shareAnalytics: Boolean!
    saveHistory: Boolean!
    encryptMessages: Boolean!
    clearOnLogout: Boolean!
    exportFormat: String!
    dataRetentionDays: Int!
    # Legacy fields
    analytics: Boolean!
    shareData: Boolean!
    publicProfile: Boolean!
    showOnlineStatus: Boolean!
}
```

**Input types** similarly expanded to match all fields.

### 5. GraphQL Resolvers (TO BE UPDATED)

**QueryResolver** needs:
```java
public CompletableFuture<Map<String, Object>> settings(DataFetchingEnvironment env) {
    AuthContext auth = getAuthContext(env);
    String userId = auth.getUserId().toString();

    // Use SettingsServiceNew
    return settingsServiceNew.getUserSettings(userId)
        .map(settings -> convertSettingsToMap(settings))
        .toCompletionStage()
        .toCompletableFuture();
}
```

**MutationResolver** needs:
- Update `updateSettings()` to use SettingsServiceNew
- Update `resetSettings()` to use SettingsServiceNew
- Update `importSettings()` to use SettingsServiceNew

### 6. Frontend Changes (TO BE MADE)

**File**: `frontend/src/stores/settingsStore.ts`

**Changes needed**:
1. Remove `chatllm-extended-settings` localStorage reads (lines 274-360)
2. Remove localStorage writes in saveSettings() (lines 464-470)
3. Trust GraphQL API to return all settings
4. Simplify fetchSettings() to just query GraphQL
5. Simplify saveSettings() to just send to GraphQL

**Before**:
```typescript
// Load extended settings from localStorage first
const extendedSettings = getStoredItem('chatllm-extended-settings');
// ... complex merge logic ...
// Also save extended settings to localStorage (not supported by backend)
setStoredItem('chatllm-extended-settings', {...});
```

**After**:
```typescript
// Simply fetch from GraphQL - it now has everything
const { data } = await apolloClient.query({
  query: GET_SETTINGS_QUERY,
  fetchPolicy: 'no-cache',
});

set((state) => {
  state.llmSettings = data.settings.llm;
  state.uiSettings = data.settings.ui;
  state.privacySettings = data.settings.privacy;
  state.notificationSettings = data.settings.notifications;
  state.keyboardShortcuts = data.settings.keyboard.shortcuts;
});
```

### 7. Database Cleanup (TO BE DONE)

**File**: `backend/src/main/resources/db/schema.sql`

**Remove from `user` table**:
- theme, language, fontSize, codeTheme (moved to settings.uiSettings)
- notifications, sounds, desktopNotifications, emailNotifications (moved to settings.notificationSettings)
- messageAlerts, systemAlerts (moved to settings.notificationSettings)
- analytics, improvement (moved to settings.privacySettings)
- keyboardShortcuts (moved to settings.keyboardSettings)
- preferredModelPath, preferredGpuLayers (moved to settings.llmSettings)

**Consider removing entire `user_settings` table**:
- All fields now in settings.llmSettings JSON column
- Can be fully deprecated after migration

## Implementation Steps

### Step 1: Run Migration (MANUAL - PRODUCTION ONLY)
```bash
cd backend/scripts
mysql -u root -p chatllm_db < migrate-settings-to-json.sql
```

### Step 2: Update Application Code (IN PROGRESS)
- [x] Create Settings model
- [x] Create SettingsServiceNew
- [x] Expand GraphQL schema
- [ ] Add QueryResolver.settings() method
- [ ] Update MutationResolver.updateSettings()
- [ ] Update MutationResolver.resetSettings()
- [ ] Update MutationResolver.importSettings()

### Step 3: Update Frontend (PENDING)
- [ ] Remove localStorage workarounds
- [ ] Simplify fetchSettings()
- [ ] Simplify saveSettings()
- [ ] Test all settings categories

### Step 4: Clean Database Schema (PENDING)
- [ ] Remove redundant user table columns
- [ ] Add migration script to preserve user table data
- [ ] Consider deprecating user_settings table

### Step 5: Testing (PENDING)
- [ ] Test settings save/load for all categories
- [ ] Test settings reset
- [ ] Test settings export/import
- [ ] Test settings caching
- [ ] Test across multiple browsers (no localStorage required)
- [ ] Test migration script with sample data

## Benefits

1. **Single Source of Truth**: All settings in one table
2. **Flexible Schema**: JSON columns allow adding fields without migrations
3. **Complete Persistence**: All 80+ frontend settings saved server-side
4. **Cross-Device Sync**: No localStorage dependencies
5. **Cleaner Code**: Eliminates complex merge logic
6. **Better Performance**: Single query instead of JOINs
7. **Easier Maintenance**: One model, one service, one table

## Backward Compatibility

- Legacy field names maintained with getters/setters
- Old UserSettings model preserved (deprecated)
- Old SettingsService preserved (deprecated)
- GraphQL schema includes deprecated fields marked with comments
- Migration script preserves all existing user preferences

## Rollback Plan

If issues arise:
1. Old `user` and `user_settings` tables are not modified by default
2. Can revert GraphQL resolvers to use old tables
3. Can re-enable localStorage fallback in frontend
4. Settings table can be dropped if needed

## Timeline Estimate

- Backend updates: 2-4 hours
- Frontend updates: 1-2 hours
- Testing: 2-3 hours
- Total: 5-9 hours

## Notes

- Presets functionality remains localStorage-only (not in scope)
- User-specific settings are fully preserved - each user has their own settings record
- Global system settings (if needed) would be a separate table/mechanism
- Consider adding settings versioning in future for migrations
