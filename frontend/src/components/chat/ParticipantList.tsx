import { useState } from 'react';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { cn } from '@/lib/utils';
import { Modal, ConfirmModal } from '@/components/ui/Modal';
import {
  User,
  UserPlus,
  Crown,
  ShieldCheck,
  Users,
  MoreVertical,
  UserMinus,
  Settings,
  Loader2,
  Clock,
  CheckCircle,
} from 'lucide-react';
import {
  useConversationParticipants,
  useRemoveParticipant,
  useUpdateParticipant,
  useTransferOwnership,
  ConversationParticipant,
} from '@/hooks/useGraphQL';
import { useAuthStore } from '@/stores/authStore';
import { InviteUserModal } from './InviteUserModal';

const menuItem =
  'flex min-h-11 cursor-pointer items-center gap-2 rounded-lg px-3 py-2.5 text-sm outline-none data-[highlighted]:bg-accent sm:min-h-0';

interface ParticipantListProps {
  isOpen: boolean;
  onClose: () => void;
  conversationId: string;
  conversationTitle?: string;
}

export const ParticipantList = ({
  isOpen,
  onClose,
  conversationId,
  conversationTitle,
}: ParticipantListProps) => {
  const { user: currentUser } = useAuthStore();
  const { participants, loading, refetch } = useConversationParticipants(conversationId);
  const { removeParticipant, loading: removeLoading } = useRemoveParticipant();
  const { updateParticipant, loading: updateLoading } = useUpdateParticipant();
  const { transferOwnership, loading: transferLoading } = useTransferOwnership();

  const [showInviteModal, setShowInviteModal] = useState(false);
  const [selectedParticipant, setSelectedParticipant] = useState<ConversationParticipant | null>(null);
  const [showRemoveConfirm, setShowRemoveConfirm] = useState(false);
  const [showTransferConfirm, setShowTransferConfirm] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [editRole, setEditRole] = useState<'MEMBER' | 'ADMIN'>('MEMBER');
  const [editCanInvite, setEditCanInvite] = useState(false);
  const [editCanAddCharacters, setEditCanAddCharacters] = useState(false);

  // Find current user's participation
  const myParticipation = participants.find((p) => p.user.id === currentUser?.id);
  const isOwner = myParticipation?.role === 'OWNER';
  const isAdmin = myParticipation?.role === 'ADMIN' || isOwner;
  const canInvite = isOwner || isAdmin || myParticipation?.canInvite;

  const handleRemoveParticipant = async () => {
    if (!selectedParticipant) return;

    try {
      await removeParticipant({
        conversationId,
        userId: selectedParticipant.user.id,
      });
      setShowRemoveConfirm(false);
      setSelectedParticipant(null);
      refetch();
    } catch (error) {
      console.error('Failed to remove participant:', error);
    }
  };

  const handleTransferOwnership = async () => {
    if (!selectedParticipant) return;

    try {
      await transferOwnership({
        conversationId,
        newOwnerId: selectedParticipant.user.id,
      });
      setShowTransferConfirm(false);
      setSelectedParticipant(null);
      refetch();
    } catch (error) {
      console.error('Failed to transfer ownership:', error);
    }
  };

  const handleUpdateParticipant = async () => {
    if (!selectedParticipant) return;

    try {
      await updateParticipant({
        conversationId,
        userId: selectedParticipant.user.id,
        role: editRole,
        canInvite: editCanInvite,
        canAddCharacters: editCanAddCharacters,
      });
      setShowEditModal(false);
      setSelectedParticipant(null);
      refetch();
    } catch (error) {
      console.error('Failed to update participant:', error);
    }
  };

  const openEditModal = (participant: ConversationParticipant) => {
    setSelectedParticipant(participant);
    setEditRole(participant.role === 'OWNER' ? 'ADMIN' : participant.role as 'MEMBER' | 'ADMIN');
    setEditCanInvite(participant.canInvite);
    setEditCanAddCharacters(participant.canAddCharacters);
    setShowEditModal(true);
  };

  const getRoleIcon = (role: string) => {
    switch (role) {
      case 'OWNER':
        return <Crown size={14} className="text-warning" />;
      case 'ADMIN':
        return <ShieldCheck size={14} className="text-primary" />;
      default:
        return null;
    }
  };

  const getRoleBadge = (role: string) => {
    switch (role) {
      case 'OWNER':
        return (
          <span className="inline-flex items-center gap-1 rounded-full bg-warning/10 px-2 py-0.5 text-xs font-medium text-warning">
            <Crown size={10} />
            Owner
          </span>
        );
      case 'ADMIN':
        return (
          <span className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
            <ShieldCheck size={10} />
            Admin
          </span>
        );
      default:
        return (
          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
            <Users size={10} />
            Member
          </span>
        );
    }
  };

  return (
    <>
      <Modal
        isOpen={isOpen}
        onClose={onClose}
        title="Participants"
        description={conversationTitle ? `Members of "${conversationTitle}"` : 'Conversation members'}
        size="md"
      >
        <div className="space-y-4">
          {/* Invite Button */}
          {canInvite && (
            <button
              onClick={() => setShowInviteModal(true)}
              className={cn(
                'flex min-h-11 w-full items-center justify-center gap-2 rounded-xl px-4 py-3',
                'border border-dashed border-primary/50',
                'text-primary hover:bg-primary/5',
                'transition-colors duration-150'
              )}
            >
              <UserPlus size={18} />
              <span className="font-medium">Invite User</span>
            </button>
          )}

          {/* Participants List */}
          <div className="space-y-2">
            {loading ? (
              <div className="flex items-center justify-center py-8">
                <Loader2 size={24} className="animate-spin text-muted-foreground" />
              </div>
            ) : participants.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-8 text-muted-foreground">
                <Users size={32} className="mb-2 opacity-50" />
                <p>No participants yet</p>
              </div>
            ) : (
              participants.map((participant) => (
                <div
                  key={participant.id}
                  className={cn(
                    'flex items-center gap-3 rounded-xl p-3',
                    'border border-border/50 bg-muted/30',
                    'hover:bg-muted/50 transition-colors'
                  )}
                >
                  {/* Avatar */}
                  <div className="relative shrink-0">
                    <div className="flex h-10 w-10 items-center justify-center rounded-full bg-primary/10">
                      {participant.user.profilePicture ? (
                        <img
                          src={participant.user.profilePicture}
                          alt={participant.user.displayName}
                          className="h-10 w-10 rounded-full object-cover"
                        />
                      ) : (
                        <User size={20} className="text-primary" />
                      )}
                    </div>
                    {getRoleIcon(participant.role) && (
                      <div className="absolute -bottom-0.5 -right-0.5 rounded-full bg-card p-0.5">
                        {getRoleIcon(participant.role)}
                      </div>
                    )}
                  </div>

                  {/* User Info */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-medium text-foreground truncate">
                        {participant.nickname || participant.user.displayName}
                      </span>
                      {participant.user.id === currentUser?.id && (
                        <span className="shrink-0 text-xs text-muted-foreground">(you)</span>
                      )}
                    </div>
                    <div className="flex min-w-0 items-center gap-2 mt-0.5">
                      <span className="truncate text-xs text-muted-foreground">@{participant.user.username}</span>
                      <span className="shrink-0">{getRoleBadge(participant.role)}</span>
                    </div>
                  </div>

                  {/* Actions Menu */}
                  {isAdmin && participant.user.id !== currentUser?.id && participant.role !== 'OWNER' && (
                    <DropdownMenu.Root modal={false}>
                      <DropdownMenu.Trigger asChild>
                        <button
                          type="button"
                          aria-label={`Actions for ${participant.nickname || participant.user.displayName}`}
                          title="Participant actions"
                          className={cn(
                            'inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-lg text-muted-foreground sm:h-9 sm:w-9',
                            'hover:bg-accent hover:text-foreground data-[state=open]:bg-accent',
                            'transition-colors duration-150'
                          )}
                        >
                          <MoreVertical size={16} />
                        </button>
                      </DropdownMenu.Trigger>
                      <DropdownMenu.Portal>
                        <DropdownMenu.Content
                          align="end"
                          sideOffset={4}
                          collisionPadding={16}
                          className="z-50 w-56 max-w-[calc(100vw-2rem)] rounded-xl border border-border bg-card p-1.5 shadow-lg"
                        >
                          <DropdownMenu.Item className={menuItem} onSelect={() => openEditModal(participant)}>
                            <Settings size={14} />
                            <span>Edit Role & Permissions</span>
                          </DropdownMenu.Item>
                          {isOwner && (
                            <DropdownMenu.Item
                              className={menuItem}
                              onSelect={() => {
                                setSelectedParticipant(participant);
                                setShowTransferConfirm(true);
                              }}
                            >
                              <Crown size={14} />
                              <span>Transfer Ownership</span>
                            </DropdownMenu.Item>
                          )}
                          <DropdownMenu.Item
                            className={cn(menuItem, 'text-destructive data-[highlighted]:bg-destructive/10')}
                            onSelect={() => {
                              setSelectedParticipant(participant);
                              setShowRemoveConfirm(true);
                            }}
                          >
                            <UserMinus size={14} />
                            <span>Remove from Conversation</span>
                          </DropdownMenu.Item>
                        </DropdownMenu.Content>
                      </DropdownMenu.Portal>
                    </DropdownMenu.Root>
                  )}
                </div>
              ))
            )}
          </div>

          {/* Participant Count */}
          <div className="flex items-center justify-between border-t border-border pt-4 text-sm text-muted-foreground">
            <span>{participants.length} participant{participants.length !== 1 ? 's' : ''}</span>
            <span className="flex items-center gap-1">
              <Clock size={12} />
              Last updated just now
            </span>
          </div>
        </div>
      </Modal>

      {/* Invite User Modal */}
      <InviteUserModal
        isOpen={showInviteModal}
        onClose={() => setShowInviteModal(false)}
        conversationId={conversationId}
        conversationTitle={conversationTitle}
      />

      {/* Remove Confirmation Modal */}
      <ConfirmModal
        isOpen={showRemoveConfirm}
        onClose={() => {
          setShowRemoveConfirm(false);
          setSelectedParticipant(null);
        }}
        onConfirm={handleRemoveParticipant}
        title="Remove Participant"
        message={`Are you sure you want to remove ${selectedParticipant?.user.displayName} from this conversation? They will no longer be able to view or send messages.`}
        confirmText="Remove"
        variant="danger"
        isLoading={removeLoading}
      />

      {/* Transfer Ownership Confirmation Modal */}
      <ConfirmModal
        isOpen={showTransferConfirm}
        onClose={() => {
          setShowTransferConfirm(false);
          setSelectedParticipant(null);
        }}
        onConfirm={handleTransferOwnership}
        title="Transfer Ownership"
        message={`Are you sure you want to transfer ownership of this conversation to ${selectedParticipant?.user.displayName}? You will become an admin and lose owner privileges.`}
        confirmText="Transfer Ownership"
        variant="warning"
        isLoading={transferLoading}
      />

      {/* Edit Participant Modal */}
      <Modal
        isOpen={showEditModal}
        onClose={() => {
          setShowEditModal(false);
          setSelectedParticipant(null);
        }}
        title="Edit Participant"
        description={selectedParticipant ? `Manage ${selectedParticipant.user.displayName}'s role and permissions` : ''}
        size="sm"
      >
        <div className="space-y-4">
          {/* Role Selection */}
          <div className="space-y-2">
            <label className="block text-sm font-medium text-foreground">Role</label>
            <div className="flex gap-2">
              <button
                onClick={() => setEditRole('MEMBER')}
                className={cn(
                  'flex flex-1 items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium max-sm:min-h-11',
                  'border transition-colors',
                  editRole === 'MEMBER'
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-border bg-muted/50 text-muted-foreground hover:bg-accent hover:text-foreground'
                )}
              >
                <Users size={16} />
                <span>Member</span>
              </button>
              <button
                onClick={() => setEditRole('ADMIN')}
                className={cn(
                  'flex flex-1 items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium max-sm:min-h-11',
                  'border transition-colors',
                  editRole === 'ADMIN'
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-border bg-muted/50 text-muted-foreground hover:bg-accent hover:text-foreground'
                )}
              >
                <ShieldCheck size={16} />
                <span>Admin</span>
              </button>
            </div>
          </div>

          {/* Permissions */}
          <div className="space-y-3">
            <label className="block text-sm font-medium text-foreground">Permissions</label>
            <label className="flex items-center gap-3 cursor-pointer max-sm:min-h-11">
              <input
                type="checkbox"
                checked={editCanInvite}
                onChange={(e) => setEditCanInvite(e.target.checked)}
                className="h-4 w-4 rounded border-border text-primary focus:ring-primary"
              />
              <span className="text-sm text-foreground">Can invite other users</span>
            </label>
            <label className="flex items-center gap-3 cursor-pointer max-sm:min-h-11">
              <input
                type="checkbox"
                checked={editCanAddCharacters}
                onChange={(e) => setEditCanAddCharacters(e.target.checked)}
                className="h-4 w-4 rounded border-border text-primary focus:ring-primary"
              />
              <span className="text-sm text-foreground">Can add AI characters</span>
            </label>
          </div>

          {/* Actions */}
          <div className="flex justify-end gap-3 pt-2">
            <button
              onClick={() => {
                setShowEditModal(false);
                setSelectedParticipant(null);
              }}
              className={cn(
                'rounded-xl px-4 py-2.5 text-sm font-medium max-sm:min-h-11',
                'bg-muted text-muted-foreground',
                'hover:bg-accent hover:text-foreground',
                'transition-colors duration-150'
              )}
            >
              Cancel
            </button>
            <button
              onClick={handleUpdateParticipant}
              disabled={updateLoading}
              className={cn(
                'inline-flex items-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium max-sm:min-h-11',
                'bg-primary text-primary-foreground',
                'hover:bg-primary-hover',
                'disabled:opacity-50 disabled:cursor-not-allowed',
                'transition-colors duration-150'
              )}
            >
              {updateLoading ? (
                <>
                  <Loader2 size={16} className="animate-spin" />
                  <span>Saving...</span>
                </>
              ) : (
                <>
                  <CheckCircle size={16} />
                  <span>Save Changes</span>
                </>
              )}
            </button>
          </div>
        </div>
      </Modal>
    </>
  );
};
