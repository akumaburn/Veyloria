import { useState } from 'react';
import { useCreateParty } from '@/hooks/useRoleplay';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Textarea } from '@/components/ui/Textarea';
import { Copy, Check } from 'lucide-react';

interface PartyCreatorProps {
  isOpen?: boolean;
  onClose?: () => void;
  onCreated?: (partyId: string) => void;
}

export default function PartyCreator({ isOpen: externalOpen, onClose, onCreated }: PartyCreatorProps) {
  const { createParty, loading } = useCreateParty();
  const [internalOpen, setInternalOpen] = useState(false);
  const isOpen = externalOpen ?? internalOpen;
  const setIsOpen = (open: boolean) => {
    setInternalOpen(open);
    if (!open && onClose) onClose();
  };
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [maxMembers, setMaxMembers] = useState('6');
  const [createdCode, setCreatedCode] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const handleCreate = async () => {
    if (!name.trim()) return;
    try {
      const result = await createParty({
        name: name.trim(),
        description: description.trim() || undefined,
        maxMembers: parseInt(maxMembers) || 6,
      });
      const party = result?.data?.createParty;
      if (party) {
        setCreatedCode(party.inviteCode ?? null);
        if (onCreated) onCreated(party.partyId);
      }
    } catch {
      // Error handled by hook
    }
  };

  const handleCopyCode = async () => {
    if (!createdCode) return;
    await navigator.clipboard.writeText(createdCode);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleClose = () => {
    setIsOpen(false);
    setName('');
    setDescription('');
    setMaxMembers('6');
    setCreatedCode(null);
    setCopied(false);
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleClose}
      title="Create a New Party"
      footer={
        createdCode ? (
          <div className="flex justify-end">
            <Button onClick={handleClose}>Done</Button>
          </div>
        ) : (
          <div className="flex justify-end gap-3">
            <Button variant="ghost" onClick={handleClose}>Cancel</Button>
            <Button onClick={handleCreate} isLoading={loading} disabled={!name.trim()}>
              Create Party
            </Button>
          </div>
        )
      }
    >
      {createdCode ? (
        <div className="space-y-4 text-center">
          <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-success/15">
            <Check className="h-7 w-7 text-success" />
          </div>
          <div>
            <h4 className="text-sm font-semibold text-foreground">Party Created!</h4>
            <p className="mt-1 text-xs text-muted-foreground">Share this invite code with others</p>
          </div>
          <div className="flex items-center gap-2 rounded-lg bg-card px-4 py-3">
            <span className="min-w-0 flex-1 text-center font-mono text-lg font-semibold tracking-wider break-all text-foreground">
              {createdCode}
            </span>
            <Button size="sm" variant="ghost" className="h-10 shrink-0 sm:h-8" onClick={handleCopyCode}>
              <Copy size={14} />
              <span className="ml-1 text-xs">{copied ? 'Copied' : 'Copy'}</span>
            </Button>
          </div>
        </div>
      ) : (
        <div className="space-y-4">
          <Input
            label="Party Name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g., The Fellowship"
          />
          <Textarea
            label="Description (optional)"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="What is this party about?"
            minRows={2}
          />
          <Input
            label="Max Members"
            type="number"
            value={maxMembers}
            onChange={(e) => setMaxMembers(e.target.value)}
            helperText="Maximum number of players allowed"
          />
        </div>
      )}
    </Modal>
  );
}
