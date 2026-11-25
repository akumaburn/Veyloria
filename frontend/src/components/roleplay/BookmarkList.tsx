import { useState } from 'react';
import { formatDate } from '@/lib/utils';
import { type StoryBookmark } from '@/graphql/types';
import {
  useBookmarks,
  useCreateBookmark,
  useDeleteBookmark,
} from '@/hooks/useRoleplay';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Bookmark, Plus, Trash2, Clock, Tag, Loader2, X } from 'lucide-react';

interface BookmarkListProps {
  conversationId: string;
}

export default function BookmarkList({ conversationId }: BookmarkListProps) {
  const { bookmarks, loading } = useBookmarks(conversationId);
  const { createBookmark, loading: createLoading } = useCreateBookmark();
  const { deleteBookmark, loading: deleteLoading } = useDeleteBookmark();

  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newLabel, setNewLabel] = useState('');
  const [newDescription, setNewDescription] = useState('');
  const [newTags, setNewTags] = useState('');

  const handleCreate = async () => {
    if (!newLabel.trim()) return;
    const tags = newTags
      .split(',')
      .map((t) => t.trim())
      .filter(Boolean);
    await createBookmark({
      conversationId,
      label: newLabel.trim(),
      description: newDescription.trim() || undefined,
      tags: tags.length > 0 ? tags : undefined,
    });
    setNewLabel('');
    setNewDescription('');
    setNewTags('');
    setShowCreateForm(false);
  };

  const handleDelete = async (bookmarkId: string) => {
    await deleteBookmark(bookmarkId);
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {/* Create bookmark button / form */}
      {showCreateForm ? (
        <div className="rounded-xl border border-border/60 bg-muted/60 p-4">
          <div className="mb-3 flex items-center justify-between">
            <h4 className="text-sm font-medium text-foreground">Create Bookmark</h4>
            <button
              type="button"
              onClick={() => setShowCreateForm(false)}
              aria-label="Close bookmark form"
              title="Close"
              className="-my-2 -mr-2 inline-flex h-10 w-10 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-foreground sm:-my-1 sm:-mr-1 sm:h-8 sm:w-8"
            >
              <X size={16} />
            </button>
          </div>
          <div className="flex flex-col gap-2.5">
            <Input
              placeholder="Bookmark label"
              value={newLabel}
              onChange={(e) => setNewLabel(e.target.value)}
              size="sm"
            />
            <Input
              placeholder="Description (optional)"
              value={newDescription}
              onChange={(e) => setNewDescription(e.target.value)}
              size="sm"
            />
            <Input
              placeholder="Tags (comma-separated)"
              value={newTags}
              onChange={(e) => setNewTags(e.target.value)}
              size="sm"
            />
            <Button
              size="sm"
              className="h-10 sm:h-8"
              onClick={() => void handleCreate()}
              isLoading={createLoading}
              disabled={!newLabel.trim()}
            >
              Save Bookmark
            </Button>
          </div>
        </div>
      ) : (
        <Button
          variant="outline"
          size="sm"
          className="h-10 sm:h-8"
          leftIcon={<Plus size={14} />}
          onClick={() => setShowCreateForm(true)}
        >
          Create Bookmark
        </Button>
      )}

      {/* Empty state */}
      {(bookmarks as StoryBookmark[]).length === 0 && (
        <p className="py-1 text-center text-sm text-muted-foreground">No bookmarks yet</p>
      )}

      {/* Bookmark cards */}
      {(bookmarks as StoryBookmark[]).map((bookmark) => {
        return (
          <div
            key={bookmark.bookmarkId}
            className="rounded-xl border border-border/60 bg-muted/60 transition-colors hover:border-border-hover/50"
          >
            {/* Header */}
            <div className="flex items-start gap-3 p-4">
              <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-primary/10">
                <Bookmark size={16} className="text-primary" />
              </div>

              <div className="min-w-0 flex-1">
                <div className="flex items-start justify-between gap-2">
                  <h4 className="min-w-0 pt-1 text-sm font-medium break-words text-foreground">{bookmark.label}</h4>
                  <button
                    type="button"
                    onClick={() => void handleDelete(bookmark.bookmarkId)}
                    disabled={deleteLoading}
                    className="-my-1.5 -mr-2 inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-destructive/15 hover:text-destructive sm:-my-1 sm:-mr-1 sm:h-8 sm:w-8"
                    aria-label={`Delete bookmark ${bookmark.label}`}
                    title="Delete bookmark"
                  >
                    <Trash2 size={14} />
                  </button>
                </div>

                {bookmark.description && (
                  <p className="mt-1 text-xs text-muted-foreground">{bookmark.description}</p>
                )}

                {/* Tags */}
                {bookmark.tags && bookmark.tags.length > 0 && (
                  <div className="mt-2 flex flex-wrap gap-1">
                    {bookmark.tags.map((tag) => (
                      <span
                        key={tag}
                        className="inline-flex items-center gap-1 rounded-md bg-accent/50 px-1.5 py-0.5 text-xs text-muted-foreground"
                      >
                        <Tag size={10} />
                        {tag}
                      </span>
                    ))}
                  </div>
                )}

                {/* Timestamp */}
                {bookmark.createdAt && (
                  <p className="mt-2 flex items-center gap-1 text-xs text-muted-foreground">
                    <Clock size={10} />
                    {formatDate(bookmark.createdAt)}
                  </p>
                )}
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
