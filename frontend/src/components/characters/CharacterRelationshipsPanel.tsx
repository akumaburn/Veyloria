import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@apollo/client';
import { ArrowRight, ArrowRightLeft, Heart, Loader2, RotateCcw } from 'lucide-react';
import {
  RELATIONSHIPS_QUERY,
  REACT_TO_CHARACTER_MUTATION,
  SET_CHARACTER_AFFINITY_MUTATION,
} from '@/graphql/operations';
import { cn } from '@/lib/utils';
import {
  AFFINITY_TIERS,
  actorKey,
  REACTIONS,
  signedPoints,
  tierLabel,
  tierOf,
  type CharacterAffinity,
  type Reaction,
  type RelationshipsData,
} from './relationships';

const fieldClass =
  'w-full min-w-0 rounded-xl border border-border bg-background px-3 py-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40 disabled:opacity-60';

export function CharacterRelationshipsPanel({
  characterId,
  onSavingChange,
}: {
  characterId?: string;
  onSavingChange?: (saving: boolean) => void;
}) {
  const { data, loading, error, refetch } = useQuery<RelationshipsData>(RELATIONSHIPS_QUERY, {
    fetchPolicy: 'network-only',
    errorPolicy: 'none',
  });
  const [react] = useMutation<{ reactToCharacter: CharacterAffinity }>(
    REACT_TO_CHARACTER_MUTATION,
    { errorPolicy: 'none' },
  );
  const [setExact] = useMutation<{ setCharacterAffinity: CharacterAffinity }>(
    SET_CHARACTER_AFFINITY_MUTATION,
    { errorPolicy: 'none' },
  );
  const [from, setFrom] = useState('user');
  const [to, setTo] = useState<string | null>(characterId ?? null);
  useEffect(() => {
    if (characterId) {
      setFrom('user');
      setTo(characterId);
    }
  }, [characterId]);
  const [reason, setReason] = useState('');
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const [confirmReset, setConfirmReset] = useState(false);
  const [saved, setSaved] = useState<CharacterAffinity[]>([]);
  const request = useRef<{ signature: string; id: string } | null>(null);
  const busy = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    onSavingChange?.(saving);
  }, [saving, onSavingChange]);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  useEffect(() => {
    if (to === null && data?.relationshipActors.length)
      setTo(data.relationshipActors[0].characterId);
  }, [data, to]);
  const affinities = [
    ...saved,
    ...(data?.characterAffinities ?? []).filter(
      (a) => !saved.some((s) => s.affinityId === a.affinityId),
    ),
  ];
  const actors = [{ characterId: 'user', name: 'You' }, ...(data?.relationshipActors ?? [])];
  const pair = affinities.find(
    (a) => actorKey(a.fromCharacterId) === from && actorKey(a.toCharacterId) === to,
  );
  const counterpart = actors.find((a) => a.characterId === (from === 'user' ? to : from));
  const startingPoints =
    from === 'user'
      ? (counterpart?.initialAffinityFromUser ?? 0)
      : (counterpart?.initialAffinityToUser ?? 0);
  const points = pair?.points ?? startingPoints;
  const tier = pair?.tier ?? tierOf(points);
  const actorName = (key: string | null) =>
    actors.find((a) => a.characterId === key)?.name ?? 'Choose a character';
  const valid =
    !!to &&
    from !== to &&
    actors.some((a) => a.characterId === from) &&
    actors.some((a) => a.characterId === to);
  const choosePair = (source: string, target: string | null) => {
    if (busy.current) return;
    setFrom(source);
    setTo(target);
    setReason('');
    setSaveError(null);
    setConfirmReset(false);
    setAnnouncement('');
  };
  const apply = async (kind: 'reaction' | 'points', value: Reaction | number) => {
    if (busy.current || !valid || loading || error) return;
    const signature = JSON.stringify([from, to, kind, value, reason.trim()]);
    if (request.current?.signature !== signature)
      request.current = { signature, id: crypto.randomUUID() };
    busy.current = true;
    setSaving(true);
    setSaveError(null);
    setConfirmReset(false);
    try {
      const variables = {
        fromCharacterId: from === 'user' ? null : from,
        toCharacterId: to === 'user' ? null : to,
        reason: reason.trim() || null,
        requestId: request.current.id,
      };
      let updated: CharacterAffinity | undefined;
      if (kind === 'reaction') {
        const { data } = await react({ variables: { ...variables, reaction: value as Reaction } });
        updated = data?.reactToCharacter;
      } else {
        const { data } = await setExact({ variables: { ...variables, points: value as number } });
        updated = data?.setCharacterAffinity;
      }
      if (!updated) throw new Error('The relationship change could not be saved. Please retry.');
      request.current = null;
      if (!mounted.current) return;
      setSaved((previous) => [
        updated,
        ...previous.filter((a) => a.affinityId !== updated.affinityId),
      ]);
      setReason('');
      setAnnouncement(
        `${updated.fromName} → ${updated.toName}: ${tierLabel(updated.tier)}, ${signedPoints(updated.points)} points. Saved.`,
      );
    } catch (failure) {
      if (mounted.current)
        setSaveError(
          failure instanceof Error ? failure.message : 'Unable to save this change. Please retry.',
        );
    } finally {
      busy.current = false;
      if (mounted.current) setSaving(false);
    }
  };
  const change = (reaction: Reaction) => apply('reaction', reaction);
  const chooseTier = (tier: string) => {
    const option = AFFINITY_TIERS.find((item) => item.tier === tier);
    if (option) void apply('points', option.points);
  };
  const related = affinities.filter(
    (a) => !characterId || a.fromCharacterId === characterId || a.toCharacterId === characterId,
  );
  return (
    <div>
      {loading ? (
        <p role="status" className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
          <Loader2 size={18} className="animate-spin" />
          Loading relationships…
        </p>
      ) : error ? (
        <div role="alert" className="space-y-3">
          <p>Unable to load relationships.</p>
          <button className={fieldClass} onClick={() => void refetch()}>
            Try again
          </button>
        </div>
      ) : !data?.relationshipActors.length ? (
        <p className="text-muted-foreground py-6 text-sm">
          Create a character to start building relationships.
        </p>
      ) : (
        <div className="space-y-5">
          <div className="grid grid-cols-1 items-end gap-2 sm:grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] sm:gap-3">
            <label className="min-w-0 space-y-1.5 text-xs font-medium">
              Feeling from
              <select
                className={fieldClass}
                value={from}
                disabled={saving}
                onChange={(e) => choosePair(e.target.value, e.target.value === to ? from : to)}
              >
                {actors.map((a) => (
                  <option key={a.characterId} value={a.characterId}>
                    {a.name}
                  </option>
                ))}
              </select>
            </label>
            <button
              type="button"
              aria-label="Reverse relationship direction"
              disabled={saving || !to}
              onClick={() => choosePair(to!, from)}
              className="text-muted-foreground hover:bg-accent mb-1 justify-self-center rounded-lg p-2 disabled:opacity-50 sm:justify-self-auto"
            >
              <ArrowRightLeft size={18} />
            </button>
            <label className="min-w-0 space-y-1.5 text-xs font-medium">
              Toward
              <select
                className={fieldClass}
                value={to ?? ''}
                disabled={saving}
                onChange={(e) => choosePair(from, e.target.value)}
              >
                {actors
                  .filter((a) => a.characterId !== from)
                  .map((a) => (
                    <option key={a.characterId} value={a.characterId}>
                      {a.name}
                    </option>
                  ))}
              </select>
            </label>
          </div>
          {related.length > 0 && (
            <section aria-label="Saved relationships" className="border-border border-t pt-4">
              <div className="mb-2 flex items-center justify-between gap-2">
                <h3 className="text-sm font-semibold">
                  {characterId ? 'Saved connections' : 'All saved connections'}
                </h3>
                <span className="text-muted-foreground text-2xs">{related.length} total</span>
              </div>
              <p className="text-muted-foreground mb-2 text-xs">
                Select a row to inspect or change that direction.
              </p>
              <div className="max-h-56 space-y-1 overflow-y-auto">
                {related.map((a) => (
                  <button
                    key={a.affinityId}
                    disabled={saving}
                    type="button"
                    onClick={() =>
                      choosePair(actorKey(a.fromCharacterId), actorKey(a.toCharacterId))
                    }
                    aria-pressed={a.affinityId === pair?.affinityId}
                    className="hover:bg-accent aria-pressed:bg-muted flex w-full items-center justify-between gap-2 rounded-lg px-2 py-2 text-left text-xs disabled:opacity-50"
                  >
                    <span className="flex min-w-0 items-center gap-1">
                      <span className="truncate">{a.fromName}</span>
                      <ArrowRight size={12} className="shrink-0" />
                      <span className="truncate">{a.toName}</span>
                    </span>
                    <span className="shrink-0 tabular-nums">
                      {tierLabel(a.tier)} · {signedPoints(a.points)}
                    </span>
                  </button>
                ))}
              </div>
            </section>
          )}
          <section
            aria-label="Current relationship"
            className="border-border bg-muted/30 rounded-xl border p-4"
          >
            <div className="flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="text-sm font-medium break-words">
                  {actorName(from)} <span className="text-muted-foreground">→</span> {actorName(to)}
                </p>
                <p className="text-muted-foreground mt-1 flex items-center gap-1.5 text-sm">
                  <Heart size={14} />
                  {tierLabel(tier)}
                  {!pair && (
                    <span className="text-2xs border-border rounded-md border px-1.5 py-0.5">
                      Starting value
                    </span>
                  )}
                </p>
              </div>
              <p className="shrink-0 text-2xl font-semibold tabular-nums">
                {signedPoints(points)}
                <span className="text-muted-foreground text-xs font-normal"> / 100</span>
              </p>
            </div>
            <div
              role="meter"
              aria-label="Relationship points"
              aria-valuemin={-100}
              aria-valuemax={100}
              aria-valuenow={points}
              aria-valuetext={`${tierLabel(tier)}, ${signedPoints(points)} points`}
              className="bg-accent relative mt-4 h-2 overflow-hidden rounded-full"
            >
              <div
                className={
                  points < 0 ? 'bg-destructive absolute h-full' : 'bg-primary absolute h-full'
                }
                style={{
                  left: `${Math.min(50, 50 + points / 2)}%`,
                  width: `${Math.abs(points) / 2}%`,
                }}
              />
              <div className="bg-foreground/40 absolute left-1/2 h-full w-px" />
            </div>
            <div className="text-muted-foreground mt-2 grid grid-cols-5 gap-1 text-center text-[10px] leading-relaxed sm:text-xs">
              <span>
                Hate
                <br />
                −100…−60
              </span>
              <span>
                Dislike
                <br />
                −59…−20
              </span>
              <span>
                Neutral
                <br />
                −19…+19
              </span>
              <span>
                Like
                <br />
                +20…+59
              </span>
              <span>
                Love
                <br />
                +60…+100
              </span>
            </div>
          </section>
          <div>
            <label className="block space-y-1.5 text-xs font-medium">
              Reason <span className="text-muted-foreground font-normal">(optional)</span>
              <textarea
                value={reason}
                maxLength={500}
                rows={2}
                disabled={saving}
                onChange={(e) => setReason(e.target.value)}
                className={fieldClass}
                placeholder="What happened between them?"
              />
            </label>
            <p className="text-muted-foreground mt-1 text-xs">
              Record a meaningful moment. Love can be platonic; feelings are not automatically
              mutual.
            </p>
            <div className="mt-3">
              <p className="text-xs font-medium">Set exact feeling</p>
              <div
                role="group"
                aria-label="Set exact feeling"
                className="mt-1.5 grid grid-cols-5 gap-1"
              >
                {AFFINITY_TIERS.map((option) => (
                  <button
                    key={option.tier}
                    type="button"
                    aria-pressed={tier === option.tier}
                    aria-label={`Set exact feeling: ${option.label.toLowerCase()}`}
                    disabled={saving || !valid}
                    onClick={() => chooseTier(option.tier)}
                    className={cn(
                      'min-h-9 rounded-lg border px-0.5 py-1.5 text-[11px] font-medium transition-colors disabled:opacity-40 sm:px-1 sm:text-xs',
                      tier === option.tier
                        ? 'border-primary bg-primary text-primary-foreground'
                        : 'border-border bg-background hover:bg-accent',
                      option.points < 0 && tier !== option.tier && 'text-destructive',
                    )}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </div>
            <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
              {REACTIONS.map((r) => (
                <button
                  type="button"
                  key={r.reaction}
                  disabled={saving || !valid || (r.delta < 0 ? points === -100 : points === 100)}
                  onClick={() => void change(r.reaction)}
                  className="border-border bg-background hover:bg-accent flex min-h-11 items-center justify-center gap-2 rounded-xl border px-3 py-2.5 text-sm font-medium transition-colors disabled:opacity-40"
                >
                  {r.label}
                  <span className={r.delta < 0 ? 'text-destructive' : 'text-primary'}>
                    {signedPoints(r.delta)}
                  </span>
                </button>
              ))}
            </div>
            <div className="mt-3 min-h-6 text-xs" aria-live="polite">
              {saving ? (
                <span className="text-muted-foreground flex items-center gap-2">
                  <Loader2 size={14} className="animate-spin" />
                  Saving change…
                </span>
              ) : (
                <span className="text-muted-foreground">
                  {announcement ||
                    'Changes are saved immediately. Normal messages do not award points.'}
                </span>
              )}
            </div>
            {saveError && (
              <p role="alert" className="text-destructive mt-2 text-sm">
                {saveError}
              </p>
            )}
          </div>
          <section aria-label="Relationship history" className="border-border border-t pt-4">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <h3 className="text-sm font-semibold">Recent changes</h3>
              <button
                type="button"
                disabled={saving || points === 0}
                onClick={() => setConfirmReset(true)}
                className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1.5 rounded-md p-1 text-xs disabled:opacity-40"
              >
                <RotateCcw size={12} />
                Reset to neutral
              </button>
            </div>
            {confirmReset && (
              <div className="border-border bg-muted mb-3 rounded-xl border p-3 text-sm">
                <p>
                  Reset this direction to 0? Its history and the reverse direction are preserved.
                </p>
                <div className="mt-3 flex gap-3">
                  <button
                    className="bg-primary text-primary-foreground rounded-lg px-3 py-2"
                    onClick={() => void change('RESET')}
                  >
                    Reset score
                  </button>
                  <button
                    className="hover:bg-accent rounded-lg px-3 py-2"
                    onClick={() => setConfirmReset(false)}
                  >
                    Cancel
                  </button>
                </div>
              </div>
            )}
            {!pair?.events.length ? (
              <p className="text-muted-foreground text-sm">
                No changes yet. This relationship starts neutral.
              </p>
            ) : (
              <ol className="max-h-56 space-y-3 overflow-y-auto pr-1">
                {pair.events.map((event) => (
                  <li key={event.eventId} className="flex items-start gap-3 text-xs">
                    <span className="bg-muted min-w-9 rounded-md px-1.5 py-1 text-center font-medium tabular-nums">
                      {signedPoints(event.delta)}
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="font-medium">
                        {tierLabel(event.reaction)}{' '}
                        <span className="text-muted-foreground font-normal">
                          · {signedPoints(event.previousPoints)} → {signedPoints(event.points)}
                        </span>
                      </p>
                      {event.reason && (
                        <p className="text-muted-foreground mt-1 break-words whitespace-pre-wrap">
                          {event.reason}
                        </p>
                      )}
                      <time
                        className="text-muted-foreground mt-1 block"
                        dateTime={`${event.createdAt}Z`}
                      >
                        {new Date(`${event.createdAt}Z`).toLocaleString()}
                      </time>
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
