import { isReadableMessage, messageVoiceProfile, speechSignature, type ConversationSpeechMessage } from '@/services/conversationSpeech';
import type { SpeechLanguage } from '@/services/speechLanguage';
import type { VoiceProfile } from '@/services/voiceProfile';

/**
 * TTS Store
 *
 * Manages TTS state including playback states, settings, and voice selection.
 * Uses Zustand for state management with persistence for settings.
 */

import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { immer } from 'zustand/middleware/immer';
import { ttsService, TTSVoice, MessagePlaybackState } from '@/services/ttsService';
import { devtoolsInDev } from '@/lib/storeHelpers';

interface TTSSettings {
  enabled: boolean;
  autoPlay: boolean;
  defaultVoice: string;
  defaultMaleVoice: string;
  defaultFemaleVoice: string;
  speed: number;
  serverUrl: string;
  languageId: SpeechLanguage;
}

interface TTSState {
  readProgress: Record<string, Record<string, string>>;
  speechMessages: Record<string, { conversationId: string; signature: string }>;
  syncConversationMessages: (conversationId: string, messages: ConversationSpeechMessage[]) => void;
  readingQueue: ConversationSpeechMessage[];
  readingEnqueuing: boolean;
  drainReadingQueue: () => Promise<void>;
  playbackSignatures: Record<string, { conversationId: string; signature: string }>;
  readingPaused: boolean;
  toggleReadingPause: () => Promise<void>;
  readingConversationId: string | null;
  readingMessageIds: string[];
  readingSession: number;
  startReading: (conversationId: string, messages: ConversationSpeechMessage[], fromBeginning?: boolean) => Promise<void>;
  stopReading: () => void;
  // Settings
  settings: TTSSettings;

  // Available voices
  voices: TTSVoice[];
  voicesLoaded: boolean;

  // Playback states per message
  playbackStates: Map<string, MessagePlaybackState>;

  // Currently playing message
  currentlyPlayingMessageId: string | null;

  // Initialization state
  isInitialized: boolean;
  initError: string | null;

  // Actions
  initialize: () => Promise<void>;
  checkConnection: () => Promise<void>;
  fetchVoices: () => Promise<void>;

  // Playback controls
  play: (messageId: string, text: string, voice?: string, character?: VoiceProfile) => Promise<void>;
  pause: (messageId: string) => void;
  resume: (messageId: string) => Promise<void>;
  restart: (messageId: string) => Promise<void>;
  stop: (messageId: string) => void;
  stopAll: () => void;

  // Streaming support
  addStreamingChunk: (messageId: string, fullText: string, voice?: string, character?: VoiceProfile) => Promise<void>;
  finalizeStreaming: (messageId: string, fullText: string, voice?: string, character?: VoiceProfile) => Promise<void>;

  // Settings
  updateSettings: (settings: Partial<TTSSettings>) => void;
  setEnabled: (enabled: boolean) => void;
  setAutoPlay: (autoPlay: boolean) => void;
  setDefaultVoice: (voice: string) => void;
  setSpeed: (speed: number) => void;

  // State updates
  updatePlaybackState: (messageId: string, state: MessagePlaybackState) => void;
  getPlaybackState: (messageId: string) => MessagePlaybackState | null;

  // Voice selection
  selectVoiceForCharacter: (character: VoiceProfile | null) => string;

  // Audio level for visualization
  subscribeToAudioLevel: (callback: (level: number) => void) => () => void;
  getAudioLevel: () => number;

  // Cleanup
  reset: () => void;
}

// Module-level unsubscribe function to prevent callback accumulation on re-initialization
let unsubscribeFromTTS: (() => void) | null = null;

const defaultSettings: TTSSettings = {
  enabled: true,
  autoPlay: true,
  defaultVoice: 'narrator',
  defaultMaleVoice: 'narrator',
  defaultFemaleVoice: 'narrator',
  speed: 1.0,
  serverUrl: '/tts',
  languageId: 'en',
};

// Cap the playbackStates Map so long conversations don't leak memory.
// Maps preserve insertion order — evict oldest when we exceed the cap.
const MAX_PLAYBACK_STATES = 200;
function evictOldestPlaybackStates(map: Map<string, MessagePlaybackState>, currentId: string | null) {
  while (map.size > MAX_PLAYBACK_STATES) {
    const oldest = map.keys().next().value;
    if (oldest === undefined || oldest === currentId) break;
    map.delete(oldest);
  }
}

