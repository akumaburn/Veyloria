import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { cn } from '@/lib/utils';
import { useStoryBranches, useCreateBranch, useBookmarks } from '@/hooks/useRoleplay';
import { type StoryBranch, type StoryBookmark } from '@/graphql/types';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Modal } from '@/components/ui/Modal';
import { Textarea } from '@/components/ui/Textarea';
import { Select } from '@/components/ui/Select';
import { GitBranch, Plus, ChevronDown, Check, Loader2 } from 'lucide-react';

interface BranchSelectorProps {
  conversationId: string;
}

export default function BranchSelector({ conversationId }: BranchSelectorProps) {
  const navigate = useNavigate();
  const { branches, loading } = useStoryBranches(conversationId);
  const { createBranch, loading: creating } = useCreateBranch();
  const { bookmarks } = useBookmarks(conversationId);
  const [isOpen, setIsOpen] = useState(false);
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [label, setLabel] = useState('');
  const [description, setDescription] = useState('');
  const [selectedBookmarkMessageId, setSelectedBookmarkMessageId] = useState('');
  const [activeBranchId, setActiveBranchId] = useState<string | null>(null);

  const bookmarkOptions = [
    { value: '', label: 'Select a branch point...' },
    ...(bookmarks as StoryBookmark[])
      .filter((b: StoryBookmark) => b.messageId)
      .map((b: StoryBookmark) => ({
        value: b.messageId!,
        label: b.label || `Bookmark at ${b.createdAt}`,
      })),
  ];

  const handleCreate = async () => {
    if (!label.trim() || !selectedBookmarkMessageId) return;
    await createBranch({
      parentConversationId: conversationId,
      branchPointMessageId: selectedBookmarkMessageId,
      label: label.trim(),
      description: description.trim() || undefined,
    });
    setLabel('');
    setDescription('');
    setSelectedBookmarkMessageId('');
    setShowCreateForm(false);
  };

  const handleSelectBranch = (branch: StoryBranch) => {
    setActiveBranchId(branch.branchId);
    setIsOpen(false);
    if (branch.branchConversationId) {
      navigate(`/chat/${branch.branchConversationId}`);
    }
  };

  const activeBranch = branches.find((b: StoryBranch) => b.branchId === activeBranchId);

  return (
    <div className="relative">
      {/* Trigger */}
      <button
        onClick={() => setIsOpen(!isOpen)}
        className={cn(
          'flex items-center gap-2 rounded-lg border border-border bg-muted px-3 py-2 text-sm transition-colors',
          'hover:border-border-hover hover:bg-accent/80',
          isOpen && 'border-primary ring-1 ring-primary'
        )}
      >
        <GitBranch size={14} className="text-muted-foreground" />
        <span className="text-foreground">
          {activeBranch?.label ?? 'Main Branch'}
        </span>
        <ChevronDown size={14} className={cn('text-muted-foreground transition-transform', isOpen && 'rotate-180')} />
      </button>

      {/* Dropdown */}
      {isOpen && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setIsOpen(false)} />
          <div className="absolute left-0 z-20 mt-1 w-64 rounded-lg border border-border bg-muted py-1 shadow-xl">
            {loading ? (
              <div className="flex justify-center py-4">
                <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
              </div>
            ) : (
              <>
                {/* Main branch option */}
                <button
                  onClick={() => { setActiveBranchId(null); setIsOpen(false); }}
                  className={cn(
                    'flex w-full items-center gap-2 px-3 py-2 text-left text-sm transition-colors hover:bg-accent',
                    !activeBranchId && 'bg-primary/10 text-primary'
                  )}
                >
                  <GitBranch size={14} />
                  <span className="flex-1">Main Branch</span>
                  {!activeBranchId && <Check size={14} />}
                </button>

                {branches.map((branch: StoryBranch) => (
                  <button
                    key={branch.branchId}
                    onClick={() => handleSelectBranch(branch)}
                    className={cn(
                      'flex w-full items-center gap-2 px-3 py-2 text-left text-sm transition-colors hover:bg-accent',
                      activeBranchId === branch.branchId && 'bg-primary/10 text-primary'
                    )}
                  >
                    <GitBranch size={14} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-foreground">{branch.label}</p>
                      {branch.description && (
                        <p className="truncate text-xs text-muted-foreground">{branch.description}</p>
                      )}
                    </div>
                    {activeBranchId === branch.branchId && <Check size={14} />}
                  </button>
                ))}

                <div className="border-t border-border mt-1 pt-1">
                  <button
                    onClick={() => { setIsOpen(false); setShowCreateForm(true); }}
                    className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-muted-foreground hover:bg-accent hover:text-foreground"
                  >
                    <Plus size={14} />
                    <span>New Branch</span>
                  </button>
                </div>
              </>
            )}
          </div>
        </>
      )}

      {/* Create branch modal */}
      <Modal
        isOpen={showCreateForm}
        onClose={() => setShowCreateForm(false)}
        title="Create Story Branch"
        footer={
          <div className="flex justify-end gap-3">
            <Button variant="ghost" onClick={() => setShowCreateForm(false)}>Cancel</Button>
            <Button onClick={handleCreate} isLoading={creating} disabled={!label.trim() || !selectedBookmarkMessageId}>
              Create Branch
            </Button>
          </div>
        }
      >
        <div className="space-y-4">
          <Select
            label="Branch Point"
            options={bookmarkOptions}
            value={selectedBookmarkMessageId}
            onChange={(e) => setSelectedBookmarkMessageId(e.target.value)}
          />
          {bookmarkOptions.length <= 1 && (
            <p className="text-xs text-muted-foreground">
              Create a bookmark first to mark a branch point in the story.
            </p>
          )}
          <Input
            label="Branch Name"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="e.g., What if we took the other path..."
          />
          <Textarea
            label="Description (optional)"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Describe what this branch explores..."
            minRows={2}
          />
        </div>
      </Modal>
    </div>
  );
}
