/**
 * TTS Settings Component
 *
 * Configure Text-to-Speech settings including voice selection,
 * auto-play, speed, and server configuration.
 */

import { useEffect, useRef, useState } from 'react';
import { Volume2, Play, Pause, Server, UserCircle, RefreshCw } from 'lucide-react';
import { SPEECH_LANGUAGES, NARRATOR_PREVIEWS, type SpeechLanguage } from '@/services/speechLanguage';
import { cn } from '@/lib/utils';
import { useTTSStore } from '@/stores/ttsStore';
import {
  SettingSection,
  SettingRow,
  SettingToggle,
  SettingSlider,
  SettingInput,
  SettingSelect,
} from './SettingsComponents';

export const TTSSettings = () => {
  const {
    settings,
    voices,
    voicesLoaded,
    isInitialized,
    initError,
    checkConnection,
    updateSettings,
    setEnabled,
    setAutoPlay,
    setSpeed,
    play,
    stop,
    playbackStates,
  } = useTTSStore();

  const [isTestPlaying, setIsTestPlaying] = useState(false);
  const [testMessageId] = useState('tts-test-preview');
  const [isChecking, setIsChecking] = useState(false);

  // Re-check connection when server URL changes (debounced)
  useEffect(() => {
    const timer = setTimeout(() => {
      if (settings.serverUrl) {
        setIsChecking(true);
        void checkConnection().finally(() => setIsChecking(false));
      }
    }, 800);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings.serverUrl]);

  // Watch for test playback completion
  useEffect(() => {
    if (!isTestPlaying) return;
    const state = playbackStates.get(testMessageId);
    if (state && !state.isPlaying && !state.isPaused) {
      setIsTestPlaying(false);
    }
  }, [playbackStates, testMessageId, isTestPlaying]);


  const testTimeoutRef = useRef<ReturnType<typeof setTimeout>>(undefined);

  useEffect(() => () => {
    if (testTimeoutRef.current) clearTimeout(testTimeoutRef.current);
    stop(testMessageId);
  }, [stop, testMessageId]);

  const handleTestVoice = async (voice: string) => {
    if (isTestPlaying) {
      stop(testMessageId);
      setIsTestPlaying(false);
      if (testTimeoutRef.current) clearTimeout(testTimeoutRef.current);
      return;
    }

    setIsTestPlaying(true);
    try {
      await play(
        testMessageId,
        NARRATOR_PREVIEWS[settings.languageId || 'en'],
        voice
      );
      // Safety fallback — playback state effect handles normal completion
      testTimeoutRef.current = setTimeout(() => {
        setIsTestPlaying(false);
      }, 30000);
    } catch (error) {
      console.error('Failed to play test:', error);
      setIsTestPlaying(false);
    }
  };

  const handleServerUrlChange = (value: string | number) => {
    updateSettings({ serverUrl: String(value) });
  };

  return (
    <div className="space-y-6">
      {/* Status Banner */}
      {initError && (
        <div className="p-4 bg-destructive/10 border border-destructive/30 rounded-xl">
          <div className="flex items-center gap-3">
            <Volume2 size={20} className="shrink-0 text-destructive" />
            <div className="min-w-0">
              <p className="text-sm font-medium text-destructive">TTS Server Not Available</p>
              <p className="text-sm text-destructive mt-0.5 break-words">
                {initError}. Make sure the TTS server is running at {settings.serverUrl}
              </p>
            </div>
          </div>
        </div>
      )}

      {/* Main TTS Settings */}
      <SettingSection
        title="Text-to-Speech"
        description="Configure automatic voice playback for AI responses"
        icon={Volume2}
      >
        <SettingRow
          label="Enable TTS"
          description="Read AI responses aloud using text-to-speech"
        >
          <SettingToggle
            checked={settings.enabled}
            onChange={setEnabled}
          />
        </SettingRow>

        <SettingRow
          label="Auto-Play"
          description="Read each AI response when it finishes generating"
          disabled={!settings.enabled}
        >
          <SettingToggle
            checked={settings.autoPlay}
            onChange={setAutoPlay}
            disabled={!settings.enabled}
          />
        </SettingRow>

        <SettingRow label="Text language" description="Choose the language your message text is written in." disabled={!settings.enabled}>
          <SettingSelect
            value={settings.languageId || 'en'}
            onChange={value => updateSettings({ languageId: String(value) as SpeechLanguage })}
            options={[...SPEECH_LANGUAGES]}
            disabled={!settings.enabled}
          />
        </SettingRow>

        <SettingRow
          label="Speech Speed"
          description="Adjust the playback speed"
          vertical
          disabled={!settings.enabled}
        >
          <SettingSlider
            value={settings.speed}
            onChange={setSpeed}
            min={0.5}
            max={2.0}
            step={0.1}
            unit="x"
            disabled={!settings.enabled}
          />
        </SettingRow>
      </SettingSection>

      <SettingSection
        title="Voice Design"
        description="Ming-3.0-tts-0.5B reads narration in a calm, clear voice. Character voices use vocal traits derived from their Personality and profile."
        icon={UserCircle}
      >
        <SettingRow label="Narrator" description="Used for actions, narration, and speech without a character personality.">
          <button
            onClick={() => void handleTestVoice('narrator')}
            disabled={!settings.enabled || !voicesLoaded}
            className="inline-flex min-h-11 items-center gap-2 rounded-lg border border-border px-3 text-sm disabled:opacity-50"
          >
            {isTestPlaying ? <Pause size={16} /> : <Play size={16} />}
            {isTestPlaying ? 'Stop preview' : 'Preview narrator'}
          </button>
        </SettingRow>
        <SettingRow label="Character dialogue" description="Edit a character’s Personality to shape their voice. Playback begins when the response finishes, so speaker changes stay accurate.">{null}</SettingRow>
        {!voicesLoaded && !initError && settings.enabled && (
          <div className="px-4 py-3 sm:px-5">
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
              Loading available voices...
            </div>
          </div>
        )}

        {voicesLoaded && voices.length === 0 && settings.enabled && (
          <div className="px-4 py-3 sm:px-5">
            <p className="text-sm text-muted-foreground">
              No voices available. Make sure the TTS server is running.
            </p>
          </div>
        )}
      </SettingSection>

      {/* Server Configuration */}
      <SettingSection
        title="TTS Server"
        description="Configure the TTS server connection"
        icon={Server}
      >
        <SettingRow
          label="Server URL"
          description="Ming service URL or same-origin path"
        >
          <SettingInput
            value={settings.serverUrl}
            onChange={handleServerUrlChange}
            placeholder="/tts"
            className="w-64"
          />
        </SettingRow>

        <div className="px-4 py-3 sm:px-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex min-w-0 items-center gap-2">
              <div
                className={cn(
                  'h-2.5 w-2.5 shrink-0 rounded-full',
                  isChecking
                    ? 'bg-warning animate-pulse'
                    : isInitialized && !initError
                    ? 'bg-success'
                    : initError
                    ? 'bg-destructive'
                    : 'bg-warning animate-pulse'
                )}
              />
              <span className="text-sm text-muted-foreground">
                {isChecking
                  ? 'Checking connection...'
                  : isInitialized && !initError
                  ? 'Connected to Ming Voice Design'
                  : initError
                  ? 'Connection failed'
                  : 'Not connected'}
              </span>
            </div>
            <button
              type="button"
              onClick={() => {
                setIsChecking(true);
                void checkConnection().finally(() => setIsChecking(false));
              }}
              disabled={isChecking}
              className={cn(
                'flex min-h-10 items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all sm:min-h-0',
                'border border-border/50 bg-muted/50 text-muted-foreground hover:bg-accent hover:text-foreground',
                isChecking && 'opacity-50 cursor-not-allowed'
              )}
            >
              <RefreshCw size={12} className={cn(isChecking && 'animate-spin')} />
              {isChecking ? 'Checking...' : 'Reconnect'}
            </button>
          </div>
        </div>
      </SettingSection>

    </div>
  );
};
