import { cn } from '@/lib/utils';

/**
 * The visual half of a switch, for controls whose owner already carries the
 * switch semantics (a role="switch" button or a menu checkbox item).
 */
export function SwitchTrack({ checked, className }: { checked: boolean; className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'relative inline-flex h-5 w-9 shrink-0 rounded-full transition-colors duration-200',
        checked ? 'bg-primary' : 'bg-muted ring-1 ring-inset ring-border',
        className,
      )}
    >
      <span
        className={cn(
          'absolute left-0.5 top-0.5 h-4 w-4 rounded-full shadow-sm transition-transform duration-200',
          checked ? 'translate-x-4 bg-primary-foreground' : 'bg-muted-foreground',
        )}
      />
    </span>
  );
}
