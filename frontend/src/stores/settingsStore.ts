/**
 * Settings Store
 *
 * Manages application settings including LLM, UI, privacy, and notification settings.
 * Uses centralized GraphQL operations from @graphql module.
 */

import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { immer } from 'zustand/middleware/immer';
import { apolloClient } from '@/lib/apollo-client';
import { devtoolsInDev, setLoading, clearLoadingAndError } from '@/lib/storeHelpers';
import {
  GET_SETTINGS_QUERY,
  UPDATE_SETTINGS_MUTATION,
  RESET_SETTINGS_MUTATION,
} from '@/graphql/operations';
import { getStoredItem } from '@/lib/storageHelper';

interface LLMSettings {
  model?: string;
  temperature: number;
  maxTokens: number;
  topP: number;
  topK: number;
  repeatPenalty: number;
  presencePenalty: number;
  frequencyPenalty: number;
  minP: number;
  tfsZ: number;
  typicalP: number;
  seed?: number;
  stopSequences: string[];
  nPredict: number;
  nCtx: number;
  nBatch: number;
  nThreads: number;
  nGpuLayers: number;
  mainGpu: number;
  promptTemplate: string;
  systemPrompt?: string;
  chatFormat: string;
  stream: boolean;
  mirostat: number;
  mirostatTau: number;
  mirostatEta: number;
  contextSize: number;
  templates: string[];
  currentTemplate: string;
}

interface UISettings {
  theme: 'light' | 'dark' | 'system' | 'auto';
  fontSize: 'small' | 'medium' | 'large';
  fontFamily: string;
  compactMode: boolean;
  showTimestamps: boolean;
  showTokenCount: boolean;
  showModelInfo: boolean;
  enterToSend: boolean;
  soundEnabled: boolean;
  notificationsEnabled: boolean;
  autoScroll: boolean;
  messageGrouping: boolean;
  syntaxHighlighting: boolean;
  markdownRendering: boolean;
  emojiPicker: boolean;
  spellCheck: boolean;
  autoSave: boolean;
  autoSaveInterval: number;
  sidebarPosition: 'left' | 'right';
  sidebarCollapsed: boolean;
  messageLayout: 'bubble' | 'standard';
  language: string;
  codeTheme: string;
  editorTheme: string;
  showLineNumbers: boolean;
  wordWrap: boolean;
}

interface PrivacySettings {
  shareAnalytics: boolean;
  saveHistory: boolean;
  encryptMessages: boolean;
  clearOnLogout: boolean;
  exportFormat: 'json' | 'markdown' | 'txt';
  dataRetentionDays: number;
  analytics: boolean;
  shareData: boolean;
  publicProfile: boolean;
  showOnlineStatus: boolean;
}

interface NotificationSettings {
  desktop: boolean;
  sound: boolean;
  vibration: boolean;
  mentions: boolean;
  replies: boolean;
  newConversations: boolean;
  systemAlerts: boolean;
  schedule: 'always' | 'daytime' | 'custom' | 'silent';
  customSchedule?: {
    startTime: string;
    endTime: string;
    daysOfWeek: number[];
  };
  enabled: boolean;
  email: boolean;
  messageAlerts: boolean;
}

interface KeyboardShortcuts {
  newConversation: string;
  searchConversations: string;
  toggleSidebar: string;
  focusInput: string;
  sendMessage: string;
  editLastMessage: string;
  deleteLastMessage: string;
  regenerateResponse: string;
  stopGeneration: string;
  toggleTheme: string;
  toggleSettings: string;
  exportChat: string;
}

interface Preset {
  id: string;
  name: string;
  description?: string;
  settings: Partial<LLMSettings>;
  isDefault: boolean;
  created: string;
  updated: string;
}

interface SettingsState {
  llmSettings: LLMSettings;
  uiSettings: UISettings;
  privacySettings: PrivacySettings;
  notificationSettings: NotificationSettings;
  keyboardShortcuts: KeyboardShortcuts;
  presets: Preset[];
  activePresetId: string | null;
  isLoading: boolean;
  error: string | null;
  hasUnsavedChanges: boolean;

