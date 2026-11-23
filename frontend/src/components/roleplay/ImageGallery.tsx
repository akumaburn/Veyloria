import { useState, useEffect } from 'react';
import { useGeneratedImages, useGenerateImage, useCancelImageGeneration, useImageGenerationProgress, useImageGenerated } from '@/hooks/useRoleplay';
import { type GeneratedImage as GeneratedImageType } from '@/graphql/types';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Textarea } from '@/components/ui/Textarea';
import GeneratedImage from './GeneratedImage';
import { Plus, ImageIcon, Loader2, XCircle } from 'lucide-react';

interface ImageGalleryProps {
  conversationId: string;
}

export default function ImageGallery({ conversationId }: ImageGalleryProps) {
  const { images, loading, refetch } = useGeneratedImages(conversationId);
  const { generateImage, loading: generating } = useGenerateImage();
  const { cancelImageGeneration, loading: cancelling } = useCancelImageGeneration();
  const { progress } = useImageGenerationProgress(conversationId);
  const { image: imageEvent } = useImageGenerated(conversationId);
  const [isFormOpen, setIsFormOpen] = useState(false);

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

  if (loading) {
    return (
      <div className="flex h-48 items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-foreground">Image Gallery</h3>
        <Button size="sm" onClick={() => setIsFormOpen(true)} leftIcon={<Plus size={14} />}>
          Generate Image
        </Button>
      </div>

      {/* Generation progress indicator */}
      {progress && progress.status === 'running' && (
        <div className="rounded-lg border border-primary/30 bg-primary/5 p-3">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-medium text-foreground">Generating...</span>
            <button
              onClick={() => cancelImageGeneration(progress.promptId)}
              disabled={cancelling}
              className="text-xs text-destructive hover:text-destructive/80 flex items-center gap-1"
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
        <div className="columns-2 gap-3 space-y-3 sm:columns-3">
          {images.map((image: GeneratedImageType) => (
            <div key={image.imageId} className="break-inside-avoid">
              <GeneratedImage image={image} />
            </div>
          ))}
        </div>
      )}

      {/* Generate form modal */}
      <Modal
        isOpen={isFormOpen}
        onClose={() => setIsFormOpen(false)}
        title="Generate New Image"
        footer={
          <div className="flex justify-end gap-3">
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
