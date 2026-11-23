import { ReactNode } from 'react';
import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';

// Spinner Component
interface SpinnerProps {
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl';
  className?: string;
  color?: 'primary' | 'secondary' | 'white' | 'muted';
}

export const Spinner = ({ size = 'md', className, color = 'primary' }: SpinnerProps) => {
  const sizeClasses = {
    xs: 'h-3 w-3',
    sm: 'h-4 w-4',
    md: 'h-6 w-6',
    lg: 'h-8 w-8',
    xl: 'h-12 w-12',
  };

  const colorClasses = {
    primary: 'text-primary',
    secondary: 'text-secondary',
    white: 'text-white',
    muted: 'text-muted-foreground',
  };

  return (
    <Loader2
      className={cn(
        'animate-spin',
        sizeClasses[size],
        colorClasses[color],
        className
      )}
    />
  );
};

// Loading Overlay Component
interface LoadingOverlayProps {
  visible: boolean;
  message?: string;
  className?: string;
  blur?: boolean;
}

export const LoadingOverlay = ({
  visible,
  message,
  className,
  blur = true
}: LoadingOverlayProps) => {
  if (!visible) return null;

  return (
    <div
      className={cn(
        'absolute inset-0 z-50 flex items-center justify-center',
        blur && 'backdrop-blur-sm',
        'bg-black/50',
        className
      )}
    >
      <div className="rounded-2xl border border-border/50 bg-card p-6 shadow-2xl">
        <div className="flex flex-col items-center gap-3">
          <Spinner size="lg" />
          {message && (
            <p className="text-sm text-muted-foreground">{message}</p>
          )}
        </div>
      </div>
    </div>
  );
};

// Page Loading Component
interface PageLoadingProps {
  message?: string;
  className?: string;
}

export const PageLoading = ({ message = 'Loading...', className }: PageLoadingProps) => {
  return (
    <div className={cn(
      'flex min-h-screen items-center justify-center bg-background',
      className
    )}>
      <div className="text-center">
        <div className="relative mx-auto mb-4">
          <Spinner size="xl" />
          <div className="absolute inset-0 animate-pulse-ring rounded-full border-2 border-primary/30" />
        </div>
        <p className="text-muted-foreground">{message}</p>
      </div>
    </div>
  );
};

// Content Loading Component
interface ContentLoadingProps {
  lines?: number;
  className?: string;
}

export const ContentLoading = ({ lines = 3, className }: ContentLoadingProps) => {
  return (
    <div className={cn('space-y-3 p-4', className)}>
      {Array.from({ length: lines }, (_, i) => (
        <div key={i} className="animate-pulse">
          <div
            className="h-4 rounded-lg bg-muted/50"
            style={{ width: `${85 - i * 10}%` }}
          />
        </div>
      ))}
    </div>
  );
};

// Skeleton Components
export const SkeletonText = ({ className }: { className?: string }) => (
  <div className={cn('h-4 animate-pulse rounded-lg bg-muted/50', className)} />
);

export const SkeletonAvatar = ({ size = 'md' }: { size?: 'sm' | 'md' | 'lg' }) => {
  const sizeClasses = {
    sm: 'h-8 w-8',
    md: 'h-10 w-10',
    lg: 'h-12 w-12',
  };

  return (
    <div className={cn(
      'animate-pulse rounded-xl bg-muted/50',
      sizeClasses[size]
    )} />
  );
};

export const SkeletonCard = ({ className }: { className?: string }) => (
  <div className={cn(
    'animate-pulse rounded-2xl border border-border/50 bg-card p-4',
    className
  )}>
    <div className="mb-3 h-6 w-2/3 rounded-lg bg-muted/50" />
    <div className="space-y-2">
      <div className="h-4 rounded-lg bg-muted/50" />
      <div className="h-4 w-5/6 rounded-lg bg-muted/50" />
    </div>
  </div>
);

// Message Loading Component (for chat)
export const MessageLoading = () => {
  return (
    <div className="flex items-start gap-3 p-4">
      <SkeletonAvatar size="sm" />
      <div className="flex-1 space-y-2">
        <div className="h-3 w-24 animate-pulse rounded-lg bg-muted/50" />
        <div className="space-y-1">
          <div className="h-4 w-3/4 animate-pulse rounded-lg bg-muted/50" />
          <div className="h-4 w-1/2 animate-pulse rounded-lg bg-muted/50" />
        </div>
      </div>
    </div>
  );
};

// List Loading Component
interface ListLoadingProps {
  items?: number;
  className?: string;
}

export const ListLoading = ({ items = 5, className }: ListLoadingProps) => {
  return (
    <div className={cn('space-y-2', className)}>
      {Array.from({ length: items }, (_, i) => (
        <div key={i} className="flex items-center gap-3 p-3">
          <SkeletonAvatar size="sm" />
          <div className="flex-1 space-y-2">
            <SkeletonText className="w-1/3" />
            <SkeletonText className="w-3/4" />
          </div>
        </div>
      ))}
    </div>
  );
};

// Table Loading Component
interface TableLoadingProps {
  rows?: number;
  columns?: number;
  className?: string;
}

