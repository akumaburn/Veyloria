import { useState, useEffect } from 'react';
import { cn, normalizeTimestamp } from '@/lib/utils';
import { Modal } from '@/components/ui/Modal';
import {
  Bell,
  Mail,
  MailOpen,
  Check,
  X,
  Loader2,
  Clock,
  MessageSquare,
  User,
  ChevronRight,
} from 'lucide-react';
import {
  useMyInvites,
  useRespondToInvite,
  ConversationInvite,
} from '@/hooks/useGraphQL';
import { formatDistanceToNow } from 'date-fns';

interface InviteNotificationBadgeProps {
  className?: string;
}

export const InviteNotificationBadge = ({ className }: InviteNotificationBadgeProps) => {
  const [showModal, setShowModal] = useState(false);
  const { invites, totalCount, loading, refetch } = useMyInvites({ status: 'PENDING' });

  // Poll for new invites periodically
  useEffect(() => {
    const interval = setInterval(() => {
      refetch();
    }, 30000); // Every 30 seconds

    return () => clearInterval(interval);
  }, [refetch]);

  if (loading && totalCount === 0) {
    return null;
  }

  return (
    <>
      <button
        onClick={() => setShowModal(true)}
        className={cn(
          'relative rounded-lg p-2',
          'text-muted-foreground hover:text-foreground',
          'hover:bg-accent transition-colors',
          className
        )}
        aria-label={`${totalCount} pending invite${totalCount !== 1 ? 's' : ''}`}
      >
        <Bell size={20} />
        {totalCount > 0 && (
          <span className="absolute -right-0.5 -top-0.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-primary text-[10px] font-bold text-primary-foreground px-1">
            {totalCount > 99 ? '99+' : totalCount}
          </span>
        )}
      </button>

      <InviteListModal
        isOpen={showModal}
        onClose={() => setShowModal(false)}
        invites={invites}
        loading={loading}
        refetch={refetch}
      />
    </>
  );
};

interface InviteListModalProps {
  isOpen: boolean;
  onClose: () => void;
  invites: ConversationInvite[];
  loading: boolean;
  refetch: () => void;
}

const InviteListModal = ({
  isOpen,
  onClose,
  invites,
  loading,
  refetch,
}: InviteListModalProps) => {
  const { respondToInvite } = useRespondToInvite();
  const [respondingTo, setRespondingTo] = useState<string | null>(null);

  const handleRespond = async (inviteId: string, accept: boolean) => {
    setRespondingTo(inviteId);
    try {
      await respondToInvite({ inviteId, accept });
      refetch();
    } catch (error) {
      console.error('Failed to respond to invite:', error);
    } finally {
      setRespondingTo(null);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Invitations"
      description="Pending conversation invitations"
      size="md"
    >
      <div className="space-y-4">
        {loading && invites.length === 0 ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 size={24} className="animate-spin text-muted-foreground" />
          </div>
        ) : invites.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-8 text-muted-foreground">
            <MailOpen size={40} className="mb-3 opacity-50" />
            <p className="font-medium">No pending invitations</p>
            <p className="text-sm mt-1">You're all caught up!</p>
          </div>
        ) : (
          <div className="space-y-3">
            {invites.map((invite) => (
              <InviteCard
                key={invite.id}
                invite={invite}
                onAccept={() => handleRespond(invite.id, true)}
                onDecline={() => handleRespond(invite.id, false)}
                isLoading={respondingTo === invite.id}
              />
            ))}
          </div>
        )}
      </div>
    </Modal>
  );
};

interface InviteCardProps {
  invite: ConversationInvite;
  onAccept: () => void;
  onDecline: () => void;
  isLoading: boolean;
}

