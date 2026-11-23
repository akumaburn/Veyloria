import { useNarratorMode } from '@/stores/narratorModeStore';
import { useState, useEffect } from 'react';
import { MapPin, Users, ArrowRight, Theater } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Textarea } from '@/components/ui/Textarea';
import { Modal } from '@/components/ui/Modal';
import { useActiveScene, useChangeScene, useSceneChanged } from '@/hooks/useRoleplay';
import { cn } from '@/lib/utils';
import type { Character } from '@/graphql/types';
import { useCharacterCardStore } from '@/stores/characterCardStore';

interface SceneIndicatorProps {
  conversationId: string;
  className?: string;
}

export default function SceneIndicator({ conversationId, className }: SceneIndicatorProps) {
  const narratorMode = useNarratorMode(conversationId);
  const { scene, loading, refetch } = useActiveScene(conversationId);
  const { changeScene, loading: changing } = useChangeScene();
  const [showChangeModal, setShowChangeModal] = useState(false);
  const openCharacterCard = useCharacterCardStore((state) => state.openCharacterCard);

  // Subscribe to real-time scene changes from the agent pipeline
  const { scene: sceneEvent } = useSceneChanged(conversationId);

  useEffect(() => {
    if (sceneEvent) refetch();
  }, [sceneEvent, refetch]);

  const [newSceneName, setNewSceneName] = useState('');
  const [newLocation, setNewLocation] = useState('');
  const [newDescription, setNewDescription] = useState('');

  const handleChangeScene = async () => {
    if (!narratorMode || !newSceneName.trim()) return;

    await changeScene({
      conversationId,
      sceneName: newSceneName.trim(),
      location: newLocation.trim() || undefined,
      description: newDescription.trim() || undefined,
    });

    setNewSceneName('');
    setNewLocation('');
    setNewDescription('');
    setShowChangeModal(false);
  };

  const handleOpenModal = () => {
    setNewSceneName('');
    setNewLocation(scene?.location || '');
    setNewDescription('');
    setShowChangeModal(true);
  };

  if (loading && !scene) {
    return (
      <div className={cn('flex h-10 animate-pulse items-center gap-2 rounded-lg bg-muted px-4', className)}>
        <div className="h-3 w-24 rounded bg-accent" />
        <div className="h-3 w-16 rounded bg-accent" />
      </div>
    );
  }

  if (!scene) {
    return null;
  }

  const characters = scene.characters as Pick<Character, 'id' | 'name' | 'avatar'>[] | undefined;

  // The wrapper (not the card) carries layout classes so callers can band it —
  // when there is no scene this whole subtree, band included, renders nothing.
  return (
    <div className={className}>
      <div
        className="flex items-center gap-3 rounded-lg border border-border/50 bg-card px-4 py-2"
      >
        {/* Scene Icon */}
        <Theater size={16} className="flex-shrink-0 text-primary" />

        {/* Scene Name */}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-sm font-medium text-foreground">
              {scene.name}
            </span>
            {scene.location && (
              <>
                <span className="text-muted-foreground/50">|</span>
                <span className="flex items-center gap-1 text-xs text-muted-foreground">
                  <MapPin size={12} />
                  {scene.location}
                </span>
              </>
            )}
          </div>
        </div>

        {/* Characters Present */}
        {characters && characters.length > 0 && (
          <div className="flex items-center gap-1">
            <Users size={14} className="text-muted-foreground" />
            <div className="flex -space-x-1.5">
              {characters.slice(0, 4).map((char) => (
                <button
                  key={char.id}
                  type="button"
                  onClick={() => openCharacterCard(char.id, conversationId)}
                  title={`Open ${char.name}'s character card`}
                  aria-label={`Open ${char.name}'s character card`}
                  className="flex h-6 w-6 cursor-pointer items-center justify-center rounded-full border-2 border-card bg-muted text-[10px] font-bold uppercase text-muted-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                >
                  {char.avatar ? (
                    <img
                      src={char.avatar}
                      alt={char.name}
                      className="h-full w-full rounded-full object-cover"
                    />
                  ) : (
                    char.name?.charAt(0) ?? '?'
                  )}
                </button>
              ))}
              {characters.length > 4 && (
                <div className="flex h-6 w-6 items-center justify-center rounded-full border-2 border-card bg-muted text-[10px] font-medium text-muted-foreground">
                  +{characters.length - 4}
                </div>
              )}
            </div>
          </div>
        )}

        {/* Change Scene Button */}
        {narratorMode && <Button
          variant="ghost"
          size="sm"
          onClick={handleOpenModal}
          leftIcon={<ArrowRight size={14} />}
        >
          Change Scene
        </Button>}
      </div>

      {/* Change Scene Modal */}
      <Modal
        isOpen={narratorMode && showChangeModal}
        onClose={() => setShowChangeModal(false)}
        title="Change Scene"
        description="Transition to a new scene in the story."
        size="md"
        footer={
          <div className="flex justify-end gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setShowChangeModal(false)}
              disabled={changing}
            >
              Cancel
            </Button>
            <Button
              variant="primary"
              size="sm"
              onClick={handleChangeScene}
              isLoading={changing}
              loadingText="Changing..."
              disabled={!newSceneName.trim()}
              leftIcon={<ArrowRight size={14} />}
            >
              Change Scene
            </Button>
          </div>
        }
      >
        <div className="space-y-4">
          {scene && (
            <div className="rounded-lg bg-muted/50 px-3 py-2">
              <p className="text-xs text-muted-foreground">Current Scene</p>
              <p className="text-sm font-medium text-foreground">{scene.name}</p>
              {scene.location && (
                <p className="mt-0.5 text-xs text-muted-foreground">
                  <MapPin size={10} className="mr-1 inline-block" />
                  {scene.location}
                </p>
              )}
            </div>
          )}

          <Input
            label="New Scene Name"
            placeholder="Enter the name of the new scene..."
            value={newSceneName}
            onChange={(e) => setNewSceneName(e.target.value)}
            error={newSceneName.trim() === '' ? undefined : undefined}
          />

          <Input
            label="Location"
            placeholder="Where does this scene take place? (optional)"
            value={newLocation}
            onChange={(e) => setNewLocation(e.target.value)}
            leftIcon={<MapPin size={14} />}
          />

          <Textarea
            label="Scene Description"
            placeholder="Describe the atmosphere, environment, or what is happening... (optional)"
            value={newDescription}
            onChange={(e) => setNewDescription(e.target.value)}
            minRows={3}
            maxRows={6}
            autoResize
          />
        </div>
      </Modal>
    </div>
  );
}
