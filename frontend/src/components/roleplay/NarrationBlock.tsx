import { BookOpen } from 'lucide-react';
import { cn } from '@/lib/utils';

interface NarrationBlockProps {
  content: string;
  sceneDescription?: string;
  timestamp?: string;
  className?: string;
}

export default function NarrationBlock({
  content,
  sceneDescription,
  timestamp,
  className,
}: NarrationBlockProps) {
  return (
    <div
      className={cn(
        'relative rounded-lg border border-amber-500/20 bg-amber-500/5 px-4 py-3',
        className
      )}
    >
      {/* Scene description header */}
      {sceneDescription && (
        <div className="mb-2 flex items-center gap-1.5 border-b border-amber-500/10 pb-2">
          <BookOpen size={14} className="flex-shrink-0 text-amber-400/70" />
          <p className="text-xs font-medium uppercase tracking-wider text-amber-400/70">
            {sceneDescription}
          </p>
        </div>
      )}

      {/* Narration text */}
      <p className="whitespace-pre-wrap text-sm italic leading-relaxed text-foreground/90">
        {content}
      </p>

      {/* Timestamp */}
      {timestamp && (
        <p className="mt-2 text-right text-[10px] text-muted-foreground">
          {timestamp}
        </p>
      )}
    </div>
  );
}
