import type { LucideIcon } from 'lucide-react';
import { cn } from '@/lib/utils';

export interface PageTab {
  id: string;
  label: string;
  icon?: LucideIcon;
}

export interface PageTabsProps {
  /** Accessible name for the tab group, such as "Community sections". */
  label: string;
  tabs: PageTab[];
  value: string;
  onChange: (id: string) => void;
  className?: string;
}

/**
 * The tab strip that sits inside a PageHeader. The active tab carries a 2px
 * sienna underline on the header's own hairline.
 */
export const PageTabs = ({ label, tabs, value, onChange, className }: PageTabsProps) => (
  <nav aria-label={label} className={cn('flex flex-wrap gap-1', className)}>
    {tabs.map(({ id, label: tabLabel, icon: Icon }) => {
      const isActive = id === value;
      return (
        <button
          key={id}
          type="button"
          aria-current={isActive ? 'page' : undefined}
          onClick={() => onChange(id)}
          className={cn(
            'relative flex h-11 shrink-0 items-center gap-2 px-3 text-sm font-medium transition-colors',
            isActive ? 'text-primary' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {Icon && <Icon size={16} aria-hidden="true" />}
          {tabLabel}
          {isActive && (
            <span className="bg-primary absolute inset-x-0 -bottom-px h-0.5" aria-hidden="true" />
          )}
        </button>
      );
    })}
  </nav>
);
