import { ReactNode } from 'react';
import {
  Search, MessageSquare, Users, Inbox, Database,
  Heart, Bell,
  Filter, AlertCircle, WifiOff, Lock, UserX, FileX,
  MessageCircleOff, SearchX, Package, Zap, Plus
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from './Button';

interface EmptyStateProps {
  icon?: ReactNode;
  title: string;
  description?: string;
  action?: {
    label: string;
    onClick: () => void;
  };
  secondaryAction?: {
    label: string;
    onClick: () => void;
  };
  className?: string;
  size?: 'sm' | 'md' | 'lg';
}

export const EmptyState = ({
  icon,
  title,
  description,
  action,
  secondaryAction,
  className,
  size = 'md',
}: EmptyStateProps) => {
  const sizeClasses = {
    sm: {
      container: 'py-8 px-4',
      icon: 'h-9 w-9',
      title: 'text-sm',
      description: 'text-xs',
    },
    md: {
      container: 'py-12 px-6',
      icon: 'h-10 w-10',
      title: 'text-sm',
      description: 'text-sm',
    },
    lg: {
      container: 'py-16 px-8',
      icon: 'h-12 w-12',
      title: 'text-base',
      description: 'text-sm',
    },
  };

  const sizes = sizeClasses[size];

  return (
    <div className={cn(
      'flex flex-col items-center justify-center text-center',
      sizes.container,
      className
    )}>
      {icon && (
        <div className={cn(
          'bg-primary/10 text-primary mb-4 flex shrink-0 items-center justify-center rounded-2xl p-2 [&>svg]:max-h-full [&>svg]:max-w-full',
          sizes.icon
        )}>
          {icon}
        </div>
      )}

      <h3 className={cn(
        'text-foreground font-semibold',
        sizes.title
      )}>
        {title}
      </h3>

      {description && (
        <p className={cn(
          'text-muted-foreground mt-1 max-w-sm',
          sizes.description
        )}>
          {description}
        </p>
      )}

      {(action || secondaryAction) && (
        <div className="mt-6 flex flex-wrap items-center justify-center gap-2">
          {action && (
            <Button variant="primary" onClick={action.onClick}>
              {action.label}
            </Button>
          )}
          {secondaryAction && (
            <Button variant="outline" onClick={secondaryAction.onClick}>
              {secondaryAction.label}
            </Button>
          )}
        </div>
      )}
    </div>
  );
};

// Pre-configured empty states for common scenarios

export const NoConversations = ({ onCreateNew }: { onCreateNew?: () => void }) => (
  <EmptyState
    icon={<MessageSquare className="h-full w-full" />}
    title="No conversations yet"
    description="Start a new conversation to begin chatting"
    action={onCreateNew ? {
      label: "Start New Conversation",
      onClick: onCreateNew
    } : undefined}
  />
);

export const NoMessages = () => (
  <EmptyState
    icon={<MessageCircleOff className="h-full w-full" />}
    title="No messages yet"
    description="Send a message to start the conversation"
    size="sm"
  />
);

export const NoSearchResults = ({ query, onClear }: { query?: string; onClear?: () => void }) => (
  <EmptyState
    icon={<SearchX className="h-full w-full" />}
    title="No results found"
    description={query ? `No results for "${query}". Try different keywords.` : "Try adjusting your search terms"}
    action={onClear ? {
      label: "Clear Search",
      onClick: onClear
    } : undefined}
  />
);

export const NoCharacters = ({ onCreate }: { onCreate?: () => void }) => (
  <EmptyState
    icon={<Users className="h-full w-full" />}
    title="No characters available"
    description="Create or import characters to use in conversations"
    action={onCreate ? {
      label: "Create Character",
      onClick: onCreate
    } : undefined}
  />
);

export const NoData = () => (
  <EmptyState
    icon={<Database className="h-full w-full" />}
    title="No data available"
    description="There's no data to display at the moment"
  />
);

export const NoFiles = ({ onUpload }: { onUpload?: () => void }) => (
  <EmptyState
    icon={<FileX className="h-full w-full" />}
    title="No files uploaded"
    description="Upload files to get started"
    action={onUpload ? {
      label: "Upload Files",
      onClick: onUpload
    } : undefined}
  />
);

export const NoNotifications = () => (
  <EmptyState
    icon={<Bell className="h-full w-full" />}
    title="All caught up!"
    description="You have no new notifications"
    size="sm"
  />
);

export const NoFavorites = () => (
  <EmptyState
    icon={<Heart className="h-full w-full" />}
    title="No favorites yet"
    description="Items you favorite will appear here"
    size="sm"
  />
);

export const NetworkError = ({ onRetry }: { onRetry?: () => void }) => (
  <EmptyState
    icon={<WifiOff className="h-full w-full text-destructive" />}
    title="Connection lost"
    description="Please check your internet connection and try again"
    action={onRetry ? {
      label: "Retry",
      onClick: onRetry
    } : undefined}
  />
);

export const AccessDenied = () => (
  <EmptyState
    icon={<Lock className="h-full w-full text-warning" />}
    title="Access denied"
    description="You don't have permission to view this content"
  />
);

export const UserNotFound = () => (
  <EmptyState
    icon={<UserX className="h-full w-full" />}
    title="User not found"
    description="The user you're looking for doesn't exist or has been removed"
  />
);

// Illustration-based empty state
interface IllustratedEmptyStateProps extends EmptyStateProps {
  illustration?: 'search' | 'empty' | 'error' | 'success' | 'maintenance';
}

export const IllustratedEmptyState = ({
  illustration = 'empty',
  ...props
}: IllustratedEmptyStateProps) => {
  const illustrations = {
    search: (
      <div className="relative h-32 w-32">
        <div className="absolute inset-0 rounded-full bg-muted/50" />
        <Search className="absolute inset-4 text-muted-foreground" />
      </div>
    ),
    empty: (
      <div className="relative h-32 w-32">
        <div className="absolute inset-0 rounded-2xl bg-muted/50" />
        <Inbox className="absolute inset-4 text-muted-foreground" />
      </div>
    ),
    error: (
      <div className="relative h-32 w-32">
        <div className="absolute inset-0 rounded-full bg-destructive/10" />
        <AlertCircle className="absolute inset-4 text-destructive" />
      </div>
    ),
    success: (
      <div className="relative h-32 w-32">
        <div className="absolute inset-0 rounded-full bg-success/10" />
        <Zap className="absolute inset-4 text-success" />
      </div>
    ),
    maintenance: (
      <div className="relative h-32 w-32">
        <div className="absolute inset-0 rounded-full bg-warning/10" />
        <Package className="absolute inset-4 text-warning" />
      </div>
    ),
  };

  return (
    <EmptyState
      {...props}
      icon={illustrations[illustration]}
    />
  );
};

// Grid empty state for card layouts
interface GridEmptyStateProps {
  title: string;
  description?: string;
  onCreate?: () => void;
  createLabel?: string;
  className?: string;
}

export const GridEmptyState = ({
  title,
  description,
  onCreate,
  createLabel = "Create New",
  className,
}: GridEmptyStateProps) => {
  return (
    <div className={cn(
      'rounded-2xl border-2 border-dashed border-border bg-muted/20',
      'flex min-h-[200px] items-center justify-center p-8',
      className
    )}>
      <div className="text-center">
        <h3 className="text-sm font-medium text-foreground">{title}</h3>
        {description && (
          <p className="mt-1 text-xs text-muted-foreground">{description}</p>
        )}
        {onCreate && (
          <button
            onClick={onCreate}
            className={cn(
              'mt-4 inline-flex items-center gap-2 rounded-lg',
              'bg-muted px-3 py-1.5 text-xs font-medium text-foreground',
              'hover:bg-accent transition-colors duration-150'
            )}
          >
            <Plus size={14} />
            <span>{createLabel}</span>
          </button>
        )}
      </div>
    </div>
  );
};

// List empty state
interface ListEmptyStateProps {
  icon?: ReactNode;
  message: string;
  className?: string;
}

export const ListEmptyState = ({
  icon,
  message,
  className,
}: ListEmptyStateProps) => {
  return (
    <div className={cn(
      'flex items-center justify-center py-8 text-muted-foreground',
      className
    )}>
      {icon && <div className="mr-2">{icon}</div>}
      <span className="text-sm">{message}</span>
    </div>
  );
};

// Inline empty message
export const InlineEmpty = ({ message }: { message: string }) => (
  <p className="py-4 text-center text-sm text-muted-foreground">{message}</p>
);

// Filter empty state
export const NoFilterResults = ({ onReset }: { onReset: () => void }) => (
  <EmptyState
    icon={<Filter className="h-full w-full" />}
    title="No results match your filters"
    description="Try adjusting or clearing your filters"
    action={{
      label: "Clear Filters",
      onClick: onReset
    }}
    size="sm"
  />
);
