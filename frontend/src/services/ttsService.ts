import type { SpeechLanguage } from './speechLanguage';
import type { VoiceProfile } from './voiceProfile';

/**
 * TTS Service - Text-to-Speech Service using Ming Voice Design over WebSocket
 *
 * Features:
 * - WebSocket streaming with PCM format for instant decoding
 * - Server-side sentence splitting for natural pauses
 * - Voice selection based on character attributes (gender inference)
 * - Pause/resume/restart controls per message
 */

// TTS Voice interface
export interface TTSVoice {
  name: string;
  language: string;
  language_code: string;
  gender: 'male' | 'female' | 'neutral';
  description: string;
}

// TTS Configuration
export interface TTSConfig {
  serverUrl: string;
  wsUrl: string;
  defaultVoice: string;
  defaultMaleVoice: string;
  defaultFemaleVoice: string;
  speed: number;
  autoPlay: boolean;
  languageId: SpeechLanguage;
}

// Audio chunk from WebSocket
interface AudioChunk {
  chunkIndex: number;
  audioBuffer: AudioBuffer;
  text: string;
  duration: number;
}

// Playback state for a message
export interface MessagePlaybackState {
  messageId: string;
  isPlaying: boolean;
  isPaused: boolean;
  currentChunkIndex: number;
  totalChunks: number;
  progress: number; // 0-100
  voice: string;
  completed?: boolean;
  error?: string;
}

// Default configuration
const DEFAULT_CONFIG: TTSConfig = {
  serverUrl: '/tts',
  wsUrl: `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/tts/ws/tts`,
  defaultVoice: 'narrator',
  defaultMaleVoice: 'narrator',
  defaultFemaleVoice: 'narrator',
  speed: 1.0,
  autoPlay: true,
  languageId: 'en',
};

// Gender detection keywords
const FEMALE_KEYWORDS = [
  'she', 'her', 'hers', 'herself',
  'woman', 'women', 'girl', 'girls',
  'female', 'feminine', 'lady', 'ladies',
  'queen', 'princess', 'goddess', 'witch',
  'mother', 'mom', 'daughter', 'sister',
  'wife', 'girlfriend', 'actress', 'waitress'
];

const MALE_KEYWORDS = [
  'he', 'him', 'his', 'himself',
  'man', 'men', 'boy', 'boys',
  'male', 'masculine', 'gentleman', 'gentlemen',
  'king', 'prince', 'god', 'wizard',
  'father', 'dad', 'son', 'brother',
  'husband', 'boyfriend', 'actor', 'waiter'
];

/**
 * Clean text for TTS - remove code blocks, thinking blocks, etc.
 */