  // Actions
  fetchSettings: () => Promise<void>;
  updateLLMSettings: (settings: Partial<LLMSettings>) => void;
  updateUISettings: (settings: Partial<UISettings>) => void;
  updatePrivacySettings: (settings: Partial<PrivacySettings>) => void;
  updateNotificationSettings: (settings: Partial<NotificationSettings>) => Promise<void>;
  updateKeyboardShortcuts: (shortcuts: Partial<KeyboardShortcuts>) => void;
  saveSettings: () => Promise<void>;
  resetSettings: () => Promise<void>;

  // Presets
  fetchPresets: () => Promise<void>;
  createPreset: (name: string, description?: string) => Promise<Preset | null>;
  updatePreset: (id: string, updates: Partial<Preset>) => Promise<void>;
  deletePreset: (id: string) => Promise<void>;
  applyPreset: (id: string) => void;

  // Utilities
  exportSettings: () => string;
  importSettings: (data: string) => Promise<void>;
  setHasUnsavedChanges: (hasChanges: boolean) => void;
  clearError: () => void;
}

// Note: Presets are not supported by the GraphQL schema
// Preset functionality is handled locally in the frontend only

const defaultLLMSettings: LLMSettings = {
  temperature: 0.7,
  maxTokens: -1,  // -1 means unlimited (backend converts to 1024000)
  topP: 0.9,
  topK: 40,
  repeatPenalty: 1.1,
  presencePenalty: 0,
  frequencyPenalty: 0,
  minP: 0.05,
  tfsZ: 1,
  typicalP: 1,
  stopSequences: [],
  nPredict: 512,
  nCtx: 4096,
  nBatch: 512,
  nThreads: 8,
  nGpuLayers: 32,
  mainGpu: 0,
  promptTemplate: 'chatml',
  chatFormat: 'chatml',
  stream: true,
  mirostat: 0,
  mirostatTau: 5,
  mirostatEta: 0.1,
  contextSize: 4096,
  templates: [],
  currentTemplate: '',
};

const defaultUISettings: UISettings = {
  theme: 'dark',
  fontSize: 'medium',
  fontFamily: 'Inter',
  compactMode: false,
  showTimestamps: true,
  showTokenCount: true,
  showModelInfo: true,
  enterToSend: true,
  soundEnabled: false,
  notificationsEnabled: true,
  autoScroll: true,
  messageGrouping: true,
  syntaxHighlighting: true,
  markdownRendering: true,
  emojiPicker: true,
  spellCheck: true,
  autoSave: true,
  autoSaveInterval: 30,
  sidebarPosition: 'left',
  sidebarCollapsed: false,
  messageLayout: 'bubble',
  language: 'en',
  codeTheme: 'default',
  editorTheme: 'default',
  showLineNumbers: true,
  wordWrap: true,
};

const defaultPrivacySettings: PrivacySettings = {
  shareAnalytics: false,
  saveHistory: true,
  encryptMessages: false,
  clearOnLogout: false,
  exportFormat: 'json',
  dataRetentionDays: 30,
  analytics: false,
  shareData: false,
  publicProfile: false,
  showOnlineStatus: true,
};

const defaultNotificationSettings: NotificationSettings = {
  desktop: true,
  sound: false,
  vibration: false,
  mentions: true,
  replies: true,
  newConversations: false,
  systemAlerts: true,
  schedule: 'always',
  customSchedule: {
    startTime: '09:00',
    endTime: '18:00',
    daysOfWeek: [1, 2, 3, 4, 5], // Monday to Friday
  },
  enabled: true,
  email: false,
  messageAlerts: true,
};

/**
 * Convert the wire format ([{key, value}]) from SmallRye GraphQL's Map<String,String>
 * serialization back into a plain Record<string, string>. Skips malformed entries.
 */
const shortcutEntriesToRecord = (
  entries: Array<{ key: string; value: string }> | null | undefined,
): Record<string, string> => {
  if (!entries) return {};
  const record: Record<string, string> = {};
  for (const entry of entries) {
    if (entry && typeof entry.key === 'string') {
      record[entry.key] = entry.value;
    }
  }
  return record;
};

