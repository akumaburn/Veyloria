import { useState } from 'react';
import { Modal } from '@/components/ui/Modal';
import { CharacterRelationshipsPanel } from './CharacterRelationshipsPanel';

export function CharacterRelationshipsModal({
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
      title="Relationships"
      size="lg"
      description="Personal feelings that persist across chats and shape how characters respond. Each direction has its own score."
      closeOnEscape={!saving}
      closeOnOverlayClick={!saving}
      showCloseButton={!saving}
    >
      <CharacterRelationshipsPanel characterId={characterId} onSavingChange={setSaving} />
    </Modal>
  );
}
