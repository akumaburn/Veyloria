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
 * sienna underline on the header's own hairline. Phones scroll the strip
 * sideways, edge to edge, instead of wrapping it into several rows.
 */
export const PageTabs = ({ label, tabs, value, onChange, className }: PageTabsProps) => (
  <nav
    aria-label={label}
    className={cn(
      'scrollbar-hide -mx-4 flex gap-1 overflow-x-auto px-4 sm:mx-0 sm:flex-wrap sm:overflow-visible sm:px-0',
      className,
    )}
  >
    {tabs.map(({ id, label: tabLabel, icon: Icon }) => {
      const isActive = id === value;
      return (
        <button
          key={id}
          type="button"
          aria-current={isActive ? 'page' : undefined}
          onClick={() => onChange(id)}
          className={cn(
            'relative flex h-11 shrink-0 items-center gap-2 px-3 text-sm font-medium whitespace-nowrap transition-colors',
            isActive ? 'text-primary' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {Icon && <Icon size={16} aria-hidden="true" />}
          {tabLabel}
          {isActive && (
            <span
              className="bg-primary absolute inset-x-0 bottom-0 h-0.5 sm:-bottom-px"
              aria-hidden="true"
            />
          )}
        </button>
      );
    })}
  </nav>
);
