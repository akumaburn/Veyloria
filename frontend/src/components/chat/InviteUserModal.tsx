import { useState, useEffect, useCallback } from 'react';
import { Modal } from '@/components/ui/Modal';
import { Input } from '@/components/ui/Input';
import { cn } from '@/lib/utils';
import {
  UserPlus,
  Search,
  User,
  Mail,
  Loader2,
  Check,
  X,
  ShieldCheck,
  Users,
} from 'lucide-react';
import {
  useSearchUsers,
  useInviteUser,
  UserSearchResult,
} from '@/hooks/useGraphQL';
import { useDebounce } from '@/hooks/useGraphQL';

interface InviteUserModalProps {
  isOpen: boolean;
  onClose: () => void;
  conversationId: string;
  conversationTitle?: string;
}

type ParticipantRole = 'MEMBER' | 'ADMIN';

export const InviteUserModal = ({
  isOpen,
  onClose,
  conversationId,
  conversationTitle,
}: InviteUserModalProps) => {
  const [searchInput, setSearchInput] = useState('');
  const [selectedUser, setSelectedUser] = useState<UserSearchResult | null>(null);
  const [inviteMessage, setInviteMessage] = useState('');
  const [roleToGrant, setRoleToGrant] = useState<ParticipantRole>('MEMBER');
  const [successMessage, setSuccessMessage] = useState('');
  const [errorMessage, setErrorMessage] = useState('');

  const debouncedSearch = useDebounce(searchInput, 300);
  const { results, loading: searchLoading, search, clearResults } = useSearchUsers();
  const { inviteUser, loading: inviteLoading, error: inviteError } = useInviteUser();

  // Trigger search when the debounced value changes. The previous code used
  // useState(fn) which only ran ONCE on mount — the debounced search never
  // fired and every keystroke hit the API un-debounced via handleSearchChange.
  useEffect(() => {
    if (debouncedSearch && debouncedSearch.length >= 2) {
      search(debouncedSearch);
    }
  }, [debouncedSearch, search]);

  const handleSearchChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    setSearchInput(value);
    setSelectedUser(null);
    setErrorMessage('');
    setSuccessMessage('');

    // The debounced useEffect above handles the actual search; only clear
    // results immediately when the query drops below the threshold.
    if (value.length < 2) {
      clearResults();
    }
  }, [clearResults]);

  const handleSelectUser = useCallback((user: UserSearchResult) => {
    setSelectedUser(user);
    setSearchInput(user.username);
    clearResults();
    setErrorMessage('');
    setSuccessMessage('');
  }, [clearResults]);

  const handleInvite = async () => {
    if (!selectedUser && !searchInput) return;

    setErrorMessage('');
    setSuccessMessage('');

    try {
      const result = await inviteUser({
        conversationId,
        usernameOrEmail: selectedUser?.username || searchInput,
        message: inviteMessage || undefined,
        roleToGrant,
      });

      if (result.data?.inviteUserToConversation) {
        setSuccessMessage(`Invitation sent to ${selectedUser?.displayName || searchInput}!`);
        setSelectedUser(null);
        setSearchInput('');
        setInviteMessage('');
        setRoleToGrant('MEMBER');
        setTimeout(() => {
          setSuccessMessage('');
        }, 3000);
      }
    } catch (err: any) {
      setErrorMessage(err.message || 'Failed to send invitation');
    }
  };

  const handleClose = () => {
    setSearchInput('');
    setSelectedUser(null);
    setInviteMessage('');
    setRoleToGrant('MEMBER');
    setSuccessMessage('');
    setErrorMessage('');
    clearResults();
    onClose();
  };

  const isEmailFormat = searchInput.includes('@');
  const canInvite = selectedUser || (searchInput.length >= 2 && isEmailFormat);

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleClose}
      title="Invite User"
      description={conversationTitle ? `Invite someone to "${conversationTitle}"` : 'Invite someone to this conversation'}
      size="md"
    >
      <div className="space-y-4">
        {/* Search Input */}
        <div className="relative">
          <Input
            label="Username or Email"
            placeholder="Search by username or enter email..."
            value={searchInput}
            onChange={handleSearchChange}
            leftIcon={<Search size={18} />}
            rightIcon={
              selectedUser ? (
                <button
                  type="button"
                  onClick={() => {
                    setSelectedUser(null);
                    setSearchInput('');
                    clearResults();
                  }}
                  aria-label="Clear selected user"
                  className="flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
                >
                  <X size={16} />
                </button>
              ) : searchLoading ? (
                <Loader2 size={16} className="animate-spin text-muted-foreground" />
              ) : null
            }
          />

          {/* Search Results Dropdown */}
          {results.length > 0 && !selectedUser && (
            <div className="absolute top-full left-0 right-0 z-50 mt-1 max-h-60 overflow-y-auto rounded-xl border border-border bg-card shadow-lg">
              {results.map((user) => (
                <button
                  key={user.id}
                  type="button"
                  onClick={() => handleSelectUser(user)}
                  className={cn(
                    'flex w-full items-center gap-3 px-3 py-2.5 sm:px-4 sm:py-3',
                    'hover:bg-accent transition-colors',
                    'border-b border-border/50 last:border-b-0'
                  )}
                >
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary/10">
                    {user.profilePicture ? (
                      <img
                        src={user.profilePicture}
                        alt={user.displayName}
                        className="h-10 w-10 rounded-full object-cover"
                      />
                    ) : (
                      <User size={20} className="text-primary" />
                    )}
                  </div>
                  <div className="min-w-0 flex-1 text-left">
                    <div className="truncate font-medium text-foreground">{user.displayName}</div>
                    <div className="truncate text-sm text-muted-foreground">@{user.username}</div>
                  </div>
                </button>
              ))}
            </div>
          )}

          {/* Selected User Display */}
          {selectedUser && (
            <div className="mt-2 flex items-center gap-3 rounded-xl border border-primary/30 bg-primary/5 p-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary/10">
                {selectedUser.profilePicture ? (
                  <img
                    src={selectedUser.profilePicture}
                    alt={selectedUser.displayName}
                    className="h-10 w-10 rounded-full object-cover"
                  />
                ) : (
                  <User size={20} className="text-primary" />
                )}
              </div>
              <div className="min-w-0 flex-1">
                <div className="truncate font-medium text-foreground">{selectedUser.displayName}</div>
                <div className="truncate text-sm text-muted-foreground">@{selectedUser.username}</div>
              </div>
              <Check size={20} className="shrink-0 text-primary" />
            </div>
          )}

          {/* Email hint */}
          {searchInput.length > 0 && !selectedUser && results.length === 0 && !searchLoading && (
            <div className="mt-2 flex items-center gap-2 text-sm text-muted-foreground">
              <Mail size={14} className="shrink-0" />
              <span>
                {isEmailFormat
                  ? 'User will be invited by email'
                  : 'Enter an email address to invite someone not yet on the platform'}
              </span>
            </div>
          )}
        </div>

        {/* Role Selection */}
        <div className="space-y-2">
          <label className="block text-sm font-medium text-foreground">Role to Grant</label>
          <div className="flex gap-2">
            <button
              onClick={() => setRoleToGrant('MEMBER')}
              className={cn(
                'flex flex-1 items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium max-sm:min-h-11',
                'border transition-colors',
                roleToGrant === 'MEMBER'
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border bg-muted/50 text-muted-foreground hover:bg-accent hover:text-foreground'
              )}
            >
              <Users size={16} />
              <span>Member</span>
            </button>
            <button
              onClick={() => setRoleToGrant('ADMIN')}
              className={cn(
                'flex flex-1 items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium max-sm:min-h-11',
                'border transition-colors',
                roleToGrant === 'ADMIN'
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border bg-muted/50 text-muted-foreground hover:bg-accent hover:text-foreground'
              )}
            >
              <ShieldCheck size={16} />
              <span>Admin</span>
            </button>
          </div>
          <p className="text-xs text-muted-foreground">
            {roleToGrant === 'ADMIN'
              ? 'Admins can invite users, add characters, and manage participants'
              : 'Members can view and send messages in the conversation'}
          </p>
        </div>

        {/* Invite Message */}
        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">
            Message (optional)
          </label>
          <textarea
            value={inviteMessage}
            onChange={(e) => setInviteMessage(e.target.value)}
            placeholder="Add a personal message to your invitation..."
            className={cn(
              'w-full rounded-xl border border-border bg-muted/50 px-3 py-2.5 text-sm',
              'text-foreground placeholder-muted-foreground',
              'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
              'transition-all duration-150 resize-none'
            )}
            rows={3}
          />
        </div>

        {/* Success/Error Messages */}
        {successMessage && (
          <div className="flex items-center gap-2 rounded-xl border border-success/30 bg-success/10 px-4 py-3 text-sm text-success">
            <Check size={16} />
            <span>{successMessage}</span>
          </div>
        )}

        {(errorMessage || inviteError) && (
          <div className="flex items-center gap-2 rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
            <X size={16} />
            <span>{errorMessage || inviteError?.message || 'Failed to send invitation'}</span>
          </div>
        )}

        {/* Actions */}
        <div className="flex justify-end gap-3 pt-2">
          <button
            onClick={handleClose}
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
            onClick={handleInvite}
            disabled={!canInvite || inviteLoading}
            className={cn(
              'inline-flex items-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium max-sm:min-h-11',
              'bg-primary text-primary-foreground',
              'hover:bg-primary-hover',
              'disabled:opacity-50 disabled:cursor-not-allowed',
              'transition-colors duration-150'
            )}
          >
            {inviteLoading ? (
              <>
                <Loader2 size={16} className="animate-spin" />
                <span>Sending...</span>
              </>
            ) : (
              <>
                <UserPlus size={16} />
                <span>Send Invitation</span>
              </>
            )}
          </button>
        </div>
      </div>
    </Modal>
  );
};
