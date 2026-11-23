import type { VoiceProfile } from '@/services/voiceProfile';

/**
 * TTS Controls Component
 *
 * Provides play/pause/restart controls for Text-to-Speech playback per message.
 * Shows playback progress and voice selection.
 */

import React, { useState, useRef, useCallback, memo } from 'react';
import { Play, Pause, RotateCcw, Volume2, VolumeX, Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useTTSStore } from '@/stores/ttsStore';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/Tooltip';

interface TTSControlsProps {
  messageId: string;
  content: string;
  character?: VoiceProfile;
  isStreaming?: boolean;
  className?: string;
  compact?: boolean;
}

export const TTSControls = memo(({
  messageId,
  content,
  character,
  isStreaming = false,
  className,
  compact = false,
}: TTSControlsProps) => {
  const {
    settings,
    playbackStates,
    play,
    pause,
    resume,
    restart,
  } = useTTSStore();

  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pending = useRef(false);

  // Get playback state for this message
  const playbackState = playbackStates.get(messageId);
  const isPlaying = playbackState?.isPlaying ?? false;
  const isPaused = playbackState?.isPaused ?? false;
  const progress = playbackState?.progress ?? 0;

  // ALL HOOKS MUST BE CALLED BEFORE ANY CONDITIONAL RETURNS
  const handlePlayPause = useCallback(async () => {
    if (pending.current) return;
    pending.current = true;
    setError(null);
    setIsLoading(true);
    try {
      if (isPlaying && !isPaused) {
        pause(messageId);
      } else if (isPaused) {
        await resume(messageId);
      } else {
        await play(messageId, content, undefined, character);
      }
    } catch (error) {
      setError(error instanceof Error ? error.message : 'Playback failed. Try again.');
    } finally {
      pending.current = false;
      setIsLoading(false);
    }
  }, [messageId, content, character, isPlaying, isPaused, play, pause, resume]);

  const handleRestart = useCallback(async () => {
    if (pending.current) return;
    pending.current = true;
    setError(null);
    setIsLoading(true);
    try {
      await restart(messageId);
    } catch (error) {
      setError(error instanceof Error ? error.message : 'Restart failed. Try again.');
    } finally {
      pending.current = false;
      setIsLoading(false);
    }
  }, [messageId, restart]);

  // Don't render if TTS is disabled
  if (!settings.enabled) {
    return null;
  }

  // Don't render if there's no content
  if (!content || content.trim().length === 0) {
    return null;
  }

  // Compact version for inline display
  if (compact) {
    return (
      <TooltipProvider>
        <div className={cn('inline-flex items-center gap-1', className)}>
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                aria-label={isPlaying && !isPaused ? 'Pause voice playback' : isPaused ? 'Resume voice playback' : 'Play voice playback'}
                onClick={handlePlayPause}
                disabled={isLoading || isStreaming}
                className={cn(
                  'inline-flex shrink-0 items-center justify-center rounded-lg p-1.5 transition-all duration-150 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary',
                  'text-muted-foreground hover:bg-accent hover:text-foreground',
                  isPlaying && !isPaused && 'text-primary',
                  (isLoading || isStreaming) && 'opacity-50 cursor-not-allowed'
                )}
              >
                {isLoading ? (
                  <Loader2 size={14} className="animate-spin" />
                ) : isPlaying && !isPaused ? (
                  <Pause size={14} />
                ) : (
                  <Play size={14} />
                )}
              </button>
            </TooltipTrigger>
            <TooltipContent side="top">
              {isPlaying && !isPaused ? 'Pause' : isPaused ? 'Resume' : 'Play TTS'}
            </TooltipContent>
          </Tooltip>

          {(isPlaying || isPaused || progress > 0) && (
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  type="button"
                  aria-label="Restart voice playback"
                  onClick={handleRestart}
                  disabled={isLoading}
                  className={cn(
                    'inline-flex shrink-0 items-center justify-center rounded-lg p-1.5 transition-all duration-150 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary',
                    'text-muted-foreground hover:bg-accent hover:text-foreground',
                    isLoading && 'opacity-50 cursor-not-allowed'
                  )}
                >
                  <RotateCcw size={14} />
                </button>
              </TooltipTrigger>
              <TooltipContent side="top">Restart from beginning</TooltipContent>
            </Tooltip>
          )}
          {(error || playbackState?.error) && <span role="alert" className="max-w-64 text-xs text-destructive">{error || playbackState?.error}</span>}
        </div>
      </TooltipProvider>
    );
  }

  // Full version with progress bar
  return (
    <div className={cn('flex items-center gap-2', className)}>
      <TooltipProvider>
        <div className="flex items-center gap-1">
          {/* Play/Pause button */}
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                aria-label={isPlaying && !isPaused ? 'Pause voice playback' : isPaused ? 'Resume voice playback' : 'Play voice playback'}
                onClick={handlePlayPause}
                disabled={isLoading || isStreaming}
                className={cn(
                  'flex items-center justify-center',
                  'h-11 w-11 shrink-0 rounded-full transition-all duration-200',
                  'border border-border/50',
                  isPlaying && !isPaused
                    ? 'bg-primary text-primary-foreground hover:bg-primary-hover'
                    : 'bg-muted/50 text-foreground hover:bg-accent',
                  (isLoading || isStreaming) && 'opacity-50 cursor-not-allowed'
                )}
              >
                {isLoading ? (
                  <Loader2 size={16} className="animate-spin" />
                ) : isPlaying && !isPaused ? (
                  <Pause size={16} />
                ) : (
                  <Play size={16} className="ml-0.5" />
                )}
              </button>
            </TooltipTrigger>
            <TooltipContent side="top">
              {isStreaming
                ? 'Wait for message to complete'
                : isPlaying && !isPaused
                ? 'Pause'
                : isPaused
                ? 'Resume'
                : 'Play TTS'}
            </TooltipContent>
          </Tooltip>

          {/* Restart button */}
          {(isPlaying || isPaused || progress > 0) && (
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  type="button"
                  aria-label="Restart voice playback"
                  onClick={handleRestart}
                  disabled={isLoading}
                  className={cn(
                    'flex items-center justify-center',
                    'h-11 w-11 shrink-0 rounded-full transition-all duration-200',
                    'border border-border/50 bg-muted/50',
                    'text-muted-foreground hover:bg-accent hover:text-foreground',
                    isLoading && 'opacity-50 cursor-not-allowed'
                  )}
                >
                  <RotateCcw size={14} />
                </button>
              </TooltipTrigger>
              <TooltipContent side="top">Restart from beginning</TooltipContent>
            </Tooltip>
          )}
        </div>

        {/* Progress bar */}
        {(isPlaying || isPaused || progress > 0) && (
          <div className="flex-1 min-w-[60px] max-w-[120px]">
            <div className="relative h-1.5 w-full rounded-full bg-muted overflow-hidden">
              <div
                className={cn(
                  'absolute inset-y-0 left-0 rounded-full transition-all duration-300',
                  isPlaying && !isPaused ? 'bg-primary' : 'bg-primary/60'
                )}
                style={{ width: `${progress}%` }}
              />
            </div>
            <div className="mt-0.5 text-2xs text-muted-foreground text-center">
              {Math.round(progress)}%
            </div>
          </div>
        )}

        {(error || playbackState?.error) && <span role="alert" className="max-w-64 text-xs text-destructive">{error || playbackState?.error}</span>}
        {/* Voice indicator */}
        {playbackState?.voice && (
          <Tooltip>
            <TooltipTrigger asChild>
              <div className="flex items-center gap-1 text-2xs text-muted-foreground">
                <Volume2 size={12} />
                <span className="hidden sm:inline">{playbackState.voice}</span>
              </div>
            </TooltipTrigger>
            <TooltipContent side="top">Voice: {playbackState.voice}</TooltipContent>
          </Tooltip>
        )}
      </TooltipProvider>
    </div>
  );
});

