/**
 * Character Portrait
 *
 * The character's generated face. A portrait is a 1024x1024 sheet rendered
 * through the ComfyUI "character_portrait" workflow; on completion the
 * backend links it to the character's avatar and derives the eight
 * reference crops used for identity consistency.
 *
 * Generation is conversation-scoped (the render job belongs to a story), so
 * the action is only available when the card was opened from a story.
 */

import { useEffect, useRef, useState } from 'react';
import { Bot, Loader2, RefreshCw, Sparkles, AlertTriangle } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import {
  useGeneratedImages,
  useGenerateImage,
  useImageGenerated,
} from '@/hooks/useRoleplay';
import type { Character } from '@/graphql/types';
import { cn } from '@/lib/utils';

interface CharacterPortraitProps {
  character: Character;
  conversationId?: string;
  /** Called once when a portrait reaches "complete" (avatar link updated server-side). */
  onPortraitComplete?: () => void;
}

const POLL_INTERVAL_MS = 5000;

export function CharacterPortrait({ character, conversationId, onPortraitComplete }: CharacterPortraitProps) {
  const { images, refetch } = useGeneratedImages(conversationId ?? '', 5, character.id);
  const { generateImage, loading: generating } = useGenerateImage();
  const { image: imageEvent } = useImageGenerated(conversationId ?? '');

  const latest = images[0] ?? null;
  const isTerminal = latest?.status === 'complete' || latest?.status === 'failed' || latest?.status === 'cancelled';
  const [lastError, setLastError] = useState<string | null>(null);

  // Refetch when the backend publishes a new/updated image for this conversation.
  useEffect(() => {
    if (imageEvent) void refetch();
  }, [imageEvent, refetch]);

  // Poll while a render is in flight; stop at a terminal status.
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  useEffect(() => {
    if (isTerminal || !conversationId) return;
    pollingRef.current = setInterval(() => void refetch(), POLL_INTERVAL_MS);
    return () => {
      if (pollingRef.current) clearInterval(pollingRef.current);
    };
  }, [isTerminal, conversationId, refetch, latest?.imageId]);

  const handleGenerate = async () => {
    if (!conversationId) return;
    setLastError(null);
    try {
      await generateImage({
        conversationId,
        characterId: character.id,
        triggerType: 'character_portrait',
      });
      void refetch();
    } catch (error) {
      setLastError(error instanceof Error ? error.message : 'Failed to start portrait generation');
    }
  };

  const hasPortrait = latest?.status === 'complete' && !!latest.filePath;
  const inFlight = !isTerminal && !!latest;

  // Notify the parent exactly once per completed portrait.
  const notifiedRef = useRef<string | null>(null);
  useEffect(() => {
    if (hasPortrait && latest && notifiedRef.current !== latest.imageId) {
      notifiedRef.current = latest.imageId;
      onPortraitComplete?.();
    }
  }, [hasPortrait, latest, onPortraitComplete]);

  return (
    <section aria-label="Character portrait" className="rounded-2xl border border-border bg-card p-4">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
          Portrait
        </h3>
        {conversationId && (
          <Button
            size="sm"
            variant="outline"
            leftIcon={hasPortrait ? <RefreshCw size={13} /> : <Sparkles size={13} />}
            onClick={() => void handleGenerate()}
            isLoading={generating}
            disabled={inFlight}
          >
            {hasPortrait ? 'Regenerate' : 'Generate'}
          </Button>
        )}
      </div>

      <div className="mt-3">
        {hasPortrait ? (
          <img
            src={latest!.filePath}
            alt={`${character.name} portrait`}
            className="aspect-square w-full max-w-64 rounded-xl border border-border object-cover"
          />
        ) : inFlight ? (
          <div className="flex aspect-square w-full max-w-64 flex-col items-center justify-center gap-2 rounded-xl border border-border bg-muted/30">
            <Loader2 size={22} className="animate-spin text-muted-foreground" />
            <p className="text-xs text-muted-foreground">Rendering portrait…</p>
          </div>
        ) : latest?.status === 'failed' ? (
          <div className="flex aspect-square w-full max-w-64 flex-col items-center justify-center gap-2 rounded-xl border border-destructive/30 bg-destructive/5 p-4 text-center">
            <AlertTriangle size={20} className="text-destructive" />
            <p className="text-xs text-destructive">
              The last portrait render failed. Check the ComfyUI server in Settings → ComfyUI and try again.
            </p>
          </div>
        ) : (
          <div className="flex aspect-square w-full max-w-64 flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-border bg-muted/20">
            <Bot size={24} className="text-muted-foreground/60" />
            <p className="px-4 text-center text-xs text-muted-foreground">
              {conversationId
                ? 'No portrait yet. Generate one to give this character a face.'
                : 'Open a story with this character to generate a portrait.'}
            </p>
          </div>
        )}
      </div>

      {(lastError || (latest?.status === 'failed' && lastError)) && (
        <p role="alert" className={cn('mt-2 text-xs text-destructive')}>
          {lastError}
        </p>
      )}
    </section>
  );
}
