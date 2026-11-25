import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@apollo/client';
import { Link } from 'react-router-dom';
import { Loader2, Swords } from 'lucide-react';
import { toast } from 'react-hot-toast';
import {
  useActiveBattle,
  useInitiateBattle,
  useVoteAction,
  useResolveBattleRound,
  useEndBattle,
  useBattleRoundSubscription,
  useBattleEndedSubscription,
  useInventoryAccess,
} from '@/hooks/useRoleplay';
import { BATTLE_QUERY, BATTLES_QUERY, PROPOSE_BATTLE_ACTION } from '@/graphql/roleplay-operations';
import type { BattleAction } from '@/graphql/types';

interface Props {
  conversationId: string;
  battleId?: string;
  currentUserId?: string;
  participants: Array<{
    character: { id: string; name: string; avatar?: string };
    isActive: boolean;
  }>;
  narratorMode?: boolean;
}
const control =
  'min-h-11 rounded-lg border border-border px-3 py-2 text-sm hover:bg-accent disabled:opacity-50 sm:min-h-0';

export default function BattleOverlay({
  conversationId,
  battleId,
  currentUserId,
  narratorMode = false,
}: Props) {
  const active = useActiveBattle(conversationId);
  const access = useInventoryAccess(conversationId, undefined, currentUserId);
  const history = useQuery(BATTLES_QUERY, {
    variables: { conversationId },
    fetchPolicy: 'cache-and-network',
  });
  const selected = useQuery(BATTLE_QUERY, {
    variables: { battleId },
    skip: !battleId,
    fetchPolicy: 'cache-and-network',
  });
  const { round } = useBattleRoundSubscription(conversationId);
  const { battleEnded } = useBattleEndedSubscription(conversationId);
  const battle = battleId ? selected.data?.battle : active.battle || history.data?.battles?.[0];
  const { initiateBattle, loading: initiating } = useInitiateBattle();
  const { voteAction } = useVoteAction();
  const { resolveBattleRound } = useResolveBattleRound();
  const { endBattle } = useEndBattle();
  const [propose] = useMutation(PROPOSE_BATTLE_ACTION);
  const [pending, setPending] = useState(false);
  const locked = useRef(false);
  const [approach, setApproach] = useState('');
  const [showForm, setShowForm] = useState(false);
  useEffect(() => {
    if (round || battleEnded) {
      void history.refetch();
      if (battleId) void selected.refetch();
    }
  }, [round, battleEnded, battleId, history.refetch, selected.refetch]);
  useEffect(() => {
    setApproach('');
  }, [battle?.battleId, battle?.roundNumber]);
  const run = async (action: () => Promise<unknown>) => {
    if (locked.current) return;
    locked.current = true;
    setPending(true);
    try {
      await action();
      await Promise.all([
        active.refetch(),
        history.refetch(),
        ...(battleId ? [selected.refetch()] : []),
      ]);
    } catch (error) {
      toast.error(
        error instanceof Error ? error.message : 'Could not update the encounter. Try again.',
      );
    } finally {
      locked.current = false;
      setPending(false);
    }
  };
  let data: any = {},
    actions: BattleAction[] = [];
  try {
    data = JSON.parse(battle?.battleData || '{}');
    actions = JSON.parse(battle?.actionOptions || '{}').actions || [];
  } catch {
    /* legacy data */
  }
  const votes: Array<{ userId: string; optionIndex: number }> = battle?.votes || [];
  const ownVote = votes.find((vote) => vote.userId === currentUserId);
  const ongoing = battle?.status === 'ACTIVE';
  const manager = battle?.canManage === true;
  const loading = battleId ? selected.loading : active.loading;
  const error = battleId ? selected.error : active.error;
  return (
    <div className="space-y-4">
      {error && (
        <div role="alert" className="space-y-2 text-sm">
          <p>Could not load the encounter.</p>
          <button
            className={control}
            onClick={() => void (battleId ? selected.refetch() : active.refetch())}
          >
            Retry
          </button>
        </div>
      )}
      {loading && !battle && (
        <p role="status" className="text-muted-foreground flex items-center gap-2 text-sm">
          <Loader2 size={16} className="animate-spin" />
          Loading encounter…
        </p>
      )}
      {battle && (
        <>
          <div className="space-y-1">
            <h3 className="flex min-w-0 items-center gap-2 text-base font-semibold break-words">
              <Swords size={18} className="shrink-0" />
              {data.enemyName || 'Encounter'}
            </h3>
            <p className="text-muted-foreground text-sm">
              {ongoing ? `Round ${battle.roundNumber}` : `Outcome: ${battle.status.toLowerCase()}`}
            </p>
          </div>
          {Number.isFinite(data.enemyHp) && (
            <div className="space-y-1">
              <div className="flex justify-between gap-2 text-sm">
                <span>Enemy health</span>
                <span>
                  {data.enemyHp} / {data.enemyMaxHp ?? data.enemyHp}
                </span>
              </div>
              <progress
                aria-label="Enemy health"
                className="accent-destructive h-2 w-full"
                value={Math.max(0, data.enemyHp)}
                max={data.enemyMaxHp || Math.max(1, data.enemyHp)}
              />
            </div>
          )}
          <p className="text-sm leading-relaxed whitespace-pre-wrap">
            {battle.currentNarration || 'Preparing the opening scene…'}
          </p>
          {data.partyStatus && <p className="text-muted-foreground text-sm">{data.partyStatus}</p>}
          {ongoing && battle.roundNumber > 0 && (
            <>
              <p className="text-muted-foreground text-sm">
                Choose the party’s next move. You can change your vote until the round resolves.
                Ties favor the first listed action.
              </p>
              <div className="space-y-2" role="group" aria-label="Battle actions">
                {actions.map((action, index) => (
                  <button
                    key={index}
                    disabled={pending || !currentUserId}
                    aria-pressed={ownVote?.optionIndex === index}
                    className={`${control} w-full text-left ${ownVote?.optionIndex === index ? 'bg-primary/10 border-primary/50' : ''}`}
                    onClick={() =>
                      void run(() =>
                        voteAction({
                          battleId: battle.battleId,
                          optionIndex: index,
                          roundNumber: battle.roundNumber,
                        }),
                      )
                    }
                  >
                    <span className="block font-medium">
                      {action.title}
                      <span className="text-muted-foreground ml-2 text-xs">
                        {votes.filter((vote) => vote.optionIndex === index).length} votes
                        {ownVote?.optionIndex === index ? ' · Your vote' : ''}
                      </span>
                    </span>
                    <span className="text-muted-foreground mt-1 block text-sm">
                      {action.description}
                    </span>
                  </button>
                ))}
              </div>
              {actions.length < 6 && (
                <form
                  className="space-y-2"
                  onSubmit={(event) => {
                    event.preventDefault();
                    void run(async () => {
                      await propose({
                        variables: {
                          battleId: battle.battleId,
                          roundNumber: battle.roundNumber,
                          description: approach.trim(),
                        },
                      });
                      setApproach('');
                    });
                  }}
                >
                  <label className="block text-sm" htmlFor="battle-approach">
                    Describe another approach
                  </label>
                  <textarea
                    id="battle-approach"
                    value={approach}
                    onChange={(event) => setApproach(event.target.value)}
                    maxLength={600}
                    rows={2}
                    placeholder="Distract the guards while Elara reaches the gate…"
                    className="border-border bg-background w-full rounded-lg border px-3 py-2 text-sm"
                  />
                  <button disabled={pending || !approach.trim()} className={control}>
                    Propose to the party
                  </button>
                </form>
              )}
              <button
                className={`${control} text-primary w-full`}
                disabled={pending || votes.length === 0}
                onClick={() =>
                  void run(() => resolveBattleRound(battle.battleId, battle.roundNumber))
                }
              >
                {pending ? 'Updating encounter…' : 'Resolve party action'}
              </button>
              <p className="text-muted-foreground text-xs leading-relaxed">
                {manager
                  ? 'As host, you can resolve once a vote is cast.'
                  : 'The round can resolve once every active player has voted.'}{' '}
                Proposed approaches are attempts; the narrator determines their consequences.
              </p>
              {manager && narratorMode && (
                <details className="border-border border-t pt-3">
                  <summary className="text-muted-foreground cursor-pointer py-2 text-sm sm:py-0">
                    Host outcome override
                  </summary>
                  <p className="text-muted-foreground my-2 text-xs">
                    End this encounter manually. This records the outcome without inventing rewards.
                  </p>
                  <div className="flex flex-wrap gap-2">
                    {['VICTORY', 'DEFEAT', 'RETREAT'].map((status) => (
                      <button
                        key={status}
                        disabled={pending}
                        className={control}
                        onClick={() => void run(() => endBattle(battle.battleId, status))}
                      >
                        {status.toLowerCase()}
                      </button>
                    ))}
                  </div>
                </details>
              )}
            </>
          )}
          {!ongoing && (
            <p className="text-muted-foreground text-sm">
              The outcome is saved in chat. Continue the story here, or return to the map to resume
              your route.
            </p>
          )}
        </>
      )}
      {!battle && !loading && !error && (
        <p className="text-muted-foreground text-sm">
          No active encounter. Combat begins from a travel encounter, or the host can start one from
          the current story.
        </p>
      )}
      {!active.battle && narratorMode && access.canManage && (
        <button className={control} onClick={() => setShowForm(!showForm)}>
          Start an encounter
        </button>
      )}
      {showForm && !active.battle && access.canManage && (
        <form
          className="space-y-3"
          onSubmit={(event) => {
            event.preventDefault();
            const values = new FormData(event.currentTarget);
            void run(async () => {
              await initiateBattle({
                conversationId,
                enemyName: values.get('enemyName'),
                enemyDescription: values.get('enemyDescription'),
                enemyHp: Number(values.get('enemyHp')),
                narrativeContext: values.get('context'),
              });
              setShowForm(false);
            });
          }}
        >
          <label className="block space-y-1 text-sm">
            <span>Opponent</span>
            <input
              name="enemyName"
              required
              maxLength={255}
              className="border-border bg-background w-full rounded-lg border px-3 py-2"
            />
          </label>
          <label className="block space-y-1 text-sm">
            <span>Description</span>
            <input
              name="enemyDescription"
              maxLength={2000}
              className="border-border bg-background w-full rounded-lg border px-3 py-2"
            />
          </label>
          <label className="block space-y-1 text-sm">
            <span>Starting health</span>
            <input
              name="enemyHp"
              type="number"
              min={1}
              max={1000000}
              defaultValue={20}
              required
              className="border-border bg-background w-full rounded-lg border px-3 py-2"
            />
          </label>
          <label className="block space-y-1 text-sm">
            <span>What led to this confrontation?</span>
            <textarea
              name="context"
              maxLength={4000}
              className="border-border bg-background w-full rounded-lg border px-3 py-2"
            />
          </label>
          <button className={control} disabled={pending || initiating}>
            {pending || initiating ? 'Preparing encounter…' : 'Begin encounter'}
          </button>
        </form>
      )}
      {history.data?.battles?.length > 0 && (
        <div className="border-border space-y-2 border-t pt-3">
          <h4 className="text-sm font-medium">Encounter history</h4>
          {history.data.battles.map((entry: any) => {
            let name = 'Encounter';
            try {
              name = JSON.parse(entry.battleData || '{}').enemyName || name;
            } catch {
              // Legacy encounters may have invalid metadata; keep the fallback name.
            }
            return (
              <Link
                key={entry.battleId}
                to={`/chat/${conversationId}?battle=${encodeURIComponent(entry.battleId)}`}
                className="text-primary flex min-h-10 items-center text-sm break-words hover:underline sm:block sm:min-h-0"
              >
                {name} · {entry.status.toLowerCase()}
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}
