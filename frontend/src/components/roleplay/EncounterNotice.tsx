import { Link } from 'react-router-dom';
import { Swords } from 'lucide-react';
import { useActiveBattle } from '@/hooks/useRoleplay';

export default function EncounterNotice({ conversationId }: { conversationId: string }) {
  const { battle, error, refetch } = useActiveBattle(conversationId);
  if (error)
    return (
      <div
        role="alert"
        className="border-border flex items-center justify-between gap-3 border-b px-4 py-2 text-sm"
      >
        <span>Encounter status unavailable.</span>
        <button onClick={() => void refetch()} className="text-primary">
          Retry
        </button>
      </div>
    );
  if (!battle) return null;
  let name = 'Encounter';
  try {
    name = JSON.parse(battle.battleData || '{}').enemyName || name;
  } catch {
    /* legacy metadata */
  }
  return (
    <div
      className="border-border bg-card flex flex-wrap items-center justify-between gap-2 border-b px-4 py-2"
      role="status"
    >
      <span className="flex min-w-0 items-center gap-2 text-sm">
        <Swords size={16} className="text-destructive shrink-0" />
        <span className="break-words">
          {name} · {battle.roundNumber > 0 ? `Round ${battle.roundNumber}` : 'Preparing encounter…'}
        </span>
      </span>
      <Link
        to={`/chat/${conversationId}?battle=${encodeURIComponent(battle.battleId)}`}
        className="text-primary hover:bg-accent shrink-0 rounded-md px-3 py-2 text-sm font-medium"
      >
        Open fight in chat
      </Link>
    </div>
  );
}
