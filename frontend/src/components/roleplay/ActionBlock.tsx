import { Swords } from 'lucide-react';
import { cn } from '@/lib/utils';

interface ActionBlockProps {
  content: string;
  characterName: string;
  characterAvatar?: string;
  timestamp?: string;
  className?: string;
  onAvatarClick?: () => void;
}

export default function ActionBlock({
  content,
  characterName,
  characterAvatar,
  timestamp,
  className,
  onAvatarClick,
}: ActionBlockProps) {
  const avatar = (
    <span
      className={cn(
        'flex h-5 w-5 flex-shrink-0 items-center justify-center overflow-hidden rounded-full bg-teal-500/20',
      )}
    >
      {characterAvatar ? (
        <img src={characterAvatar} alt={characterName} className="h-5 w-5 rounded-full object-cover" />
      ) : (
        <span className="text-[10px] font-bold uppercase text-teal-400">
          {characterName.charAt(0)}
        </span>
      )}
    </span>
  );
  return (
    <div
      className={cn(
        'relative rounded-lg border border-teal-500/20 bg-teal-500/5 px-3 py-2.5 sm:px-4 sm:py-3',
        className
      )}
    >
      {/* Left accent bar */}
      <div className="absolute inset-y-0 left-0 w-1 rounded-l-lg bg-teal-500/40" />

      {/* Character header */}
      <div className="mb-1.5 flex min-w-0 items-center gap-2">
        {onAvatarClick ? (
          <button
            type="button"
            onClick={onAvatarClick}
            className="flex-shrink-0 cursor-pointer rounded-full focus:outline-none focus-visible:ring-2 focus-visible:ring-teal-500 max-md:-m-2.5 max-md:p-2.5"
            aria-label={`Open ${characterName}'s character card`}
            title={`Open ${characterName}'s character card`}
          >
            {avatar}
          </button>
        ) : (
          avatar
        )}
        <span className="truncate text-xs font-semibold text-teal-400">{characterName}</span>
        <Swords size={12} className="shrink-0 text-teal-400/50" />
      </div>

      {/* Action text */}
      <p className="whitespace-pre-wrap break-words text-sm leading-relaxed text-foreground/90">
        <span className="text-teal-400/70">*</span>
        <span className="italic">{content}</span>
        <span className="text-teal-400/70">*</span>
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
