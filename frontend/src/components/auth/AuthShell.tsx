import React from 'react';
import { Link } from 'react-router-dom';
import { cn } from '@/lib/utils';
import { BrandMark } from '@/components/layout/WorkspaceNavigation';

interface AuthShellProps {
  title: string;
  subtitle: string;
  children: React.ReactNode;
  footerPrompt: string;
  footerLink: { to: string; label: string };
  legal: React.ReactNode;
}

/**
 * The public authentication frame. Both sign-in and registration use one
 * atmosphere, one brand lockup, and one card so the pair reads as a set.
 */
export const AuthShell: React.FC<AuthShellProps> = ({
  title,
  subtitle,
  children,
  footerPrompt,
  footerLink,
  legal,
}) => (
  <div className="relative flex min-h-screen items-start justify-center overflow-x-hidden bg-background py-12 sm:items-center">
    {/* Background effects: the one atmospheric surface in the product. */}
    <div className="pointer-events-none absolute inset-0" aria-hidden="true">
      <div className="absolute inset-0 bg-gradient-to-br from-primary/5 via-transparent to-secondary/5" />
      <div className="absolute top-1/4 left-1/4 h-96 w-96 rounded-full bg-primary/10 blur-3xl" />
      <div className="absolute right-1/4 bottom-1/4 h-96 w-96 rounded-full bg-secondary/10 blur-3xl" />
    </div>

    <div className="relative z-10 w-full max-w-md px-6">
      <div className="mb-8 flex flex-col items-center">
        <div className="flex items-center gap-3">
          <BrandMark className="scale-125" />
          <h1 className="text-3xl font-bold text-foreground">
            Veyloria<span className="text-brand-orange">_</span>
          </h1>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">AI-run fantasy RPG</p>
      </div>

      <div
        className={cn(
          'rounded-2xl border border-border/50 bg-card p-5 sm:p-8',
          'shadow-2xl shadow-black/10',
        )}
      >
        <div className="mb-6 text-center">
          <h2 className="font-display text-foreground text-3xl leading-tight font-normal tracking-[0.015em]">
            {title}
          </h2>
          <p className="text-muted-foreground mt-1 text-sm">{subtitle}</p>
        </div>

        {children}

        <div className="mt-6">
          <div className="relative">
            <div className="absolute inset-0 flex items-center">
              <div className="w-full border-t border-border" />
            </div>
            <div className="relative flex justify-center text-sm">
              <span className="bg-card px-3 text-muted-foreground">{footerPrompt}</span>
            </div>
          </div>

          <Link
            to={footerLink.to}
            className={cn(
              'mt-4 flex w-full items-center justify-center gap-2',
              'rounded-xl border border-border bg-muted/50',
              'px-4 py-3 text-sm font-medium text-foreground',
              'transition-colors duration-150 hover:bg-accent',
            )}
          >
            {footerLink.label}
          </Link>
        </div>
      </div>

      <div className="mt-8 text-center">
        <p className="text-muted-foreground text-xs text-pretty">{legal}</p>
      </div>
    </div>
  </div>
);
