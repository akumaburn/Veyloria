import { useState } from 'react';
import { cn } from '@/lib/utils';
import { type GeneratedImage as GeneratedImageType } from '@/graphql/types';
import { Modal } from '@/components/ui/Modal';
import { Image, Loader2, CheckCircle, XCircle, Clock } from 'lucide-react';

interface GeneratedImageProps {
  image: GeneratedImageType;
  status?: 'pending' | 'generating' | 'complete' | 'failed';
}

const statusConfig = {
  pending: { icon: Clock, label: 'Pending', color: 'bg-warning/15 text-warning' },
  generating: { icon: Loader2, label: 'Generating', color: 'bg-primary/15 text-primary' },
  complete: { icon: CheckCircle, label: 'Complete', color: 'bg-success/15 text-success' },
  failed: { icon: XCircle, label: 'Failed', color: 'bg-destructive/15 text-destructive' },
};

export default function GeneratedImage({ image, status = 'complete' }: GeneratedImageProps) {
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [imageError, setImageError] = useState(false);
  const config = statusConfig[status];
  const StatusIcon = config.icon;

  return (
    <>
      <div
        className="group relative cursor-pointer overflow-hidden rounded-lg border border-border bg-card transition-all hover:border-border-hover hover:shadow-lg"
        onClick={() => status === 'complete' && setIsModalOpen(true)}
      >
        {/* Image or placeholder */}
        <div className="relative aspect-square w-full overflow-hidden bg-muted">
          {status === 'complete' && !imageError ? (
            <img
              src={image.filePath}
              alt={image.prompt}
              className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105"
              onError={() => setImageError(true)}
            />
          ) : (
            <div className="flex h-full items-center justify-center">
              {status === 'generating' ? (
                <Loader2 className="h-10 w-10 animate-spin text-muted-foreground" />
              ) : (
                <Image className="h-10 w-10 text-muted-foreground/70" />
              )}
            </div>
          )}
        </div>

        {/* Status badge */}
        <div className="absolute right-2 top-2">
          <span className={cn('inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium', config.color)}>
            <StatusIcon size={12} className={status === 'generating' ? 'animate-spin' : ''} />
            {config.label}
          </span>
        </div>

        {/* Info overlay */}
        <div className="p-2.5">
          <p className="line-clamp-2 text-xs text-foreground">{image.prompt}</p>
          {(image.width || image.height) && (
            <p className="mt-1 text-xs text-muted-foreground">
              {image.width}x{image.height}
              {image.seed && <span className="ml-2">Seed: {image.seed}</span>}
            </p>
          )}
        </div>
      </div>

      {/* Full-size modal */}
      <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)} size="xl" title="Generated Image">
        <div className="flex flex-col items-center gap-4">
          <img
            src={image.filePath}
            alt={image.prompt}
            className="max-h-[70vh] rounded-lg object-contain"
          />
          <div className="w-full space-y-2">
            <p className="text-sm text-foreground">{image.prompt}</p>
            {image.negativePrompt && (
              <p className="text-xs text-muted-foreground">Negative: {image.negativePrompt}</p>
            )}
            <div className="flex flex-wrap gap-3 text-xs text-muted-foreground">
              {image.width && image.height && <span>{image.width}x{image.height}</span>}
              {image.seed && <span>Seed: {image.seed}</span>}
            </div>
          </div>
        </div>
      </Modal>
    </>
  );
}