TTSControls.displayName = 'TTSControls';

/**
 * TTS Toggle Button - Quick enable/disable for TTS
 */
export const TTSToggle = memo(({ className }: { className?: string }) => {
  const { settings, setEnabled } = useTTSStore();

  const handleToggle = useCallback(() => {
    setEnabled(!settings.enabled);
  }, [settings.enabled, setEnabled]);

  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          <button
            type="button"
            aria-label={settings.enabled ? 'Disable voice playback' : 'Enable voice playback'}
            aria-pressed={settings.enabled}
            onClick={handleToggle}
            className={cn(
              'flex items-center justify-center',
              'h-8 w-8 rounded-lg transition-all duration-200',
              'border border-border/50',
              settings.enabled
                ? 'bg-primary/10 text-primary hover:bg-primary/20'
                : 'bg-muted/50 text-muted-foreground hover:bg-accent',
              className
            )}
          >
            {settings.enabled ? <Volume2 size={16} /> : <VolumeX size={16} />}
          </button>
        </TooltipTrigger>
        <TooltipContent side="bottom">
          {settings.enabled ? 'Disable TTS' : 'Enable TTS'}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
});

TTSToggle.displayName = 'TTSToggle';

/**
 * TTS Auto-Play Indicator - Shows when auto-play is active
 */
export const TTSAutoPlayIndicator = memo(({ className }: { className?: string }) => {
  const { settings, currentlyPlayingMessageId } = useTTSStore();

  if (!settings.enabled || !settings.autoPlay || !currentlyPlayingMessageId) {
    return null;
  }

  return (
    <div className={cn(
      'flex items-center gap-1.5 px-2 py-1 rounded-full',
      'bg-primary/10 text-primary text-xs font-medium',
      'animate-pulse',
      className
    )}>
      <Volume2 size={12} />
      <span>TTS Playing</span>
    </div>
  );
});

TTSAutoPlayIndicator.displayName = 'TTSAutoPlayIndicator';
