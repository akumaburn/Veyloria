import { useAuthStore } from '@/stores/authStore';
import { useRef, useState } from 'react';
import { toast } from 'react-hot-toast';
import {
  useActiveJourney,
  useResumeJourney,
  useCancelJourney,
} from '@/components/map/hooks/useMapData';
import { useActiveBattle, useInventoryAccess } from '@/hooks/useRoleplay';

export default function JourneyNotice({ conversationId }: { conversationId: string }) {
  const { activeJourney: journey } = useActiveJourney(conversationId);
  const { battle } = useActiveBattle(conversationId);
  const caller = useAuthStore((state) => state.user?.id);
  const access = useInventoryAccess(conversationId, undefined, caller);
  const mayContinue = journey?.userId === caller || access.canManage === true;
  const { resumeJourney } = useResumeJourney();
  const { cancelJourney } = useCancelJourney();
  const [pending, setPending] = useState(false);
  const locked = useRef(false);
  if (journey?.status !== 'PAUSED') return null;
  const run = async (action: () => Promise<unknown>) => {
    if (locked.current) return;
    locked.current = true;
    setPending(true);
    try {
      await action();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not update the journey');
    } finally {
      locked.current = false;
      setPending(false);
    }
  };
  return (
    <div
      className="border-border flex flex-wrap items-center justify-between gap-2 border-b px-4 py-2 text-sm"
      role="status"
    >
      <span>Route to {journey.toLocationName || 'your destination'} paused for an encounter.</span>
      <div className="flex flex-wrap gap-2">
        {!battle && (
          <button
            disabled={pending || !mayContinue}
            onClick={() => void run(() => resumeJourney(conversationId))}
            className="border-border hover:bg-accent rounded-md border px-3 py-2 disabled:opacity-50"
          >
            Continue journey
          </button>
        )}
        <button
          disabled={pending || !mayContinue}
          onClick={() => void run(() => cancelJourney(conversationId))}
          className="text-muted-foreground hover:bg-accent rounded-md px-3 py-2 disabled:opacity-50"
        >
          Abandon route
        </button>
      </div>
    </div>
  );
}
