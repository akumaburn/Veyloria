import { useState } from 'react';
import { useJoinPartyByCode } from '@/hooks/useRoleplay';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Ticket } from 'lucide-react';

interface PartyInviteProps {
  isOpen?: boolean;
  onClose?: () => void;
  onJoin?: (partyId: string) => void;
}

export default function PartyInvite({ isOpen = true, onClose, onJoin }: PartyInviteProps) {
  const { joinPartyByCode, loading } = useJoinPartyByCode();
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const handleJoin = async () => {
    if (!code.trim()) return;
    setError(null);
    try {
      const result = await joinPartyByCode(code.trim());
      setSuccess(true);
      setCode('');
      const partyId = result?.data?.joinPartyByCode?.partyId;
      if (partyId && onJoin) {
        onJoin(partyId);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to join party');
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={() => onClose?.()}
      title="Join a party"
      description="Enter the invite code another player shared with you."
      footer={
        success ? (
          <div className="flex justify-end">
            <Button onClick={() => onClose?.()}>Done</Button>
          </div>
        ) : (
          <div className="flex justify-end gap-3">
            <Button variant="ghost" onClick={() => onClose?.()}>
              Cancel
            </Button>
            <Button onClick={handleJoin} isLoading={loading} disabled={!code.trim()}>
              Join party
            </Button>
          </div>
        )
      }
    >
      {success ? (
        <div className="rounded-xl border border-success/20 bg-success/10 p-4 text-center">
          <p className="text-sm text-success">You joined the party.</p>
        </div>
      ) : (
        <Input
          label="Invite code"
          value={code}
          onChange={(e) => {
            setCode(e.target.value);
            setError(null);
          }}
          placeholder="Enter invite code"
          leftIcon={<Ticket size={14} />}
          error={error ?? undefined}
          onKeyDown={(e) => e.key === 'Enter' && handleJoin()}
        />
      )}
    </Modal>
  );
}
