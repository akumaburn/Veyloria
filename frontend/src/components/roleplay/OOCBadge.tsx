import { cn } from '@/lib/utils';

interface OOCBadgeProps {
  className?: string;
}

export default function OOCBadge({ className }: OOCBadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wider',
        'bg-border/30 text-muted-foreground border border-border/40',
        className
      )}
      title="Out of Character"
    >
      OOC
    </span>
  );
}
