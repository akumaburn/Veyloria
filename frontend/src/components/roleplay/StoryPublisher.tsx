import { useState } from 'react';
import { Send, X, Tag } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Textarea } from '@/components/ui/Textarea';
import { Select } from '@/components/ui/Select';
import { Switch } from '@/components/ui/Switch';
import { Modal } from '@/components/ui/Modal';
import { Card } from '@/components/ui/Card';
import { useCommunityStore } from '@/stores/communityStore';
import { GenreTag } from './GenreTag';

interface StoryPublisherProps {
  conversationId: string;
  isOpen: boolean;
  onClose: () => void;
}

const GENRE_OPTIONS = [
  { value: '', label: 'Select a genre' },
  { value: 'fantasy', label: 'Fantasy' },
  { value: 'sci-fi', label: 'Sci-Fi' },
  { value: 'horror', label: 'Horror' },
  { value: 'romance', label: 'Romance' },
  { value: 'mystery', label: 'Mystery' },
  { value: 'adventure', label: 'Adventure' },
  { value: 'slice-of-life', label: 'Slice of Life' },
  { value: 'historical', label: 'Historical' },
];

export default function StoryPublisher({
  conversationId,
  isOpen,
  onClose,
}: StoryPublisherProps) {
  const [title, setTitle] = useState('');
  const [synopsis, setSynopsis] = useState('');
  const [genre, setGenre] = useState('');
  const [tagsInput, setTagsInput] = useState('');
  const [isPreview, setIsPreview] = useState(false);

  const { publishStory, isLoading } = useCommunityStore();

  const tags = tagsInput
    .split(',')
    .map((t) => t.trim())
    .filter(Boolean);

  const canPublish = title.trim().length > 0;

  const handlePublish = async () => {
    if (!canPublish) return;

    await publishStory({
      conversationId,
      title: title.trim(),
      synopsis: synopsis.trim() || undefined,
      genre: genre || undefined,
      tags: tags.length > 0 ? tags : undefined,
    });

    // Reset form and close
    setTitle('');
    setSynopsis('');
    setGenre('');
    setTagsInput('');
    setIsPreview(false);
    onClose();
  };

  const handleRemoveTag = (tagToRemove: string) => {
    const updatedTags = tags.filter((t) => t !== tagToRemove);
    setTagsInput(updatedTags.join(', '));
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Publish Story"
      description="Share your story with the community."
      size="lg"
      footer={
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Switch
              size="sm"
              checked={isPreview}
              onChange={(e) => setIsPreview(e.target.checked)}
              label="Preview"
            />
          </div>
          <div className="flex items-center gap-3">
            <Button variant="ghost" size="sm" onClick={onClose}>
              Cancel
            </Button>
            <Button
              size="sm"
              onClick={() => void handlePublish()}
              disabled={!canPublish}
              isLoading={isLoading}
              loadingText="Publishing..."
              leftIcon={<Send size={14} />}
            >
              Publish
            </Button>
          </div>
        </div>
      }
    >
      {isPreview ? (
        /* Preview Mode */
        <div className="space-y-4">
          <Card className="p-4">
            <h3 className="text-lg font-bold text-foreground">
              {title || 'Untitled Story'}
            </h3>
            <div className="mt-2 flex items-center gap-2">
              <GenreTag genre={genre} />
            </div>
            {synopsis && (
              <p className="mt-3 whitespace-pre-wrap text-sm leading-relaxed text-foreground">
                {synopsis}
              </p>
            )}
            {tags.length > 0 && (
              <div className="mt-3 flex flex-wrap gap-1.5">
                {tags.map((tag) => (
                  <span
                    key={tag}
                    className="inline-flex items-center gap-1 rounded-md bg-accent/50 px-2 py-0.5 text-xs text-muted-foreground"
                  >
                    <Tag size={10} />
                    {tag}
                  </span>
                ))}
              </div>
            )}
          </Card>
          <p className="text-center text-xs text-muted-foreground">
            This is how your story will appear to others.
          </p>
        </div>
      ) : (
        /* Edit Mode */
        <div className="space-y-4">
          {/* Title */}
          <Input
            label="Title"
            placeholder="Enter a title for your story"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            error={title.length > 0 && title.trim().length === 0 ? 'Title cannot be blank' : undefined}
          />

          {/* Synopsis / Description */}
          <Textarea
            label="Synopsis"
            placeholder="Write a brief synopsis or description..."
            value={synopsis}
            onChange={(e) => setSynopsis(e.target.value)}
            resize="vertical"
            minRows={4}
            maxLength={2000}
            showCount
          />

          {/* Genre */}
          <Select
            label="Genre"
            options={GENRE_OPTIONS}
            value={genre}
            onChange={(e) => setGenre(e.target.value)}
          />

          {/* Tags */}
          <div>
            <Input
              label="Tags"
              placeholder="Comma-separated tags (e.g. dragons, magic, quest)"
              value={tagsInput}
              onChange={(e) => setTagsInput(e.target.value)}
              helperText="Separate tags with commas"
            />
            {tags.length > 0 && (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {tags.map((tag) => (
                  <span
                    key={tag}
                    className="inline-flex items-center gap-1 rounded-md bg-accent/50 px-2 py-0.5 text-xs text-muted-foreground"
                  >
                    <Tag size={10} />
                    {tag}
                    <button
                      type="button"
                      onClick={() => handleRemoveTag(tag)}
                      className="ml-0.5 rounded-sm text-muted-foreground transition-colors hover:text-foreground"
                    >
                      <X size={10} />
                    </button>
                  </span>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </Modal>
  );
}
