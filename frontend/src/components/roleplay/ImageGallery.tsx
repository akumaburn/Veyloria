import { useState, useEffect } from 'react';
import { useGeneratedImages, useGenerateImage, useCancelImageGeneration, useImageGenerationProgress, useImageGenerated } from '@/hooks/useRoleplay';
import { type GeneratedImage as GeneratedImageType } from '@/graphql/types';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Textarea } from '@/components/ui/Textarea';
import GeneratedImage from './GeneratedImage';
import { Plus, ImageIcon, Loader2, XCircle, User, Mountain } from 'lucide-react';

interface ImageGalleryProps {
  conversationId: string;
  participants?: Array<{
    character: { id: string; name: string; avatar?: string };
    isActive: boolean;
  }>;
}

export default function ImageGallery({ conversationId, participants }: ImageGalleryProps) {
  const { images, loading, refetch } = useGeneratedImages(conversationId);
  const { generateImage, loading: generating } = useGenerateImage();
  const { cancelImageGeneration, loading: cancelling } = useCancelImageGeneration();
  const { progress } = useImageGenerationProgress(conversationId);
  const { image: imageEvent } = useImageGenerated(conversationId);
  const [isFormOpen, setIsFormOpen] = useState(false);

  // Characters available for portrait renders (active participants only).
  const characters = (participants ?? []).filter((p) => p.isActive).map((p) => p.character);
  const [portraitCharacterId, setPortraitCharacterId] = useState('');
  const effectivePortraitId =
    portraitCharacterId || (characters.length === 1 ? characters[0].id : '');

  // Auto-refresh gallery when new images are generated
  useEffect(() => {
    if (imageEvent) refetch();
  }, [imageEvent, refetch]);
  const [prompt, setPrompt] = useState('');
  const [negativePrompt, setNegativePrompt] = useState('');
  const [width, setWidth] = useState('512');
  const [height, setHeight] = useState('512');

  const handleGenerate = async () => {
    if (!prompt.trim()) return;
    try {
      await generateImage({
        conversationId,
        prompt: prompt.trim(),
        negativePrompt: negativePrompt.trim() || undefined,
        width: parseInt(width) || 512,
        height: parseInt(height) || 512,
      });
      setPrompt('');
      setNegativePrompt('');
      setIsFormOpen(false);
    } catch {
      // Keep form open with user's prompt on error
    }
  };

  // Workflow quick actions: fixed ComfyUI templates, no free-form prompt.
  const handleGeneratePortrait = async () => {
    if (!effectivePortraitId) return;
    try {
      await generateImage({
        conversationId,
        characterId: effectivePortraitId,
        triggerType: 'character_portrait',
      });
      void refetch();
    } catch {
      // progress/row state surfaces the failure; keep the panel usable
    }
  };

  const handleGenerateScene = async () => {
    try {
      await generateImage({
        conversationId,
        triggerType: 'scene_change',
      });
      void refetch();
    } catch {
      // progress/row state surfaces the failure; keep the panel usable
    }
  };

  if (loading) {
    return (
      <div className="flex h-48 items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-foreground">Image Gallery</h3>
        <Button size="sm" className="h-10 sm:h-8" onClick={() => setIsFormOpen(true)} leftIcon={<Plus size={14} />}>
          Generate Image
        </Button>
      </div>

      {/* Workflow quick actions: fixed ComfyUI templates */}
      <div className="flex flex-wrap items-center gap-2">
        {characters.length > 0 && (
          <Button
            size="sm"
            variant="outline"
            className="h-10 sm:h-8"
            onClick={() => void handleGeneratePortrait()}
            isLoading={generating}
            disabled={!effectivePortraitId}
            leftIcon={<User size={14} />}
          >
            Portrait
          </Button>
        )}
        {characters.length > 1 && (
          <select
            aria-label="Portrait character"
            value={effectivePortraitId}
            onChange={(e) => setPortraitCharacterId(e.target.value)}
            className="h-10 rounded-lg border border-border bg-card px-2 text-xs text-foreground sm:h-8"
          >
            {characters.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        )}
        <Button
          size="sm"
          variant="outline"
          className="h-10 sm:h-8"
          onClick={() => void handleGenerateScene()}
          isLoading={generating}
          leftIcon={<Mountain size={14} />}
        >
          Scene
        </Button>
      </div>

      {/* Generation progress indicator */}
      {progress && progress.status === 'running' && (
        <div className="rounded-lg border border-primary/30 bg-primary/5 p-3">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-medium text-foreground">Generating...</span>
            <button
              type="button"
              onClick={() => cancelImageGeneration(progress.promptId)}
              disabled={cancelling}
              className="-my-2 -mr-2 flex min-h-10 items-center gap-1 rounded-md px-2 text-xs text-destructive hover:bg-destructive/10 sm:-my-1 sm:-mr-1 sm:min-h-8"
            >
              <XCircle size={12} />
              Cancel
            </button>
          </div>
          <div className="h-1.5 rounded-full bg-border/50 overflow-hidden">
            <div
              className="h-full rounded-full bg-primary transition-all duration-300"
              style={{ width: `${Math.min(Math.round(progress.progress * 100), 100)}%` }}
            />
          </div>
          {progress.currentStep != null && progress.totalSteps != null && (
            <p className="mt-1 text-[10px] text-muted-foreground">
              Step {progress.currentStep}/{progress.totalSteps}
            </p>
          )}
        </div>
      )}

      {images.length === 0 ? (
        <div className="flex flex-col items-center justify-center rounded-xl border border-border bg-muted/30 py-6">
          <ImageIcon className="mb-1.5 h-5 w-5 text-muted-foreground/70" />
          <p className="text-sm text-muted-foreground">No images generated yet</p>
        </div>
      ) : (
        <div className="@container">
          <div className="columns-2 gap-3 space-y-3 @lg:columns-3">
            {images.map((image: GeneratedImageType) => (
              <div key={image.imageId} className="break-inside-avoid">
                <GeneratedImage
                  image={image}
                  status={
                    image.status === 'failed'
                      ? 'failed'
                      : image.status === 'complete'
                        ? 'complete'
                        : image.status === 'cancelled'
                          ? 'failed'
                          : 'generating'
                  }
                />
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Generate form modal */}
      <Modal
        isOpen={isFormOpen}
        onClose={() => setIsFormOpen(false)}
        title="Generate New Image"
        footer={
          <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end sm:gap-3">
            <Button variant="ghost" onClick={() => setIsFormOpen(false)}>Cancel</Button>
            <Button onClick={handleGenerate} isLoading={generating} disabled={!prompt.trim()}>
              Generate
            </Button>
          </div>
        }
      >
        <div className="space-y-4">
          <Textarea
            label="Prompt"
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            placeholder="Describe the image you want to generate..."
            minRows={3}
          />
          <Textarea
            label="Negative Prompt (optional)"
            value={negativePrompt}
            onChange={(e) => setNegativePrompt(e.target.value)}
            placeholder="Things to avoid in the image..."
            minRows={2}
          />
          <div className="flex gap-3">
            <Input label="Width" type="number" value={width} onChange={(e) => setWidth(e.target.value)} />
            <Input label="Height" type="number" value={height} onChange={(e) => setHeight(e.target.value)} />
          </div>
        </div>
      </Modal>
    </div>
  );
}