/**
 * Inverse of {@link shortcutEntriesToRecord} — used when sending the mutation.
 * Generic over any object (KeyboardShortcuts has a fixed set of keys, so it
 * isn't structurally Record<string, string> under strict TS).
 */
const recordToShortcutEntries = <T extends object>(
  record: T,
): Array<{ key: string; value: string }> =>
  Object.entries(record).map(([key, value]) => ({ key, value: String(value) }));

const defaultKeyboardShortcuts: KeyboardShortcuts = {
  newConversation: 'Ctrl+N',
  searchConversations: 'Ctrl+K',
  toggleSidebar: 'Ctrl+B',
  focusInput: 'Ctrl+L',
  sendMessage: 'Enter',
  editLastMessage: 'Up',
  deleteLastMessage: 'Ctrl+D',
  regenerateResponse: 'Ctrl+R',
  stopGeneration: 'Escape',
  toggleTheme: 'Ctrl+Shift+T',
  toggleSettings: 'Ctrl+,',
  exportChat: 'Ctrl+E',
};

/**
 * Map a server API settings section to the local settings model, falling back
 * to the provided defaults for any missing value.  Used by both fetchSettings
 * and resetSettings to avoid duplicating the field-by-field mapping.
 */
function mapServerToLlm(server: any, defaults: LLMSettings): LLMSettings {
  if (!server) return defaults;
  return {
    ...defaults,
    model: server.model,
    temperature: server.temperature ?? defaults.temperature,
    maxTokens: server.maxTokens ?? defaults.maxTokens,
    topP: server.topP ?? defaults.topP,
    topK: server.topK ?? defaults.topK,
    repeatPenalty: server.repeatPenalty ?? defaults.repeatPenalty,
    presencePenalty: server.presencePenalty ?? defaults.presencePenalty,
    frequencyPenalty: server.frequencyPenalty ?? defaults.frequencyPenalty,
    minP: server.minP ?? defaults.minP,
    tfsZ: server.tfsZ ?? defaults.tfsZ,
    typicalP: server.typicalP ?? defaults.typicalP,
    seed: server.seed,
    stopSequences: server.stopSequences ?? defaults.stopSequences,
    nPredict: server.nPredict ?? defaults.nPredict,
    nCtx: server.nCtx ?? defaults.nCtx,
    nBatch: server.nBatch ?? defaults.nBatch,
    nThreads: server.nThreads ?? defaults.nThreads,
    nGpuLayers: server.nGpuLayers ?? defaults.nGpuLayers,
    mainGpu: server.mainGpu ?? defaults.mainGpu,
    promptTemplate: server.promptTemplate ?? defaults.promptTemplate,
    systemPrompt: server.systemPrompt,
    chatFormat: server.chatFormat ?? defaults.chatFormat,
    stream: server.streamingEnabled ?? defaults.stream,
    mirostat: server.mirostat ?? defaults.mirostat,
    mirostatTau: server.mirostatTau ?? defaults.mirostatTau,
    mirostatEta: server.mirostatEta ?? defaults.mirostatEta,
    contextSize: server.contextSize ?? defaults.contextSize,
    templates: server.templates ?? defaults.templates,
    currentTemplate: server.currentTemplate ?? defaults.currentTemplate,
  };
}