const InviteCard = ({ invite, onAccept, onDecline, isLoading }: InviteCardProps) => {
  const [expanded, setExpanded] = useState(false);

  const timeAgo = formatDistanceToNow(new Date(normalizeTimestamp(invite.createdAt)), { addSuffix: true });
  const expiresIn = invite.expiresAt
    ? formatDistanceToNow(new Date(normalizeTimestamp(invite.expiresAt)), { addSuffix: true })
    : null;

  return (
    <div
      className={cn(
        'rounded-xl border border-border/50 bg-muted/30',
        'overflow-hidden transition-all duration-200'
      )}
    >
      {/* Header */}
      <div
        className={cn(
          'flex items-start gap-3 p-4 cursor-pointer',
          'hover:bg-muted/50 transition-colors'
        )}
        onClick={() => setExpanded(!expanded)}
      >
        {/* Inviter Avatar */}
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary/10">
          {invite.inviter.profilePicture ? (
            <img
              src={invite.inviter.profilePicture}
              alt={invite.inviter.displayName}
              className="h-10 w-10 rounded-full object-cover"
            />
          ) : (
            <User size={20} className="text-primary" />
          )}
        </div>

        {/* Content */}
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-medium text-foreground">
              {invite.inviter.displayName}
            </span>
            <span className="text-sm text-muted-foreground">invited you to</span>
          </div>
          <div className="flex items-center gap-2 mt-0.5">
            <MessageSquare size={14} className="text-muted-foreground" />
            <span className="text-sm font-medium text-foreground truncate">
              {invite.conversation.title || 'Untitled Conversation'}
            </span>
          </div>
          <div className="flex items-center gap-2 mt-1 text-xs text-muted-foreground">
            <Clock size={12} />
            <span>{timeAgo}</span>
            {expiresIn && (
              <>
                <span>·</span>
                <span>Expires {expiresIn}</span>
              </>
            )}
          </div>
        </div>

        {/* Expand Arrow */}
        <ChevronRight
          size={18}
          className={cn(
            'text-muted-foreground transition-transform',
            expanded && 'rotate-90'
          )}
        />
      </div>

      {/* Expanded Content */}
      {expanded && (
        <div className="border-t border-border/50 px-4 py-3 space-y-3">
          {/* Message */}
          {invite.message && (
            <div className="text-sm text-muted-foreground bg-muted/50 rounded-lg p-3">
              "{invite.message}"
            </div>
          )}

          {/* Role Info */}
          <div className="flex items-center gap-2 text-sm">
            <span className="text-muted-foreground">You'll join as:</span>
            <span className={cn(
              'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium',
              invite.roleToGrant === 'ADMIN'
                ? 'bg-primary/10 text-primary'
                : 'bg-muted text-muted-foreground'
            )}>
              {invite.roleToGrant === 'ADMIN' ? 'Admin' : 'Member'}
            </span>
          </div>

          {/* Actions */}
          <div className="flex gap-2 pt-1">
            <button
              onClick={(e) => {
                e.stopPropagation();
                onDecline();
              }}
              disabled={isLoading}
              className={cn(
                'flex-1 inline-flex items-center justify-center gap-2',
                'rounded-xl px-4 py-2.5 text-sm font-medium',
                'border border-border bg-muted/50',
                'text-muted-foreground hover:text-foreground hover:bg-accent',
                'disabled:opacity-50 disabled:cursor-not-allowed',
                'transition-colors duration-150'
              )}
            >
              {isLoading ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <X size={14} />
              )}
              <span>Decline</span>
            </button>
            <button
              onClick={(e) => {
                e.stopPropagation();
                onAccept();
              }}
              disabled={isLoading}
              className={cn(
                'flex-1 inline-flex items-center justify-center gap-2',
                'rounded-xl px-4 py-2.5 text-sm font-medium',
                'bg-primary text-primary-foreground',
                'hover:bg-primary-hover',
                'disabled:opacity-50 disabled:cursor-not-allowed',
                'transition-colors duration-150'
              )}
            >
              {isLoading ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <Check size={14} />
              )}
              <span>Accept</span>
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

// Standalone Pending Invites List component (for sidebar or dedicated page)
interface PendingInvitesListProps {
  className?: string;
  onNavigateToConversation?: (conversationId: string) => void;
}

export const PendingInvitesList = ({
  className,
  onNavigateToConversation,
}: PendingInvitesListProps) => {
  const { invites, loading, refetch } = useMyInvites({ status: 'PENDING' });
  const { respondToInvite } = useRespondToInvite();
  const [respondingTo, setRespondingTo] = useState<string | null>(null);

  const handleRespond = async (inviteId: string, accept: boolean, conversationId?: string) => {
    setRespondingTo(inviteId);
    try {
      const result = await respondToInvite({ inviteId, accept });
      if (accept && result.data?.respondToInvite && onNavigateToConversation && conversationId) {
        onNavigateToConversation(conversationId);
      }
      refetch();
    } catch (error) {
      console.error('Failed to respond to invite:', error);
    } finally {
      setRespondingTo(null);
    }
  };

  if (loading && invites.length === 0) {
    return (
      <div className={cn('p-4', className)}>
        <Loader2 size={20} className="animate-spin text-muted-foreground mx-auto" />
      </div>
    );
  }

  if (invites.length === 0) {
    return null;
  }

  return (
    <div className={cn('space-y-2', className)}>
      <div className="flex items-center gap-2 px-3 py-2 text-sm font-medium text-muted-foreground">
        <Mail size={14} />
        <span>Pending Invites ({invites.length})</span>
      </div>
      {invites.slice(0, 3).map((invite) => (
        <div
          key={invite.id}
          className={cn(
            'flex items-center gap-3 rounded-xl px-3 py-2',
            'bg-primary/5 border border-primary/20',
            'hover:bg-primary/10 transition-colors'
          )}
        >
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary/10">
            {invite.inviter.profilePicture ? (
              <img
                src={invite.inviter.profilePicture}
                alt={invite.inviter.displayName}
                className="h-8 w-8 rounded-full object-cover"
              />
            ) : (
              <User size={16} className="text-primary" />
            )}
          </div>
          <div className="flex-1 min-w-0">
            <div className="text-sm font-medium text-foreground truncate">
              {invite.conversation.title || 'Untitled'}
            </div>
            <div className="text-xs text-muted-foreground truncate">
              from {invite.inviter.displayName}
            </div>
          </div>
          <div className="flex gap-1">
            <button
              onClick={() => handleRespond(invite.id, false)}
              disabled={respondingTo === invite.id}
              className={cn(
                'rounded-lg p-1.5 text-muted-foreground',
                'hover:bg-destructive/10 hover:text-destructive',
                'disabled:opacity-50 transition-colors'
              )}
              aria-label="Decline"
            >
              <X size={14} />
            </button>
            <button
              onClick={() => handleRespond(invite.id, true, invite.conversationId)}
              disabled={respondingTo === invite.id}
              className={cn(
                'rounded-lg p-1.5 text-primary',
                'hover:bg-primary/20',
                'disabled:opacity-50 transition-colors'
              )}
              aria-label="Accept"
            >
              {respondingTo === invite.id ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <Check size={14} />
              )}
            </button>
          </div>
        </div>
      ))}
      {invites.length > 3 && (
        <div className="text-center text-xs text-muted-foreground py-1">
          +{invites.length - 3} more invite{invites.length - 3 !== 1 ? 's' : ''}
        </div>
      )}
    </div>
  );
};
