/**
 * TTS Playing Indicator
 *
 * Animated visualization showing when TTS audio is actively playing.
 * Features animated equalizer bars that respond to actual audio output levels.
 */

import { memo, useEffect, useState, useRef } from 'react';
import { cn } from '@/lib/utils';
import { useTTSStore } from '@/stores/ttsStore';

interface TTSPlayingIndicatorProps {
  isPlaying: boolean;
  className?: string;
  size?: 'sm' | 'md' | 'lg';
  color?: 'primary' | 'white' | 'muted';
}

// Minimum audio level threshold to show animation (avoids flickering during silence)
const AUDIO_LEVEL_THRESHOLD = 0.02;

/**
 * Animated sound bars - the classic audio playing visualization
 * Only animates when actual audio output is detected (decibel level > 0)
 */
export const TTSPlayingIndicator = memo(({
  isPlaying,
  className,
  size = 'md',
  color = 'primary'
}: TTSPlayingIndicatorProps) => {
  const { subscribeToAudioLevel } = useTTSStore();
  const [audioLevel, setAudioLevel] = useState(0);
  const [isAudioActive, setIsAudioActive] = useState(false);

  // Track audio level with some smoothing to avoid flickering
  const audioActiveTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (!isPlaying) {
      setAudioLevel(0);
      setIsAudioActive(false);
      return;
    }

    const unsubscribe = subscribeToAudioLevel((level) => {
      setAudioLevel(level);

      // Set active when level is above threshold
      if (level > AUDIO_LEVEL_THRESHOLD) {
        setIsAudioActive(true);

        // Clear any pending timeout to hide
        if (audioActiveTimeoutRef.current) {
          clearTimeout(audioActiveTimeoutRef.current);
          audioActiveTimeoutRef.current = null;
        }
      } else {
        // Add a small delay before hiding to prevent flickering during brief pauses
        if (!audioActiveTimeoutRef.current) {
          audioActiveTimeoutRef.current = setTimeout(() => {
            setIsAudioActive(false);
            audioActiveTimeoutRef.current = null;
          }, 150);
        }
      }
    });

    return () => {
      unsubscribe();
      if (audioActiveTimeoutRef.current) {
        clearTimeout(audioActiveTimeoutRef.current);
      }
    };
  }, [isPlaying, subscribeToAudioLevel]);

  // Don't render if not playing or no audio output
  if (!isPlaying || !isAudioActive) return null;

  const sizeClasses = {
    sm: 'h-3 gap-[2px]',
    md: 'h-4 gap-[3px]',
    lg: 'h-5 gap-1'
  };

  const barWidths = {
    sm: 'w-[2px]',
    md: 'w-[3px]',
    lg: 'w-1'
  };

  const colorClasses = {
    primary: 'bg-primary',
    white: 'bg-white',
    muted: 'bg-muted-foreground'
  };

  // Scale animation intensity based on audio level (0.3 to 1.0)
  const intensity = Math.min(1, Math.max(0.3, audioLevel * 3));

  return (
    <div
      className={cn(
        'flex items-end',
        sizeClasses[size],
        className
      )}
      role="status"
      aria-label="Audio playing"
    >
      {[0, 1, 2, 3, 4].map((i) => (
        <div
          key={i}
          className={cn(
            'rounded-full transition-all',
            barWidths[size],
            colorClasses[color],
            'animate-sound-bar'
          )}
          style={{
            animationDelay: `${i * 0.1}s`,
            height: '40%',
            opacity: intensity
          }}
        />
      ))}
    </div>
  );
});

TTSPlayingIndicator.displayName = 'TTSPlayingIndicator';

/**
 * Circular pulsing indicator - alternative minimal style
 */
export const TTSPulseIndicator = memo(({
  isPlaying,
  className,
  size = 'md'
}: Omit<TTSPlayingIndicatorProps, 'color'>) => {
  if (!isPlaying) return null;

  const sizeClasses = {
    sm: 'h-2 w-2',
    md: 'h-2.5 w-2.5',
    lg: 'h-3 w-3'
  };

  return (
    <div className={cn('relative', className)}>
      {/* Outer pulse ring */}
      <div
        className={cn(
          'absolute inset-0 rounded-full bg-primary/40',
          'animate-ping'
        )}
        style={{ animationDuration: '1.5s' }}
      />
      {/* Inner solid dot */}
      <div
        className={cn(
          'relative rounded-full bg-primary',
          sizeClasses[size]
        )}
      />
    </div>
  );
});

TTSPulseIndicator.displayName = 'TTSPulseIndicator';

/**
 * Voice waveform - more elaborate visualization
 */
export const TTSWaveform = memo(({
  isPlaying,
  className
}: Pick<TTSPlayingIndicatorProps, 'isPlaying' | 'className'>) => {
  if (!isPlaying) return null;

  return (
    <div className={cn('flex items-center gap-[2px] h-4', className)}>
      {[...Array(9)].map((_, i) => {
        // Create a wave pattern - middle bars are taller
        const distance = Math.abs(i - 4);
        const baseHeight = 100 - distance * 15;

        return (
          <div
            key={i}
            className="w-[2px] rounded-full bg-primary animate-sound-wave"
            style={{
              animationDelay: `${i * 0.05}s`,
              height: `${baseHeight}%`
            }}
          />
        );
      })}
    </div>
  );
});

TTSWaveform.displayName = 'TTSWaveform';

/**
 * Speaking mouth animation - fun alternative
 */
export const TTSSpeakingIndicator = memo(({
  isPlaying,
  className
}: Pick<TTSPlayingIndicatorProps, 'isPlaying' | 'className'>) => {
  if (!isPlaying) return null;

  return (
    <div
      className={cn(
        'relative w-5 h-5 rounded-full bg-primary/20 flex items-center justify-center',
        className
      )}
    >
      {/* Simple animated "mouth" */}
      <div
        className="w-2.5 bg-primary rounded-full animate-speaking"
        style={{ height: '2px' }}
      />
    </div>
  );
});

TTSSpeakingIndicator.displayName = 'TTSSpeakingIndicator';