function mapServerToUi(server: any, defaults: UISettings): UISettings {
  if (!server) return defaults;
  return {
    ...defaults,
    theme: server.theme ?? defaults.theme,
    fontSize: server.fontSize ?? defaults.fontSize,
    fontFamily: server.fontFamily ?? defaults.fontFamily,
    compactMode: server.compactMode ?? defaults.compactMode,
    showTimestamps: server.showTimestamps ?? defaults.showTimestamps,
    showTokenCount: server.showTokenCount ?? defaults.showTokenCount,
    showModelInfo: server.showModelInfo ?? defaults.showModelInfo,
    enterToSend: server.enterToSend ?? defaults.enterToSend,
    soundEnabled: server.soundEnabled ?? defaults.soundEnabled,
    notificationsEnabled: server.notificationsEnabled ?? defaults.notificationsEnabled,
    autoScroll: server.autoScroll ?? defaults.autoScroll,
    messageGrouping: server.messageGrouping ?? defaults.messageGrouping,
    syntaxHighlighting: server.syntaxHighlighting ?? defaults.syntaxHighlighting,
    markdownRendering: server.markdownRendering ?? defaults.markdownRendering,
    emojiPicker: server.emojiPicker ?? defaults.emojiPicker,
    spellCheck: server.spellCheck ?? defaults.spellCheck,
    autoSave: server.autoSave ?? defaults.autoSave,
    autoSaveInterval: server.autoSaveInterval ?? defaults.autoSaveInterval,
    sidebarPosition: server.sidebarPosition ?? defaults.sidebarPosition,
    sidebarCollapsed: server.sidebarCollapsed ?? defaults.sidebarCollapsed,
    messageLayout: server.messageLayout ?? defaults.messageLayout,
    language: server.language ?? defaults.language,
    codeTheme: server.codeTheme ?? defaults.codeTheme,
    editorTheme: server.editorTheme ?? defaults.editorTheme,
    showLineNumbers: server.showLineNumbers ?? defaults.showLineNumbers,
    wordWrap: server.wordWrap ?? defaults.wordWrap,
  };
}

function mapServerToPrivacy(server: any, defaults: PrivacySettings): PrivacySettings {
  if (!server) return defaults;
  return {
    ...defaults,
    shareAnalytics: server.shareAnalytics ?? defaults.shareAnalytics,
    saveHistory: server.saveHistory ?? defaults.saveHistory,
    encryptMessages: server.encryptMessages ?? defaults.encryptMessages,
    clearOnLogout: server.clearOnLogout ?? defaults.clearOnLogout,
    exportFormat: server.exportFormat ?? defaults.exportFormat,
    dataRetentionDays: server.dataRetentionDays ?? defaults.dataRetentionDays,
    analytics: server.analytics ?? defaults.analytics,
    shareData: server.shareData ?? defaults.shareData,
    publicProfile: server.publicProfile ?? defaults.publicProfile,
    showOnlineStatus: server.showOnlineStatus ?? defaults.showOnlineStatus,
  };
}

function mapServerToNotifications(server: any, defaults: NotificationSettings): NotificationSettings {
  if (!server) return defaults;
  return {
    ...defaults,
    desktop: server.desktop ?? defaults.desktop,
    sound: server.sound ?? defaults.sound,
    vibration: server.vibration ?? defaults.vibration,
    mentions: server.mentions ?? defaults.mentions,
    replies: server.replies ?? defaults.replies,
    newConversations: server.newConversations ?? defaults.newConversations,
    systemAlerts: server.systemAlerts ?? defaults.systemAlerts,
    schedule: server.schedule ?? defaults.schedule,
    customSchedule: server.customSchedule,
    enabled: server.enabled ?? defaults.enabled,
    email: server.email ?? defaults.email,
    messageAlerts: server.messageAlerts ?? defaults.messageAlerts,
  };
}

// Debounced preset persistence. Writing JSON.stringify(presets) on every
// keystroke blocks the main thread; batching coalesces rapid edits into one
// write per ~500ms window.
let presetPersistTimer: ReturnType<typeof setTimeout> | null = null;
function schedulePresetPersist(getState: () => SettingsState) {
  if (presetPersistTimer !== null) clearTimeout(presetPersistTimer);
  presetPersistTimer = setTimeout(() => {
    presetPersistTimer = null;
    try {
      localStorage.setItem('llm-presets', JSON.stringify(getState().presets));
    } catch (err) {
      // localStorage quota exceeded or unavailable — log once, don't crash.
      console.warn('[settingsStore] Failed to persist presets:', err);
    }
  }, 500);
}

