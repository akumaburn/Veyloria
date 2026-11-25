import { useState, useEffect, useCallback } from 'react';
import { X, Save, Trash2, Pin, Archive, Tag, Loader2, AlertCircle } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useConversationStore } from '@/stores/conversationStore';
import { toast } from 'react-hot-toast';
import { useNavigate } from 'react-router-dom';
import type { Conversation } from '@/graphql/types';

interface ConversationSettingsProps {
  conversationId: string | undefined;
  onClose: () => void;
}

export const ConversationSettings = ({ conversationId, onClose }: ConversationSettingsProps) => {
  const navigate = useNavigate();
  const {
    fetchConversation,
    updateConversation,
    deleteConversation,
    pinConversation,
    archiveConversation
  } = useConversationStore();

  const [conversation, setConversation] = useState<Conversation | null>(null);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState('');
  const [isSaving, setIsSaving] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);

  // Load conversation data
  const loadConversation = useCallback(async () => {
    if (!conversationId) return;

    try {
      const conv = await fetchConversation(conversationId);
      if (conv) {
        setConversation(conv);
        setTitle(conv.title || '');
        setDescription(conv.description || '');
        setCategory(conv.category || '');
      }
    } catch (error) {
      console.error('Failed to load conversation:', error);
      toast.error('Failed to load conversation settings');
    }
  }, [conversationId, fetchConversation]);

  useEffect(() => {
    loadConversation();
  }, [loadConversation]);

  const handleSave = async () => {
    if (!conversationId) return;

    setIsSaving(true);
    try {
      await updateConversation(conversationId, {
        title: title.trim() || undefined,
        description: description.trim() || undefined,
        category: category.trim() || undefined,
      });
      toast.success('Conversation updated');
      onClose();
    } catch (error) {
      console.error('Failed to update conversation:', error);
      toast.error('Failed to update conversation');
    } finally {
      setIsSaving(false);
    }
  };

  const handlePin = async () => {
    if (!conversationId) return;

    try {
      await pinConversation(conversationId);
      const newPinnedState = !conversation?.isPinned;
      setConversation(prev => prev ? { ...prev, isPinned: newPinnedState } : prev);
      toast.success(conversation?.isPinned ? 'Unpinned' : 'Pinned');
    } catch (error) {
      console.error('Failed to toggle pin:', error);
      toast.error('Failed to toggle pin');
    }
  };

  const handleArchive = async () => {
    if (!conversationId) return;

    try {
      await archiveConversation(conversationId);
      const wasArchived = conversation?.isArchived;
      setConversation(prev => prev ? { ...prev, isArchived: !wasArchived } : prev);
      toast.success(wasArchived ? 'Unarchived' : 'Archived');
      if (!wasArchived) {
        onClose();
        navigate('/chat');
      }
    } catch (error) {
      console.error('Failed to toggle archive:', error);
      toast.error('Failed to toggle archive');
    }
  };

  const handleDelete = async () => {
    if (!conversationId) return;

    setIsDeleting(true);
    try {
      await deleteConversation(conversationId);
      toast.success('Conversation deleted');
      onClose();
      navigate('/chat');
    } catch (error) {
      console.error('Failed to delete conversation:', error);
      toast.error('Failed to delete conversation');
    } finally {
      setIsDeleting(false);
    }
  };

  // Show placeholder if no conversation
  if (!conversationId) {
    return (
      <div className={cn(
        'absolute left-0 right-0 top-full z-50',
        'max-h-[70dvh] overflow-y-auto overscroll-contain custom-scrollbar',
        'border-b border-border/50 bg-background-secondary',
        'shadow-2xl animate-fade-down'
      )}>
        <div className="mx-auto max-w-2xl p-4 sm:p-6">
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-lg font-semibold text-foreground">Conversation Settings</h3>
            <button
              type="button"
              onClick={onClose}
              className="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-lg text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
              aria-label="Close settings"
            >
              <X size={20} />
            </button>
          </div>
          <div className="flex flex-col items-center justify-center py-8 text-center">
            <AlertCircle className="h-12 w-12 text-muted-foreground mb-4" />
            <p className="text-muted-foreground">
              Start a conversation first to access settings
            </p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className={cn(
      'absolute left-0 right-0 top-full z-50',
      'max-h-[70dvh] overflow-y-auto overscroll-contain custom-scrollbar',
      'border-b border-border/50 bg-background-secondary',
      'shadow-2xl animate-fade-down'
    )}>
      <div className="mx-auto max-w-2xl p-4 sm:p-6">
        {/* Header */}
        <div className="flex items-center justify-between mb-4 sm:mb-6">
          <h3 className="text-lg font-semibold text-foreground">Conversation Settings</h3>
          <button
            type="button"
            onClick={onClose}
            className="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-lg text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
            aria-label="Close settings"
          >
            <X size={20} />
          </button>
        </div>

        {/* Form */}
        <div className="space-y-4">
          {/* Title */}
          <div>
            <label className="block text-sm font-medium text-foreground mb-1.5">
              Title
            </label>
            <input
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Conversation title..."
              className={cn(
                'w-full rounded-xl border border-border bg-muted/50',
                'px-4 py-2.5 text-sm text-foreground placeholder-muted-foreground',
                'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
                'transition-all duration-150'
              )}
            />
          </div>

          {/* Description */}
          <div>
            <label className="block text-sm font-medium text-foreground mb-1.5">
              Description
            </label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Optional description..."
              rows={2}
              className={cn(
                'w-full rounded-xl border border-border bg-muted/50',
                'px-4 py-2.5 text-sm text-foreground placeholder-muted-foreground',
                'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
                'transition-all duration-150 resize-none'
              )}
            />
          </div>

          {/* Category */}
          <div>
            <label className="block text-sm font-medium text-foreground mb-1.5">
              Category
            </label>
            <div className="relative">
              <Tag className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" size={16} />
              <input
                type="text"
                value={category}
                onChange={(e) => setCategory(e.target.value)}
                placeholder="e.g., Work, Personal, Research..."
                className={cn(
                  'w-full rounded-xl border border-border bg-muted/50',
                  'pl-10 pr-4 py-2.5 text-sm text-foreground placeholder-muted-foreground',
                  'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
                  'transition-all duration-150'
                )}
              />
            </div>
          </div>

          {/* Quick actions */}
          <div className="flex gap-2 pt-2">
            <button
              type="button"
              onClick={handlePin}
              aria-pressed={!!conversation?.isPinned}
              className={cn(
                'flex items-center justify-center gap-2 px-4 py-2 rounded-lg text-sm font-medium max-sm:min-h-11 max-sm:flex-1',
                'border border-border bg-card',
                'hover:bg-accent transition-colors',
                conversation?.isPinned && 'bg-primary/10 border-primary text-primary'
              )}
            >
              <Pin size={16} />
              {conversation?.isPinned ? 'Pinned' : 'Pin'}
            </button>
            <button
              type="button"
              onClick={handleArchive}
              aria-pressed={!!conversation?.isArchived}
              className={cn(
                'flex items-center justify-center gap-2 px-4 py-2 rounded-lg text-sm font-medium max-sm:min-h-11 max-sm:flex-1',
                'border border-border bg-card',
                'hover:bg-accent transition-colors',
                conversation?.isArchived && 'bg-warning/10 border-warning text-warning'
              )}
            >
              <Archive size={16} />
              {conversation?.isArchived ? 'Archived' : 'Archive'}
            </button>
          </div>
        </div>

        {/* Footer */}
        <div className="mt-6 flex flex-wrap items-center justify-between gap-2 border-t border-border/50 pt-4">
          {/* Delete button */}
          {showDeleteConfirm ? (
            <div className="flex flex-wrap items-center gap-2 max-sm:w-full">
              <span className="text-sm text-destructive max-sm:w-full">Delete this conversation?</span>
              <button
                type="button"
                onClick={handleDelete}
                disabled={isDeleting}
                className={cn(
                  'inline-flex items-center justify-center px-3 py-1.5 rounded-lg text-sm font-medium max-sm:min-h-11 max-sm:flex-1',
                  'bg-destructive text-destructive-foreground',
                  'hover:bg-destructive-hover transition-colors',
                  'disabled:opacity-50'
                )}
              >
                {isDeleting ? <Loader2 size={14} className="animate-spin" /> : 'Yes, delete'}
              </button>
              <button
                type="button"
                onClick={() => setShowDeleteConfirm(false)}
                className="px-3 py-1.5 rounded-lg text-sm font-medium text-muted-foreground hover:text-foreground max-sm:min-h-11 max-sm:flex-1"
              >
                Cancel
              </button>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => setShowDeleteConfirm(true)}
              className={cn(
                'flex shrink-0 items-center gap-2 whitespace-nowrap rounded-lg px-3 py-2 text-sm font-medium sm:px-4 max-sm:min-h-11',
                'text-destructive hover:bg-destructive/10 transition-colors'
              )}
            >
              <Trash2 size={16} />
              Delete
            </button>
          )}

          {/* Save button */}
          <button
            type="button"
            onClick={handleSave}
            disabled={isSaving}
            className={cn(
              'flex shrink-0 items-center justify-center gap-2 whitespace-nowrap rounded-lg px-3 py-2 text-sm font-medium sm:px-4 max-sm:min-h-11',
              'bg-primary text-primary-foreground',
              'hover:bg-primary-hover transition-colors',
              'disabled:opacity-50'
            )}
          >
            {isSaving ? (
              <>
                <Loader2 size={16} className="animate-spin" />
                Saving...
              </>
            ) : (
              <>
                <Save size={16} />
                Save Changes
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
};
