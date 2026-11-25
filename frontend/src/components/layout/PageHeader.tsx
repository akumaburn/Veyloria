import type { ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';
import { cn } from '@/lib/utils';

export interface PageHeaderProps {
  /** Page title. Rendered as the page's only h1. */
  title: ReactNode;
  /** Supporting line under the title, set in muted body type. */
  description?: ReactNode;
  /** Section icon, drawn in a 40px well. Decorative; the title carries the name. */
  icon?: LucideIcon;
  /** Control before the icon, such as a back button or an account initial. */
  lead?: ReactNode;
  /** Controls on the trailing edge. They wrap below the title on narrow screens. */
  actions?: ReactNode;
  /**
   * Max width of the header content. Pass the same measure as the page content
   * so the title and the first card share a left edge.
   */
  measure?: string;
  /** A rule-separated strip under the title row, such as page tabs. */
  children?: ReactNode;
  /** Keeps the bar at the top of the window. Used outside the workspace shell. */
  sticky?: boolean;
  className?: string;
}

export const PageHeader = ({
  title,
  description,
  icon: Icon,
  lead,
  actions,
  measure,
  children,
  sticky = false,
  className,
}: PageHeaderProps) => (
  <header
    className={cn(
      'border-border bg-background-secondary shrink-0 border-b',
      sticky && 'sticky top-0 z-10',
      className,
    )}
  >
    <div className="px-4 sm:px-6">
      <div
        className={cn(
          'mx-auto flex flex-wrap items-center justify-between gap-x-4 gap-y-3 py-3 sm:py-4',
          measure,
        )}
      >
        <div className="flex min-w-0 items-center gap-3">
          {lead}
          {Icon && (
            <span
              className="bg-primary/10 text-primary flex h-10 w-10 shrink-0 items-center justify-center rounded-lg"
              aria-hidden="true"
            >
              <Icon size={20} />
            </span>
          )}
          <div className="min-w-0">
            <h1 className="font-display text-foreground text-2xl leading-tight font-normal text-balance break-words">
              {title}
            </h1>
            {description && (
              <p className="text-muted-foreground mt-1 text-sm break-words">{description}</p>
            )}
          </div>
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </div>
    {children && (
      <div className="border-border border-t px-4 sm:px-6">
        <div className={cn('mx-auto', measure)}>{children}</div>
      </div>
    )}
  </header>
);