export const TableLoading = ({ rows = 5, columns = 4, className }: TableLoadingProps) => {
  return (
    <div className={cn('w-full', className)}>
      <div className="mb-4 grid grid-cols-4 gap-4 border-b border-border/50 pb-3">
        {Array.from({ length: columns }, (_, i) => (
          <SkeletonText key={i} className="h-3" />
        ))}
      </div>
      <div className="space-y-3">
        {Array.from({ length: rows }, (_, i) => (
          <div key={i} className="grid grid-cols-4 gap-4">
            {Array.from({ length: columns }, (_, j) => (
              <SkeletonText key={j} className="h-4" />
            ))}
          </div>
        ))}
      </div>
    </div>
  );
};

// Button Loading State
interface ButtonLoadingProps {
  loading: boolean;
  children: ReactNode;
  loadingText?: string;
  className?: string;
  onClick?: () => void;
  disabled?: boolean;
}

export const ButtonLoading = ({
  loading,
  children,
  loadingText = 'Loading...',
  className,
  onClick,
  disabled,
}: ButtonLoadingProps) => {
  return (
    <button
      onClick={onClick}
      disabled={loading || disabled}
      className={cn(
        'relative flex items-center justify-center rounded-xl px-4 py-2',
        'transition-all duration-150',
        'disabled:cursor-not-allowed disabled:opacity-50',
        className
      )}
    >
      {loading ? (
        <>
          <Spinner size="sm" className="mr-2" />
          <span>{loadingText}</span>
        </>
      ) : (
        children
      )}
    </button>
  );
};

// Lazy Loading Component
interface LazyLoadingProps {
  loading: boolean;
  error?: Error | null;
  children: ReactNode;
  fallback?: ReactNode;
  errorFallback?: ReactNode;
}

export const LazyLoading = ({
  loading,
  error,
  children,
  fallback,
  errorFallback,
}: LazyLoadingProps) => {
  if (error) {
    return (
      <>
        {errorFallback || (
          <div className="flex items-center justify-center p-8">
            <div className="text-center">
              <p className="text-destructive">Failed to load</p>
              <button
                onClick={() => window.location.reload()}
                className="mt-2 text-sm text-primary hover:text-primary-hover transition-colors"
              >
                Retry
              </button>
            </div>
          </div>
        )}
      </>
    );
  }

  if (loading) {
    return <>{fallback || <ContentLoading />}</>;
  }

  return <>{children}</>;
};

// Progress Bar Component
interface ProgressBarProps {
  value: number;
  max?: number;
  label?: string;
  showPercentage?: boolean;
  color?: 'primary' | 'success' | 'secondary' | 'destructive';
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

export const ProgressBar = ({
  value,
  max = 100,
  label,
  showPercentage = false,
  color = 'primary',
  size = 'md',
  className,
}: ProgressBarProps) => {
  const percentage = Math.min(100, (value / max) * 100);

  const sizeClasses = {
    sm: 'h-1',
    md: 'h-2',
    lg: 'h-3',
  };

  const colorClasses = {
    primary: 'bg-primary',
    success: 'bg-success',
    secondary: 'bg-secondary',
    destructive: 'bg-destructive',
  };

  return (
    <div className={cn('w-full', className)}>
      {(label || showPercentage) && (
        <div className="mb-1 flex justify-between text-xs text-muted-foreground">
          {label && <span>{label}</span>}
          {showPercentage && <span>{Math.round(percentage)}%</span>}
        </div>
      )}
      <div className={cn('w-full rounded-full bg-muted/50', sizeClasses[size])}>
        <div
          className={cn(
            'h-full rounded-full transition-all duration-300',
            colorClasses[color]
          )}
          style={{ width: `${percentage}%` }}
        />
      </div>
    </div>
  );
};

// Circular Progress Component
interface CircularProgressProps {
  value: number;
  max?: number;
  size?: number;
  strokeWidth?: number;
  color?: string;
  showLabel?: boolean;
  className?: string;
}

export const CircularProgress = ({
  value,
  max = 100,
  size = 60,
  strokeWidth = 4,
  color = 'var(--color-primary)',
  showLabel = true,
  className,
}: CircularProgressProps) => {
  const percentage = Math.min(100, (value / max) * 100);
  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (percentage / 100) * circumference;

  return (
    <div className={cn('relative inline-flex items-center justify-center', className)}>
      <svg
        width={size}
        height={size}
        className="rotate-[-90deg]"
      >
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          stroke="currentColor"
          strokeWidth={strokeWidth}
          fill="none"
          className="text-muted/50"
        />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          stroke={color}
          strokeWidth={strokeWidth}
          fill="none"
          strokeDasharray={circumference}
          strokeDashoffset={offset}
          strokeLinecap="round"
          className="transition-all duration-300"
        />
      </svg>
      {showLabel && (
        <div className="absolute text-xs font-medium text-foreground">
          {Math.round(percentage)}%
        </div>
      )}
    </div>
  );
};

// Dots Loading Animation
export const DotsLoading = ({ className }: { className?: string }) => {
  return (
    <div className={cn('flex gap-1', className)}>
      {[0, 1, 2].map((i) => (
        <div
          key={i}
          className="h-2 w-2 rounded-full bg-primary animate-typing-dot"
          style={{ animationDelay: `${i * 150}ms` }}
        />
      ))}
    </div>
  );
};

// Shimmer effect loading
export const ShimmerLoading = ({ className }: { className?: string }) => (
  <div className={cn(
    'relative overflow-hidden rounded-xl bg-muted/50',
    'before:absolute before:inset-0 before:-translate-x-full',
    'before:animate-shimmer before:bg-gradient-to-r',
    'before:from-transparent before:via-white/10 before:to-transparent',
    className
  )} />
);

// Alias for backward compatibility
export const LoadingSpinner = Spinner;
