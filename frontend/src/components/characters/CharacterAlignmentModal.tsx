import { useState } from 'react';
import { Modal } from '@/components/ui/Modal';
import { CharacterAlignmentPanel } from './CharacterAlignmentPanel';

export function CharacterAlignmentModal({
  characterId,
  onClose,
}: {
  characterId?: string;
  onClose: () => void;
}) {
  const [saving, setSaving] = useState(false);
  return (
    <Modal
      isOpen
      onClose={() => {
        if (!saving) onClose();
      }}
      title="Moral alignment"
      size="xl"
      description="Lawful to chaotic and good to evil, measured in points that persist across chats. Alignment guides a character's values and methods; it never overrides the user's choices."
      closeOnEscape={!saving}
      closeOnOverlayClick={!saving}
      showCloseButton={!saving}
    >
      <CharacterAlignmentPanel characterId={characterId} onSavingChange={setSaving} />
    </Modal>
  );
}