export const useSettingsStore = create<SettingsState>()(
  devtoolsInDev(
    persist(
      immer((set, get) => ({
        llmSettings: defaultLLMSettings,
        uiSettings: defaultUISettings,
        privacySettings: defaultPrivacySettings,
        notificationSettings: defaultNotificationSettings,
        keyboardShortcuts: defaultKeyboardShortcuts,
        presets: [],
        activePresetId: null,
        isLoading: false,
        error: null,
        hasUnsavedChanges: false,

        fetchSettings: async () => {
          set((state) => {
            setLoading(state);
          });

          try {
            const { data } = await apolloClient.query({
              query: GET_SETTINGS_QUERY,
              fetchPolicy: 'no-cache',
              context: {
                fetchOptions: {
                  cache: 'no-store',
                },
              },
            });

            set((state) => {
              const s = data.settings;
              state.llmSettings = mapServerToLlm(s.llm, defaultLLMSettings);
              state.uiSettings = mapServerToUi(s.ui, defaultUISettings);
              state.privacySettings = mapServerToPrivacy(s.privacy, defaultPrivacySettings);
              state.notificationSettings = mapServerToNotifications(s.notifications, defaultNotificationSettings);
              state.keyboardShortcuts = s.keyboard?.shortcuts
                ? { ...defaultKeyboardShortcuts, ...shortcutEntriesToRecord(s.keyboard.shortcuts) }
                : defaultKeyboardShortcuts;
              clearLoadingAndError(state);
              state.hasUnsavedChanges = false;
            });
          } catch (error) {
            set((state) => {
              state.error = error instanceof Error ? error.message : 'Failed to fetch settings';
              state.isLoading = false;
            });
          }
        },

        updateLLMSettings: (settings: Partial<LLMSettings>) => {
          set((state) => {
            Object.assign(state.llmSettings, settings);
            state.hasUnsavedChanges = true;
          });
        },

        updateUISettings: (settings: Partial<UISettings>) => {
          set((state) => {
            Object.assign(state.uiSettings, settings);
            state.hasUnsavedChanges = true;

            // Apply theme immediately
            if (settings.theme) {
              const effectiveTheme = settings.theme === 'auto' ? 'system' : settings.theme;
              const shouldBeDark = effectiveTheme === 'dark' ||
                (effectiveTheme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);

              if (shouldBeDark) {
                document.documentElement.classList.add('dark');
              } else {
                document.documentElement.classList.remove('dark');
              }
            }
          });
        },

        updatePrivacySettings: (settings: Partial<PrivacySettings>) => {
          set((state) => {
            Object.assign(state.privacySettings, settings);
            state.hasUnsavedChanges = true;
          });
        },

        updateNotificationSettings: async (settings: Partial<NotificationSettings>) => {
          set((state) => {
            Object.assign(state.notificationSettings, settings);
            state.hasUnsavedChanges = true;
          });

          // Request notification permission if enabling desktop notifications
          if (settings.desktop && 'Notification' in window) {
            await Notification.requestPermission();
          }
        },

        updateKeyboardShortcuts: (shortcuts: Partial<KeyboardShortcuts>) => {
          set((state) => {
            Object.assign(state.keyboardShortcuts, shortcuts);
            state.hasUnsavedChanges = true;
          });
        },

        saveSettings: async () => {
          const state = get();

          set((s) => {
            setLoading(s);
          });

          try {
            const { llmSettings: llm, uiSettings: ui, privacySettings: privacy, notificationSettings: notif, keyboardShortcuts: kb } = state;
            // `stream` and `templates` are client-side fields. The GraphQL input
            // takes the stream flag as `streamingEnabled`, and the server derives
            // the template list, so sending either one breaks the whole mutation
            // with "LLMSettingsInput has no field ...".
            const { stream, templates: _templates, ...llmInput } = llm;
            await apolloClient.mutate({
              mutation: UPDATE_SETTINGS_MUTATION,
              variables: {
                input: {
                  llm: { ...llmInput, streamingEnabled: stream },
                  ui,
                  notifications: notif,
                  privacy,
                  keyboard: { shortcuts: recordToShortcutEntries(kb) },
                },
              },
            });

            set((s) => {
              clearLoadingAndError(s);
              s.hasUnsavedChanges = false;
            });
          } catch (error) {
            set((s) => {
              s.error = error instanceof Error ? error.message : 'Failed to save settings';
              s.isLoading = false;
            });
            throw error;
          }
        },

        resetSettings: async () => {
          set((state) => {
            setLoading(state);
          });

          try {
            const { data } = await apolloClient.mutate({
              mutation: RESET_SETTINGS_MUTATION,
            });

            set((state) => {
              const s = data.resetSettings;
              state.llmSettings = mapServerToLlm(s.llm, defaultLLMSettings);
              state.uiSettings = mapServerToUi(s.ui, defaultUISettings);
              state.privacySettings = mapServerToPrivacy(s.privacy, defaultPrivacySettings);
              state.notificationSettings = mapServerToNotifications(s.notifications, defaultNotificationSettings);
              state.keyboardShortcuts = s.keyboard?.shortcuts
                ? { ...defaultKeyboardShortcuts, ...shortcutEntriesToRecord(s.keyboard.shortcuts) }
                : defaultKeyboardShortcuts;
              clearLoadingAndError(state);
              state.hasUnsavedChanges = false;
            });
          } catch (error) {
            set((state) => {
              state.error = error instanceof Error ? error.message : 'Failed to reset settings';
              state.isLoading = false;
            });
          }
        },

        // Presets are managed locally only (not in backend schema)
        fetchPresets: async () => {
          // Load from localStorage
          try {
            const stored = getStoredItem('llm-presets');
            if (stored) {
              set((state) => {
                state.presets = stored;
              });
            }
          } catch (error) {
            console.error('Failed to load presets:', error);
          }
        },

        createPreset: async (name: string, description?: string) => {
          const { llmSettings } = get();

          const newPreset: Preset = {
            id: `preset-${Date.now()}`,
            name,
            description,
            settings: llmSettings,
            isDefault: false,
            created: new Date().toISOString(),
            updated: new Date().toISOString(),
          };

          set((state) => {
            // Dedup by id — a retry with the same Date.now() collision or an
            // accidental double-invocation must not duplicate the preset.
            if (!state.presets.some((p: Preset) => p.id === newPreset.id)) {
              state.presets.push(newPreset);
            }
          });
          schedulePresetPersist(get);

          return newPreset;
        },

        updatePreset: async (id: string, updates: Partial<Preset>) => {
          set((state) => {
            const index = state.presets.findIndex((p: Preset) => p.id === id);
            if (index >= 0) {
              state.presets[index] = { ...state.presets[index], ...updates, updated: new Date().toISOString() };
            }
          });
          schedulePresetPersist(get);
        },

        deletePreset: async (id: string) => {
          set((state) => {
            state.presets = state.presets.filter((p: Preset) => p.id !== id);
            if (state.activePresetId === id) {
              state.activePresetId = null;
            }
          });
          schedulePresetPersist(get);
        },

        applyPreset: (id: string) => {
          const preset = get().presets.find((p) => p.id === id);
          if (!preset) return;

          set((state) => {
            Object.assign(state.llmSettings, preset.settings);
            state.activePresetId = id;
            state.hasUnsavedChanges = true;
          });
        },

        exportSettings: () => {
          const state = get();
          return JSON.stringify({
            llmSettings: state.llmSettings,
            uiSettings: state.uiSettings,
            privacySettings: state.privacySettings,
            notificationSettings: state.notificationSettings,
            keyboardShortcuts: state.keyboardShortcuts,
            presets: state.presets,
          }, null, 2);
        },

        importSettings: async (data: string) => {
          try {
            const imported = JSON.parse(data);

            set((state) => {
              if (imported.llmSettings) state.llmSettings = imported.llmSettings;
              if (imported.uiSettings) state.uiSettings = imported.uiSettings;
              if (imported.privacySettings) state.privacySettings = imported.privacySettings;
              if (imported.notificationSettings) state.notificationSettings = imported.notificationSettings;
              if (imported.keyboardShortcuts) state.keyboardShortcuts = imported.keyboardShortcuts;
              if (imported.presets) state.presets = imported.presets;
              state.hasUnsavedChanges = true;
            });

            await get().saveSettings();
          } catch (_error) {
            throw new Error('Invalid settings file');
          }
        },

        setHasUnsavedChanges: (hasChanges: boolean) => {
          set((state) => {
            state.hasUnsavedChanges = hasChanges;
          });
        },

        clearError: () => {
          set((state) => {
            state.error = null;
          });
        },
      })),
      {
        name: 'settings-storage',
        partialize: (state) => ({
          uiSettings: state.uiSettings,
          activePresetId: state.activePresetId,
        }),
      },
    ),
  ),
);