export function cleanTextForTTS(text: string): string {
  return text
    .replace(/<think(?:ing)?>([\s\S]*?)<\/think(?:ing)?>/gi, '')
    .replace(/<think(?:ing)?>([\s\S]*)$/gi, '') // partial thinking blocks
    .replace(/```[\s\S]*?```/g, '') // code blocks
    .replace(/`[^`]+`/g, '') // inline code
    .trim();
}

/**
 * Count words in a string
 */
function countWords(text: string): number {
  return text.trim().split(/\s+/).filter(w => w.length > 0).length;
}

/**
 * Batch short sentences together to ensure minimum word count per chunk.
 * This prevents choppy playback from very short sentences.
 *
 * Logic:
 * - Accumulate sentences until we reach minWords
 * - Once we hit a sentence boundary AND have >= minWords, close the batch
 * - Final batch gets whatever remains (even if < minWords)
 * - Newlines act as natural batch boundaries (for poetry/verse structure)
 *
 * Example with minWords=12:
 *   "Hi. Hello there. How are you today? I'm doing great thanks!"
 *   → "Hi. Hello there. How are you today?" (11 words) + "I'm doing great thanks!" (4 words)
 *   Wait, 11 < 12, so it continues...
 *   → "Hi. Hello there. How are you today? I'm doing great thanks!" (15 words, all in one batch)
 *
 * @param text - The text to process
 * @param minWords - Minimum words per batch (default: 12)
 * @returns Text with short sentences batched together
 */
export function batchShortSentences(text: string, minWords: number = 12): string {
  if (!text.trim()) return '';

  // Split by newlines to preserve paragraph/verse structure
  // Capturing group keeps the newlines in the result
  const segments = text.split(/(\n+)/);
  const result: string[] = [];
  let isFirstSegment = true;

  for (const segment of segments) {
    // Preserve newlines as-is (natural batch boundaries)
    if (/^\n+$/.test(segment)) {
      result.push(segment);
      continue;
    }

    if (!segment.trim()) continue;

    // Process this segment - batch its sentences
    // First segment gets fast-start treatment (first sentence is its own batch)
    const batched = batchSegment(segment, minWords, isFirstSegment);
    result.push(batched);
    isFirstSegment = false;
  }

  return result.join('');
}

/**
 * Batch sentences within a single segment (no newlines)
 *
 * @param segment - Text segment to batch
 * @param minWords - Minimum words per batch
 * @param isFirstSegment - If true, first sentence is its own batch for fast start
 */
function batchSegment(segment: string, minWords: number, isFirstSegment: boolean = false): string {
  const batches: string[] = [];
  let currentBatch = '';
  let currentWordCount = 0;
  let isFirstSentence = isFirstSegment;

  // Split on sentence-ending punctuation, keeping the delimiters
  // Matches: . ! ? and combinations like ... or ?! followed by optional whitespace
  const parts = segment.split(/([.!?]+\s*)/);

  for (const part of parts) {
    if (!part) continue;

    const isPunctuation = /^[.!?]+\s*$/.test(part);

    if (isPunctuation) {
      // Add punctuation to complete the sentence
      currentBatch += part;

      // First sentence gets its own batch for fast initial playback
      if (isFirstSentence) {
        batches.push(currentBatch.trim());
        currentBatch = '';
        currentWordCount = 0;
        isFirstSentence = false;
      }
      // Subsequent sentences: check if we have enough words to close this batch
      else if (currentWordCount >= minWords) {
        batches.push(currentBatch.trim());
        currentBatch = '';
        currentWordCount = 0;
      }
      // Otherwise keep accumulating - short sentences stay together
    } else {
      // Regular text - add to batch and count words
      currentBatch += part;
      currentWordCount += countWords(part);
    }
  }

  // Final batch gets whatever remains (may be < minWords, that's fine)
  if (currentBatch.trim()) {
    batches.push(currentBatch.trim());
  }

  // Join batches with space (they're already trimmed)
  return batches.join(' ');
}

/**
 * Infer gender from character attributes
 */
export function inferGenderFromCharacter(character: VoiceProfile | null): 'male' | 'female' | 'unknown' {
  if (!character) return 'unknown';

  const text = [
    character.name || '',
    character.description || '',
    character.personality || '',
    character.appearance || ''
  ].join(' ').toLowerCase();

  let femaleScore = 0;
  let maleScore = 0;

  for (const keyword of FEMALE_KEYWORDS) {
    const regex = new RegExp(`\\b${keyword}\\b`, 'gi');
    const matches = text.match(regex);
    if (matches) femaleScore += matches.length;
  }

  for (const keyword of MALE_KEYWORDS) {
    const regex = new RegExp(`\\b${keyword}\\b`, 'gi');
    const matches = text.match(regex);
    if (matches) maleScore += matches.length;
  }

  if (femaleScore > maleScore) return 'female';
  if (maleScore > femaleScore) return 'male';
  return 'unknown';
}

/**
 * TTS Service Class - WebSocket streaming with PCM format
 */
class TTSService {
  private config: TTSConfig;
  private playbackRequests = new Map<string, symbol>();
  private voices: TTSVoice[] = [];
  private audioContext: AudioContext | null = null;

  // Per-message state
  private audioPlayQueue: Map<string, AudioChunk[]> = new Map();
  private playbackStates: Map<string, MessagePlaybackState> = new Map();
  private activeWebSockets: Map<string, WebSocket> = new Map();
  private messageTexts: Map<string, { text: string; voice: string; character?: VoiceProfile }> = new Map(); // Store original text for restart

  // Audio format from server
  private sampleRate: number = 24000;

  // Playback pipeline state
  private currentSource: AudioBufferSourceNode | null = null;
  private currentMessageId: string | null = null;
  private nextPlayTime: Map<string, number> = new Map();

  // Message queue for round-robin group conversations
  // Messages wait in queue and play in order (FIFO)
  private pendingMessageQueue: Array<{
    messageId: string;
    text: string;
    voice: string;
    character?: VoiceProfile;
  }> = [];

  // Synchronization: tracks which message has "claimed" playback rights
  // This prevents race conditions where multiple messages start play() concurrently
  // Set synchronously BEFORE calling async play(), cleared when playback finishes or fails
  private activePlaybackMessageId: string | null = null;

  // Generate at most one following message while current audio is playing.
  // Wait for current synthesis to finish so lookahead cannot starve its chunks.
  private preloaded: { messageId: string; ready: Promise<void> } | null = null;

  // Order tracking: records when each message started streaming (for FIFO ordering)
  // In group conversations, messages should play in the order they started, not the order they got content
  private streamingStartOrder: Map<string, number> = new Map();
  private streamingOrderCounter: number = 0;

  // Audio analysis for visualization
  private analyserNode: AnalyserNode | null = null;
  private gainNode: GainNode | null = null;
  private audioLevelListeners: Set<(level: number) => void> = new Set();
  private audioLevelInterval: ReturnType<typeof setInterval> | null = null;

  // Listeners
  private listeners: Map<string, Set<(state: MessagePlaybackState) => void>> = new Map();
  private globalListeners: Set<(messageId: string, state: MessagePlaybackState) => void> = new Set();

  constructor(config: Partial<TTSConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };
  }

  /**
   * Check the service without requiring browser audio permission.
   */
  async initialize(): Promise<void> {
    await this.fetchVoices();
  }

  /** Unlock audio directly from a user gesture before any network awaits. */
  async unlockAudio(): Promise<void> {
    if (!this.audioContext) {
      this.audioContext = new AudioContext({ sampleRate: this.sampleRate });

      // Create audio analysis chain for visualization
      this.analyserNode = this.audioContext.createAnalyser();
      this.analyserNode.fftSize = 256;
      this.analyserNode.smoothingTimeConstant = 0.3;

      this.gainNode = this.audioContext.createGain();
      this.gainNode.connect(this.analyserNode);
      this.analyserNode.connect(this.audioContext.destination);
    }
    if (this.audioContext.state === 'suspended') {
      await this.audioContext.resume();
    }
  }

  /**
   * Fetch available voices from TTS server
   * Throws on network/server errors so callers can detect connectivity issues.
   */
  async fetchVoices(): Promise<TTSVoice[]> {
    const response = await fetch(`${this.config.serverUrl.replace(/\/$/, '')}/api/voices`, {
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) {
      throw new Error(`TTS server returned ${response.status}`);
    }
    const data = await response.json();
    if (!data.success || !Array.isArray(data.data)) {
      throw new Error('The voice service returned an invalid response');
    }
    this.voices = data.data;
    return this.voices;
  }

  /**
   * Get all available voices
   */
  getVoices(): TTSVoice[] {
    return this.voices;
  }

  /**
   * Select appropriate voice for a character
   */
  selectVoiceForCharacter(character: VoiceProfile | null): string {
    // Character voice design travels as metadata; narrator is the default.
    void character;
    return this.config.defaultVoice;
  }

  /**
   * Convert PCM Int16 to AudioBuffer (instant - no async decoding!)
   */
  private pcmToAudioBuffer(pcmBase64: string, sampleRate: number): AudioBuffer {
    // Decode base64 to bytes
    const pcmBytes = Uint8Array.from(atob(pcmBase64), c => c.charCodeAt(0));

    // Convert to Int16Array (PCM is 16-bit signed)
    const pcmData = new Int16Array(pcmBytes.buffer);

    // Convert to Float32 for Web Audio API (-1.0 to 1.0)
    const floatData = new Float32Array(pcmData.length);
    for (let i = 0; i < pcmData.length; i++) {
      floatData[i] = pcmData[i] / 32768.0;
    }

    // Create AudioBuffer
    const audioBuffer = this.audioContext!.createBuffer(1, floatData.length, sampleRate);
    audioBuffer.getChannelData(0).set(floatData);

    return audioBuffer;
  }

  /**
   * Start TTS playback using WebSocket streaming
   */
  async play(messageId: string, text: string, voice?: string, character?: VoiceProfile): Promise<void> {
    const buffered = this.preloaded;
    if (buffered?.messageId === messageId) {
      await buffered.ready;
      if (this.preloaded !== buffered) return; // cancelled during preparation
      this.preloaded = null;
      const state = this.playbackStates.get(messageId);
      if (!state) return;
      this.activePlaybackMessageId = messageId;
      if (state.error) {
        this.notifyListeners(messageId);
        this.activePlaybackMessageId = null;
        void this.playNextQueuedMessage();
        return;
      }
      state.isPlaying = true;
      this.notifyListeners(messageId);
      this.tryStartPlayback(messageId);
      this.preloadNextMessage();
      return;
    }
    await this.synthesize(messageId, text, voice, character);
  }

  private preloadNextMessage(): void {
    const active = this.activePlaybackMessageId;
    if (!active || !this.playbackStates.get(active)?.isPlaying ||
        this.activeWebSockets.has(active) || this.preloaded || !this.pendingMessageQueue.length) return;
    const next = [...this.pendingMessageQueue].sort((a, b) =>
      (this.streamingStartOrder.get(a.messageId) ?? Infinity) -
      (this.streamingStartOrder.get(b.messageId) ?? Infinity))[0];
    const order = this.streamingStartOrder.get(next.messageId) ?? Infinity;
    if ([...this.streamingStartOrder.entries()].some(([id, value]) =>
      id !== next.messageId && value < order && this.streamingText.has(id))) return;
    const buffered = { messageId: next.messageId, ready: Promise.resolve() };
    this.preloaded = buffered;
    buffered.ready = this.synthesize(next.messageId, next.text, next.voice, next.character, true)
      .catch(() => { /* Report generation errors when this message reaches the front. */ });
  }

  private async synthesize(messageId: string, text: string, voice?: string, character?: VoiceProfile, preload = false): Promise<void> {
    const request = Symbol(messageId);
    this.playbackRequests.set(messageId, request);
    try {
      // Lookahead uses the existing context without resuming paused playback.
      if (!preload) await this.unlockAudio();
      if (this.playbackRequests.get(messageId) !== request) return;
      await this.initialize();
    } catch (error) {
      if (this.playbackRequests.get(messageId) === request) {
        this.playbackStates.set(messageId, { messageId, isPlaying: false, isPaused: false,
          currentChunkIndex: 0, totalChunks: 0, progress: 0, error: error instanceof Error ? error.message : "Voice playback failed", voice: voice || this.config.defaultVoice });
        if (!preload) {
          this.notifyListeners(messageId);
          this.activePlaybackMessageId = null;
          void this.playNextQueuedMessage();
        }
      }
      throw error;
    }

    if (this.playbackRequests.get(messageId) !== request) return;

    // Preserve punctuation and action boundaries for server-side speaker routing.
    const cleanText = cleanTextForTTS(text);
    if (!cleanText) {
      // If this message was the active one, clear it and try the next queued message
      if (this.activePlaybackMessageId === messageId) {
        this.activePlaybackMessageId = null;
        this.playNextQueuedMessage();
      }
      return;
    }

    // Select voice early so we can store it
    const selectedVoice = voice || this.selectVoiceForCharacter(character || null);
    const selectedLanguage = this.config.languageId;

    // Store original text and voice for restart functionality
    this.messageTexts.set(messageId, { text: cleanText, voice: selectedVoice, character });

    // Cancel an old request even if it is still waiting for its first audio.
    const previousId = this.activePlaybackMessageId || this.currentMessageId;
    if (!preload && previousId && previousId !== messageId) this.stop(previousId, false);
    if (this.currentSource && this.currentMessageId === messageId) {
      this.currentSource.onended = null;
      this.currentSource.stop();
      this.currentSource = null;
    }

    // Close existing WebSocket for this message if any
    const existingWs = this.activeWebSockets.get(messageId);
    if (existingWs) {
      existingWs.close();
      this.activeWebSockets.delete(messageId);
    }

    // Initialize state
    this.audioPlayQueue.set(messageId, []);
    this.nextPlayTime.set(messageId, 0);

    const state: MessagePlaybackState = {
      messageId,
      isPlaying: !preload,
      isPaused: false,
      currentChunkIndex: 0,
      totalChunks: 0, // Will be updated as chunks arrive
      progress: 0,
      voice: selectedVoice
    };
    this.playbackStates.set(messageId, state);
    if (!preload) {
      this.activePlaybackMessageId = messageId;
      this.notifyListeners(messageId);
    }

    // Create WebSocket connection
    const ws = new WebSocket(this.config.wsUrl);
    this.activeWebSockets.set(messageId, ws);

    ws.onopen = () => {
      if (this.activeWebSockets.get(messageId) !== ws) return;
      ws.send(JSON.stringify({
        action: 'synthesize',
        text: cleanText,
        voice: selectedVoice,
        character,
        mode: 'VOICE_DESIGN',
        language_id: selectedLanguage,
        output_format: 'pcm_base64'  // Raw PCM, fast decoding
      }));
    };

    ws.onmessage = (event) => {
      if (this.activeWebSockets.get(messageId) !== ws) return;
      let msg;
      try {
        msg = JSON.parse(event.data);
      } catch {
        finish('The voice service sent an invalid response. Try again.');
        ws.close();
        return;
      }

      if (msg.type === 'START') {
        // Audio format metadata
        this.sampleRate = msg.sample_rate || 24000;
        state.totalChunks = msg.total_chunks || 0;

        // AudioBuffer carries the model's sample rate. Web Audio resamples it
        // for the existing context, preserving pause and analyser connections.

      } else if (msg.type === 'CHUNK') {
        // Convert PCM to AudioBuffer (instant!)
        const audioBuffer = this.pcmToAudioBuffer(msg.audio_base64, this.sampleRate);

        const chunk: AudioChunk = {
          chunkIndex: msg.chunk_index,
          audioBuffer,
          text: msg.chunk_text || '',
          duration: msg.duration
        };

        // Add to queue
        const queue = this.audioPlayQueue.get(messageId) || [];
        queue.push(chunk);
        this.audioPlayQueue.set(messageId, queue);


        // Update total chunks estimate
        const currentState = this.playbackStates.get(messageId);
        if (currentState) {
          currentState.totalChunks = Math.max(currentState.totalChunks, msg.chunk_index + 1);
        }

        // Try to start playback
        this.tryStartPlayback(messageId);

      } else if (msg.type === 'COMPLETE' || msg.type === 'ERROR') {
        finish(msg.type === 'ERROR' ? msg.error || 'Voice generation failed. Try again.' : undefined);
        ws.close();
      }
    };

    const finish = (error?: string) => {
      if (this.activeWebSockets.get(messageId) !== ws) return;
      if (error) state.error = error;
      this.activeWebSockets.delete(messageId);
      if (!this.currentSource) this.playNextFromQueue(messageId);
      this.preloadNextMessage();
    };
    ws.onerror = () => finish('Voice connection lost. Try again.');
    ws.onclose = () => finish('Voice connection closed before speech finished. Try again.');
  }

  /**
   * Try to start playback if conditions are met
   */
  private tryStartPlayback(messageId: string): void {
    const state = this.playbackStates.get(messageId);
    if (!state || !state.isPlaying || state.isPaused) {
      return;
    }

    // SAFETY CHECK: Only start playback if this is the active message
    // This prevents race conditions where multiple messages try to play
    if (this.activePlaybackMessageId && this.activePlaybackMessageId !== messageId) {
      return;
    }

    // If nothing is currently playing for this message, start
    if (!this.currentSource || this.currentMessageId !== messageId) {
      this.playNextFromQueue(messageId);
    }
  }

  /**
   * Play next message from the pending queue (round-robin support)
   * Called when current message playback completes
   */
  private async playNextQueuedMessage(): Promise<void> {
    if (this.pendingMessageQueue.length === 0) {
      return;
    }

    // Sort queue by streaming start order to ensure FIFO based on when messages appeared
    // (not when they were queued, which could be out of order)
    this.pendingMessageQueue.sort((a, b) => {
      const orderA = this.streamingStartOrder.get(a.messageId) ?? Infinity;
      const orderB = this.streamingStartOrder.get(b.messageId) ?? Infinity;
      return orderA - orderB;
    });

    // Get next message from queue (now sorted by streaming start order)
    const nextMessage = this.pendingMessageQueue.shift()!

    // CLAIM playback rights SYNCHRONOUSLY before any async operations
    // An earlier streaming message may not be finalized yet.
    const nextOrder = this.streamingStartOrder.get(nextMessage.messageId) ?? Infinity;
    if ([...this.streamingStartOrder.entries()].some(([id, order]) =>
      id !== nextMessage.messageId && order < nextOrder && this.streamingText.has(id))) {
      this.pendingMessageQueue.unshift(nextMessage);
      return;
    }
    this.activePlaybackMessageId = nextMessage.messageId;

    // Get the latest text for this message (may have accumulated more while waiting)
    const latestText = this.streamingText.get(nextMessage.messageId) || nextMessage.text;

    // Clean up streaming state for this message (was deferred from finalizeStreaming)
    this.streamingText.delete(nextMessage.messageId);
    this.streamingStartOrder.delete(nextMessage.messageId); // Clean up order tracking

    // Play the message
    try {
      await this.play(nextMessage.messageId, latestText, nextMessage.voice, nextMessage.character);
    } catch (error) {
      console.error('[TTS] Queued playback failed:', error);
    }
  }

  /**
   * Play next audio chunk from the queue
   */
  private playNextFromQueue(messageId: string): void {
    if (!this.audioContext) {
      return;
    }

    const state = this.playbackStates.get(messageId);
    if (!state || !state.isPlaying || state.isPaused) return;
    if (this.audioContext.state === 'suspended') void this.audioContext.resume();

    // SAFETY CHECK: Only play if this is the active message
    // This prevents race conditions where multiple messages try to play
    if (this.activePlaybackMessageId && this.activePlaybackMessageId !== messageId) {
      return;
    }

    const playQueue = this.audioPlayQueue.get(messageId);
    if (!playQueue || playQueue.length === 0) {
      // Check if any WebSocket is still active (primary or append)
      const wsActive = this.activeWebSockets.has(messageId);
      const appendWsActive = this.activeWebSockets.has(`${messageId}_append`);

      if (!wsActive && !appendWsActive) {
        // All done with all audio for this message
        this.currentSource = null;
        this.currentMessageId = null;
        this.activePlaybackMessageId = null; // Clear the claim
        this.updatePlaybackState(messageId, {
          isPlaying: false,
          completed: !state.error && state.currentChunkIndex > 0,
          progress: state.error ? state.progress : 100
        });

        // Check if there are queued messages waiting (round-robin support)
        this.playNextQueuedMessage();
      } else {
        // WebSocket still active - more chunks may arrive, keep state as playing
      }
      return;
    }

    // Get next chunk
    const chunk = playQueue.shift()!;
    this.audioPlayQueue.set(messageId, playQueue);


    // Stop any current playback
    if (this.currentSource) {
      try {
        this.currentSource.onended = null;
        this.currentSource.stop();
      } catch {
        // Already stopped
      }
    }

    this.currentMessageId = messageId;

    // Create and play source - connect through gain node for audio analysis
    this.currentSource = this.audioContext.createBufferSource();
    this.currentSource.buffer = chunk.audioBuffer;
    this.currentSource.playbackRate.value = this.config.speed;

    // Connect through gain node to enable audio level analysis
    if (this.gainNode) {
      this.currentSource.connect(this.gainNode);
    } else {
      this.currentSource.connect(this.audioContext.destination);
    }

    this.currentSource.onended = () => {
      this.currentSource = null;

      // Update progress
      const totalChunks = state.totalChunks || 1;
      const progress = Math.round(((chunk.chunkIndex + 1) / totalChunks) * 100);

      this.updatePlaybackState(messageId, {
        currentChunkIndex: chunk.chunkIndex + 1,
        progress: Math.min(progress, 99)
      });

      // Play next
      this.playNextFromQueue(messageId);
    };

    this.currentSource.start();
  }

  /**
   * Streaming support - for auto-play during message streaming
   * Accumulates text and starts TTS when enough content is available
   */
  private streamingText: Map<string, string> = new Map();

  async addStreamingChunk(messageId: string, fullText: string, _voice?: string, _character?: VoiceProfile): Promise<void> {
    // Reserve FIFO order while buffering the complete message. Generating only
    // balanced, finalized text prevents quote boundaries from changing speakers.
    if (this.activePlaybackMessageId === messageId) return;
    this.streamingText.set(messageId, fullText);
    if (!this.streamingStartOrder.has(messageId)) {
      this.streamingStartOrder.set(messageId, this.streamingOrderCounter++);
    }
  }

  async finalizeStreaming(messageId: string, fullText: string, voice?: string, character?: VoiceProfile): Promise<void> {
    if (this.activePlaybackMessageId === messageId) return;
    this.streamingText.set(messageId, fullText);
    if (!this.streamingStartOrder.has(messageId)) {
      this.streamingStartOrder.set(messageId, this.streamingOrderCounter++);
    }
    if (this.pendingMessageQueue.some(message => message.messageId === messageId)) return;
    this.pendingMessageQueue.push({ messageId, text: fullText,
      voice: voice || this.config.defaultVoice, character });
    if (!this.activePlaybackMessageId && !this.currentMessageId) {
      await this.playNextQueuedMessage();
    }
    this.preloadNextMessage();
  }

  /**
   * Pause playback - uses AudioContext.suspend() for true pausing
   */
  pause(messageId: string): void {
    const state = this.playbackStates.get(messageId);
    if (!state || !state.isPlaying) return;

    // Use AudioContext.suspend() for true pause (not stop!)
    // This pauses all audio processing and can be resumed
    if (this.audioContext && this.currentMessageId === messageId) {
      this.audioContext.suspend().catch(e => console.error('[TTS] Suspend error:', e));
    }

    this.updatePlaybackState(messageId, { isPaused: true });
  }

  /**
   * Resume playback - uses AudioContext.resume() to continue from pause
   */
  async resume(messageId: string): Promise<void> {
    const state = this.playbackStates.get(messageId);
    if (!state || !state.isPaused) return;

    // Resume the AudioContext (this continues playback from where it paused)
    if (this.audioContext && this.audioContext.state === 'suspended') {
      await this.audioContext.resume();
    }

    this.updatePlaybackState(messageId, { isPaused: false });

    // If there's no current source playing, start from the queue
    // (this handles the edge case where pause happened between chunks)
    if (!this.currentSource) {
      this.playNextFromQueue(messageId);
    }
  }

  /**
   * Restart playback from beginning
   */
  async restart(messageId: string): Promise<void> {
    // Get the stored original text and voice
    const stored = this.messageTexts.get(messageId);
    if (!stored) {
      return;
    }

    // Stop current playback
    this.stopAll();

    // Re-store the text (stop() clears it) and play again
    this.messageTexts.set(messageId, stored);
    await this.play(messageId, stored.text, stored.voice, stored.character);
  }

  /**
   * Stop playback completely
   */
  stop(messageId: string, advanceQueue = true): void {
    this.playbackRequests.delete(messageId);
    if (this.preloaded?.messageId === messageId) this.preloaded = null;
    // Close primary WebSocket
    const ws = this.activeWebSockets.get(messageId);
    if (ws) {
      ws.close();
      this.activeWebSockets.delete(messageId);
    }

    // Close append WebSocket if exists
    const appendWs = this.activeWebSockets.get(`${messageId}_append`);
    if (appendWs) {
      appendWs.close();
      this.activeWebSockets.delete(`${messageId}_append`);
    }

    // Resume AudioContext if suspended (so it's ready for next playback)
    if (this.audioContext && this.audioContext.state === 'suspended') {
      this.audioContext.resume().catch(() => {});
    }

    // Stop audio
    if (this.currentSource && this.currentMessageId === messageId) {
      try {
        this.currentSource.onended = null;
        this.currentSource.stop();
      } catch {
        // Already stopped
      }
      this.currentSource = null;
    }

    // Clear queue and all state for this message
    this.audioPlayQueue.delete(messageId);
    this.nextPlayTime.delete(messageId);
    this.messageTexts.delete(messageId);

    // Remove from pending queue if it was waiting
    this.pendingMessageQueue = this.pendingMessageQueue.filter(m => m.messageId !== messageId);

    // Clear streaming state (important for regeneration)
    this.streamingText.delete(messageId);
    this.streamingStartOrder.delete(messageId); // Clear order tracking

    if (this.currentMessageId === messageId) {
      this.currentMessageId = null;
    }

    // Clear the active playback claim if this was the active message
    if (this.activePlaybackMessageId === messageId) {
      this.activePlaybackMessageId = null;
    }

    this.updatePlaybackState(messageId, {
      isPlaying: false,
      isPaused: false,
      progress: 0,
      currentChunkIndex: 0,
      completed: false,
      error: undefined
    });
    if (advanceQueue) queueMicrotask(() => {
      if (!this.activePlaybackMessageId && !this.currentMessageId) {
        void this.playNextQueuedMessage().catch(error => console.error('[TTS]', error));
      }
    });
  }

  /**
   * Stop all playback
   */
  stopAll(): void {
    const ids = new Set([...this.playbackRequests.keys(), ...this.playbackStates.keys(), ...this.streamingText.keys(),
      ...this.pendingMessageQueue.map(message => message.messageId)]);
    ids.forEach(messageId => this.stop(messageId, false));
  }

  /**
   * Update and notify playback state
   */
  private updatePlaybackState(messageId: string, updates: Partial<MessagePlaybackState>): void {
    const state = this.playbackStates.get(messageId);
    if (state) {
      Object.assign(state, updates);
      this.notifyListeners(messageId);
    }
  }

  /**
   * Get playback state for a message
   */
  getPlaybackState(messageId: string): MessagePlaybackState | null {
    return this.playbackStates.get(messageId) || null;
  }

  /**
   * Subscribe to playback state changes for a specific message
   */
  subscribe(messageId: string, callback: (state: MessagePlaybackState) => void): () => void {
    if (!this.listeners.has(messageId)) {
      this.listeners.set(messageId, new Set());
    }
    this.listeners.get(messageId)!.add(callback);
    return () => {
      this.listeners.get(messageId)?.delete(callback);
    };
  }

  /**
   * Subscribe to all playback state changes
   */
  subscribeAll(callback: (messageId: string, state: MessagePlaybackState) => void): () => void {
    this.globalListeners.add(callback);
    return () => {
      this.globalListeners.delete(callback);
    };
  }

  /**
   * Notify listeners of state change
   */
  private notifyListeners(messageId: string): void {
    const state = this.playbackStates.get(messageId);
    if (!state) return;
    this.listeners.get(messageId)?.forEach(callback => callback(state));
    this.globalListeners.forEach(callback => callback(messageId, state));
  }

  /**
   * Update configuration
   */
  updateConfig(config: Partial<TTSConfig>): void {
    this.config = { ...this.config, ...config };
  }

  /**
   * Get current configuration
   */
  getConfig(): TTSConfig {
    return { ...this.config };
  }

  /**
   * Get current audio level (0-1) from the analyser node
   * Returns 0 if no audio is playing or analysis is not available
   */
  getAudioLevel(): number {
    if (!this.analyserNode || !this.audioContext || this.audioContext.state !== 'running') {
      return 0;
    }

    const dataArray = new Uint8Array(this.analyserNode.frequencyBinCount);
    this.analyserNode.getByteFrequencyData(dataArray);

    // Calculate average amplitude
    let sum = 0;
    for (let i = 0; i < dataArray.length; i++) {
      sum += dataArray[i];
    }
    const average = sum / dataArray.length;

    // Normalize to 0-1 range (byte values are 0-255)
    return average / 255;
  }

  /**
   * Subscribe to audio level updates (called at ~60fps when monitoring is active)
   */
  subscribeToAudioLevel(callback: (level: number) => void): () => void {
    this.audioLevelListeners.add(callback);

    // Start monitoring if this is the first listener
    if (this.audioLevelListeners.size === 1) {
      this.startAudioLevelMonitoring();
    }

    return () => {
      this.audioLevelListeners.delete(callback);

      // Stop monitoring if no more listeners
      if (this.audioLevelListeners.size === 0) {
        this.stopAudioLevelMonitoring();
      }
    };
  }

  /**
   * Start audio level monitoring interval
   */
  private startAudioLevelMonitoring(): void {
    if (this.audioLevelInterval) return;

    // Update at ~30fps for smooth visualization
    this.audioLevelInterval = setInterval(() => {
      const level = this.getAudioLevel();
      this.audioLevelListeners.forEach(callback => callback(level));
    }, 33);
  }

  /**
   * Stop audio level monitoring interval
   */
  private stopAudioLevelMonitoring(): void {
    if (this.audioLevelInterval) {
      clearInterval(this.audioLevelInterval);
      this.audioLevelInterval = null;
    }
  }

  /**
   * Cleanup resources
   */
  dispose(): void {
    this.stopAll();
    this.stopAudioLevelMonitoring();
    this.audioLevelListeners.clear();

    if (this.audioContext) {
      this.audioContext.close();
      this.audioContext = null;
    }

    this.analyserNode = null;
    this.gainNode = null;
    this.audioPlayQueue.clear();
    this.playbackStates.clear();
    this.messageTexts.clear();
    this.pendingMessageQueue = []; // Clear the round-robin queue
    this.activePlaybackMessageId = null; // Clear the synchronization flag
    this.streamingStartOrder.clear(); // Clear order tracking
    this.streamingOrderCounter = 0; // Reset order counter
    this.streamingText.clear();
    this.listeners.clear();
    this.globalListeners.clear();
  }
}

// Export singleton instance
export const ttsService = new TTSService();

// Export class for testing
export { TTSService };

// Legacy exports for compatibility
export function splitIntoSentences(text: string, onlyComplete: boolean = false): string[] {
  const cleanText = cleanTextForTTS(text);
  if (!cleanText) return [];

  const sentences: string[] = [];
  const lines = cleanText.split(/\n+/);

  for (const line of lines) {
    const trimmedLine = line.trim();
    if (!trimmedLine || trimmedLine.length <= 1) continue;

    const parts = trimmedLine.split(/([.!?]+\s*)/);
    let currentSentence = '';

    for (const part of parts) {
      if (!part) continue;
      if (/^[.!?]+\s*$/.test(part)) {
        currentSentence += part.trim();
        if (currentSentence.length > 1) {
          sentences.push(currentSentence.trim());
        }
        currentSentence = '';
      } else {
        currentSentence += part;
      }
    }

    if (currentSentence.trim().length > 1 && !onlyComplete) {
      sentences.push(currentSentence.trim());
    }
  }

  if (sentences.length === 0 && cleanText.length > 1 && !onlyComplete) {
    return [cleanText];
  }

  return sentences;
}

export function getCompleteSentences(text: string): string[] {
  return splitIntoSentences(text, true);
}

export function getAllSentences(text: string): string[] {
  return splitIntoSentences(text, false);
}