export const useTTSStore = create<TTSState>()(
  devtoolsInDev(
    persist(
      immer((set, get) => ({
        // Initial state
        settings: defaultSettings,
        readProgress: {},
        speechMessages: {},
        readingQueue: [],
        readingEnqueuing: false,
        playbackSignatures: {},
        readingPaused: false,
        readingConversationId: null,
        readingMessageIds: [],
        readingSession: 0,
        voices: [],
        voicesLoaded: false,
        playbackStates: new Map(),
        currentlyPlayingMessageId: null,
        isInitialized: false,
        initError: null,

        initialize: async () => {
          if (get().isInitialized) return;

          try {
            // Update TTS service config
            const settings = get().settings;
            ttsService.updateConfig({
              serverUrl: settings.serverUrl,
              wsUrl: new URL(`${settings.serverUrl.replace(/\/$/, '')}/ws/tts`, window.location.origin).href.replace(/^http/, 'ws'),
              defaultVoice: settings.defaultVoice,
              defaultMaleVoice: settings.defaultMaleVoice,
              defaultFemaleVoice: settings.defaultFemaleVoice,
              speed: settings.speed,
              languageId: settings.languageId,
              autoPlay: settings.autoPlay,
            });

            // Subscribe to playback state updates with leak-safe unsubscribe
            if (unsubscribeFromTTS) {
              unsubscribeFromTTS();
            }
            unsubscribeFromTTS = ttsService.subscribeAll((messageId, state) => {
              set((s) => {
                s.playbackStates.set(messageId, { ...state });
                if (state.isPlaying || state.isPaused) {
                  s.currentlyPlayingMessageId = messageId;
                } else if (s.currentlyPlayingMessageId === messageId) {
                  s.currentlyPlayingMessageId = null;
                }
                evictOldestPlaybackStates(s.playbackStates, s.currentlyPlayingMessageId);
                if (state.isPlaying && !s.playbackSignatures[messageId] && s.speechMessages[messageId]) {
                  s.playbackSignatures[messageId] = { ...s.speechMessages[messageId] };
                }
                const message = s.playbackSignatures[messageId] || s.speechMessages[messageId];
                if (state.completed && message) {
                  const progress = s.readProgress[message.conversationId] ||= {};
                  progress[messageId] = message.signature;
                  const ids = Object.keys(progress);
                  ids.slice(0, Math.max(0, ids.length - 1000)).forEach(id => delete progress[id]);
                }
                if (!state.isPlaying && !state.isPaused) delete s.playbackSignatures[messageId];
                if (state.error) s.initError = state.error;
                if (!state.isPlaying && !state.isPaused &&
                    s.readingMessageIds[s.readingMessageIds.length - 1] === messageId) {
                  s.readingConversationId = null;
                  s.readingMessageIds = [];
                  s.readingPaused = false;
                }
              });
            });

            // Fetch voices — this now throws on connectivity failure
            await get().fetchVoices();

            set((state) => {
              state.isInitialized = true;
              state.initError = null;
            });
          } catch (error) {
            set((state) => {
              state.isInitialized = false;
              state.initError = error instanceof Error ? error.message : 'Failed to connect to TTS server';
            });
          }
        },

        checkConnection: async () => {
          // Force re-check by resetting initialization state
          set((state) => {
            state.isInitialized = false;
            state.initError = null;
            state.voicesLoaded = false;
            state.voices = [];
          });
          await get().initialize();
        },

        fetchVoices: async () => {
          const voices = await ttsService.fetchVoices();
          set((state) => {
            state.voices = voices;
            state.voicesLoaded = true;
          });
        },

        syncConversationMessages: (conversationId, messages) => {
          const current = get();
          const readable = messages.filter(isReadableMessage);
          const metadata = Object.fromEntries(readable.map(message => [message.id, {
            conversationId, signature: speechSignature(message),
          }]));
          const metadataChanged = Object.keys(current.speechMessages).length !== Object.keys(metadata).length ||
            Object.entries(metadata).some(([id, value]) =>
              current.speechMessages[id]?.conversationId !== value.conversationId ||
              current.speechMessages[id]?.signature !== value.signature);
          const additions = current.readingConversationId === conversationId
            ? readable.filter(message => !current.readingMessageIds.includes(message.id) &&
                current.readProgress[conversationId]?.[message.id] !== metadata[message.id].signature)
            : [];

          // Message arrays may be recreated by a subscribed parent on every render.
          // Equivalent input must not publish another store update (or storage write).
          if (!metadataChanged && !additions.length) return;
          set(state => {
            if (metadataChanged) state.speechMessages = metadata;
            for (const message of additions) {
              state.readingMessageIds.push(message.id);
              state.readingQueue.push(message);
            }
          });
          if (get().readingConversationId === conversationId && get().isInitialized && get().readingQueue.length) {
            void get().drainReadingQueue();
          }
        },

        drainReadingQueue: async () => {
          if (get().readingEnqueuing || !get().readingConversationId || !get().readingQueue.length) return;
          const session = get().readingSession;
          set(state => { state.readingEnqueuing = true; });
          try {
            while (get().readingSession === session && get().readingQueue.length) {
              const message = get().readingQueue[0];
              set(state => { state.readingQueue.shift(); });
              await ttsService.finalizeStreaming(message.id, message.content, undefined, messageVoiceProfile(message));
            }
          } catch (error) {
            if (get().readingSession === session) {
              get().stopReading();
              set(state => { state.initError = error instanceof Error ? error.message : 'Voice playback failed'; });
            }
          } finally {
            if (get().readingSession === session) set(state => { state.readingEnqueuing = false; });
          }
        },

        toggleReadingPause: async () => {
          const id = get().currentlyPlayingMessageId;
          if (!get().readingConversationId || !id) return;
          if (get().readingPaused) {
            try {
              await ttsService.resume(id);
              set(state => { state.readingPaused = false; });
            } catch (error) {
              set(state => { state.initError = error instanceof Error ? error.message : 'Unable to resume. Try again.'; });
            }
          } else {
            ttsService.pause(id);
            set(state => { state.readingPaused = true; });
          }
        },

        startReading: async (conversationId, messages, fromBeginning = false) => {
          if (!get().settings.enabled) return;
          get().syncConversationMessages(conversationId, messages);
          const readable = messages.filter(message => isReadableMessage(message) &&
            (fromBeginning || get().readProgress[conversationId]?.[message.id] !== speechSignature(message)));
          const queue = readable;
          get().stopAll();
          if (!queue.length) return;
          const session = get().readingSession + 1;
          set(state => {
            state.initError = null;
            state.readingPaused = false;
            state.readingSession = session;
            if (fromBeginning) queue.forEach(message => { delete state.readProgress[conversationId]?.[message.id]; });
            state.readingConversationId = conversationId;
            state.readingMessageIds = queue.map(message => message.id);
            state.readingQueue = queue;
          });
          try {
            await ttsService.unlockAudio();
            await get().initialize();
            if (get().readingSession !== session) return;
            if (!get().isInitialized) { get().stopReading(); return; }
            await get().drainReadingQueue();
          } catch (error) {
            if (get().readingSession === session) {
              get().stopReading();
              set(state => { state.initError = error instanceof Error ? error.message : 'Voice playback failed'; });
            }
          }
        },

        stopReading: () => { get().stopAll(); },

        play: async (messageId, text, voice, character) => {
          const settings = get().settings;
          if (!settings.enabled) return;
          get().stopAll();
          const session = get().readingSession;
          await ttsService.unlockAudio();
          await get().initialize();
          if (get().readingSession !== session || !get().settings.enabled) return;
          if (!get().isInitialized) throw new Error(get().initError || 'Voice service unavailable. Try again.');
          set(state => { state.initError = null; });
          const selectedVoice = voice || get().selectVoiceForCharacter(character || null);
          await ttsService.play(messageId, text, selectedVoice, character);
        },

        pause: (messageId) => {
          ttsService.pause(messageId);
          if (get().readingMessageIds.includes(messageId)) set(state => { state.readingPaused = true; });
        },

        resume: async (messageId) => {
          await ttsService.resume(messageId);
          if (get().readingMessageIds.includes(messageId)) set(state => { state.readingPaused = false; });
        },

        restart: async (messageId) => {
          set(state => {
            state.readingSession += 1;
            state.readingQueue = [];
            state.readingEnqueuing = false;
            state.readingConversationId = null;
            state.readingMessageIds = [];
            state.readingPaused = false;
          });
          await ttsService.restart(messageId);
        },

        stop: (messageId) => {
          ttsService.stop(messageId);
          set((state) => {
            state.playbackStates.delete(messageId);
            if (state.currentlyPlayingMessageId === messageId) {
              state.currentlyPlayingMessageId = null;
            }
          });
        },

        stopAll: () => {
          set((state) => {
            state.readingSession += 1;
            state.readingQueue = [];
            state.readingEnqueuing = false;
            state.readingPaused = false;
            state.readingConversationId = null;
            state.readingMessageIds = [];
            state.playbackStates.clear();
            state.currentlyPlayingMessageId = null;
          });
          ttsService.stopAll();
        },

        addStreamingChunk: async (messageId, fullText, voice, character) => {
          const settings = get().settings;
          if (!settings.enabled || !settings.autoPlay || get().readingConversationId) return;

          const selectedVoice = voice || get().selectVoiceForCharacter(character || null);
          await ttsService.addStreamingChunk(messageId, fullText, selectedVoice, character);
        },

        finalizeStreaming: async (messageId, fullText, voice, character) => {
          const settings = get().settings;
          if (!settings.enabled || !settings.autoPlay || get().readingConversationId) return;
          const session = get().readingSession;
          // Reserve arrival order before awaiting initialization: completed-only
          // narrator events must precede the character stream that follows them.
          await ttsService.addStreamingChunk(messageId, fullText, voice, character);
          await get().initialize();
          if (get().readingSession !== session || !get().settings.enabled || !get().settings.autoPlay || get().readingConversationId) return;
          if (!get().isInitialized) {
            ttsService.stop(messageId);
            return;
          }
          await ttsService.finalizeStreaming(messageId, fullText, voice, character);
        },

        updateSettings: (newSettings) => {
          const urlChanged = newSettings.serverUrl !== undefined &&
            newSettings.serverUrl !== get().settings.serverUrl;

          if (newSettings.enabled === false || urlChanged ||
              (newSettings.languageId !== undefined && newSettings.languageId !== get().settings.languageId)) {
            get().stopAll();
          }
          set((state) => {
            Object.assign(state.settings, newSettings);
          });

          // Update TTS service config
          const settings = get().settings;
          ttsService.updateConfig({
            serverUrl: settings.serverUrl,
            wsUrl: new URL(`${settings.serverUrl.replace(/\/$/, '')}/ws/tts`, window.location.origin).href.replace(/^http/, 'ws'),
            defaultVoice: settings.defaultVoice,
            defaultMaleVoice: settings.defaultMaleVoice,
            defaultFemaleVoice: settings.defaultFemaleVoice,
            speed: settings.speed,
            languageId: settings.languageId,
            autoPlay: settings.autoPlay,
          });

          // Reset initialization when server URL changes so connection is re-checked
          if (urlChanged) {
            set((state) => {
              state.isInitialized = false;
              state.initError = null;
              state.voicesLoaded = false;
              state.voices = [];
            });
          }
        },

        setEnabled: (enabled) => {
          set((state) => {
            state.settings.enabled = enabled;
          });

          // Stop all playback if disabling
          if (!enabled) {
            get().stopAll();
          }
        },

        setAutoPlay: (autoPlay) => {
          set((state) => {
            state.settings.autoPlay = autoPlay;
          });
          ttsService.updateConfig({ autoPlay });
        },

        setDefaultVoice: (voice) => {
          set((state) => {
            state.settings.defaultVoice = voice;
          });
          ttsService.updateConfig({ defaultVoice: voice });
        },

        setSpeed: (speed) => {
          set((state) => {
            state.settings.speed = speed;
          });
          ttsService.updateConfig({ speed });
        },

        updatePlaybackState: (messageId, playbackState) => {
          set((state) => {
            state.playbackStates.set(messageId, playbackState);
            evictOldestPlaybackStates(state.playbackStates, state.currentlyPlayingMessageId);
          });
        },

        getPlaybackState: (messageId) => {
          return get().playbackStates.get(messageId) || null;
        },

        selectVoiceForCharacter: (character) => {
          return ttsService.selectVoiceForCharacter(character);
        },

        subscribeToAudioLevel: (callback) => {
          return ttsService.subscribeToAudioLevel(callback);
        },

        getAudioLevel: () => {
          return ttsService.getAudioLevel();
        },

        reset: () => {
          get().stopReading();
          if (unsubscribeFromTTS) {
            unsubscribeFromTTS();
            unsubscribeFromTTS = null;
          }
          ttsService.dispose();
          set((state) => {
            state.playbackStates.clear();
            state.speechMessages = {};
            state.playbackSignatures = {};
            state.readProgress = {};
            state.currentlyPlayingMessageId = null;
            state.isInitialized = false;
            state.initError = null;
          });
        },
      })),
      {
        name: 'tts-storage',
        partialize: (state) => ({
          settings: state.settings,
          readProgress: Object.fromEntries(Object.entries(state.readProgress).slice(-100)),
        }),
        // Custom merge to handle Map serialization
        merge: (persisted: any, current) => ({
          ...current,
          readProgress: persisted?.readProgress || {},
          settings: {
            ...current.settings,
            ...persisted?.settings,
            defaultVoice: 'narrator',
            defaultMaleVoice: 'narrator',
            defaultFemaleVoice: 'narrator',
            serverUrl: !persisted?.settings?.serverUrl || persisted.settings.serverUrl === 'http://localhost:9797'
              ? '/tts' : persisted.settings.serverUrl,
          },
        }),
      },
    ),
  ),
);
