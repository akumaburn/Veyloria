import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@apollo/client';
import { Compass, Loader2, RotateCcw, Scale } from 'lucide-react';
import { Textarea } from '@/components/ui/Textarea';
import { cn } from '@/lib/utils';
import {
  ADJUST_CHARACTER_ALIGNMENT_MUTATION,
  CHARACTER_ALIGNMENTS_QUERY,
  SET_CHARACTER_ALIGNMENT_MUTATION,
} from '@/graphql/operations';
import {
  actorAlignmentKey,
  ALIGNMENT_ORDER,
  ALIGNMENTS,
  alignmentMeta,
  alignmentOf,
  signedAlignment,
  type Alignment,
  type AlignmentData,
  type CharacterAlignment,
} from './alignment';

type Change =
  | { kind: 'set'; lawfulness: number; goodness: number }
  | { kind: 'adjust'; lawfulnessDelta: number; goodnessDelta: number };

const axisButton =
  'flex h-8 min-w-9 items-center justify-center rounded-lg border border-border bg-background px-2 text-xs font-medium tabular-nums text-foreground transition-colors hover:bg-accent disabled:opacity-40';

function AxisMeter({
  label,
  low,
  high,
  value,
  saving,
  lowColor,
  highColor,
  onNudge,
}: {
  label: string;
  low: string;
  high: string;
  value: number;
  saving: boolean;
  lowColor: string;
  highColor: string;
  onNudge: (delta: number) => void;
}) {
  return (
    <div>
      <div className="mb-1.5 flex items-center justify-between gap-2">
        <span className="text-xs font-medium">{label}</span>
        <span className="text-sm font-semibold tabular-nums">{signedAlignment(value)}</span>
      </div>
      <div className="flex items-center gap-2">
        <span className="text-2xs text-muted-foreground w-12 shrink-0 text-right">{low}</span>
        <div
          role="meter"
          aria-label={`${label} points`}
          aria-valuemin={-100}
          aria-valuemax={100}
          aria-valuenow={value}
          aria-valuetext={`${signedAlignment(value)} ${label.toLowerCase()} points, ${
            value < 0 ? low : value > 0 ? high : 'neutral'
          }`}
          className="bg-accent relative h-2 flex-1 overflow-hidden rounded-full"
        >
          <div className="bg-foreground/30 absolute left-1/2 h-full w-px" />
          <div
            className={cn('absolute h-full rounded-full', value >= 0 ? highColor : lowColor)}
            style={{
              left: value >= 0 ? '50%' : `${50 + value / 2}%`,
              width: `${Math.abs(value) / 2}%`,
            }}
          />
        </div>
        <span className="text-2xs text-muted-foreground w-12 shrink-0">{high}</span>
        <div className="flex shrink-0 gap-1">
          <button
            type="button"
            className={axisButton}
            disabled={saving || value <= -100}
            onClick={() => onNudge(-10)}
            aria-label={`Move ${label.toLowerCase()} 10 points toward ${low}`}
          >
            −10
          </button>
          <button
            type="button"
            className={axisButton}
            disabled={saving || value >= 100}
            onClick={() => onNudge(10)}
            aria-label={`Move ${label.toLowerCase()} 10 points toward ${high}`}
          >
            +10
          </button>
        </div>
      </div>
    </div>
  );
}

export function CharacterAlignmentPanel({
  characterId,
  onSavingChange,
  onRecord,
}: {
  characterId?: string;
  onSavingChange?: (saving: boolean) => void;
  onRecord?: (record: CharacterAlignment) => void;
}) {
  const { data, loading, error, refetch } = useQuery<AlignmentData>(CHARACTER_ALIGNMENTS_QUERY, {
    fetchPolicy: 'network-only',
    errorPolicy: 'none',
  });
  const [setAlignment] = useMutation<{ setCharacterAlignment: CharacterAlignment }>(
    SET_CHARACTER_ALIGNMENT_MUTATION,
    { errorPolicy: 'none', refetchQueries: ['CharacterAlignments', 'CharacterAlignmentSummary'] },
  );
  const [adjustAlignment] = useMutation<{ adjustCharacterAlignment: CharacterAlignment }>(
    ADJUST_CHARACTER_ALIGNMENT_MUTATION,
    { errorPolicy: 'none', refetchQueries: ['CharacterAlignments', 'CharacterAlignmentSummary'] },
  );
  const [selected, setSelected] = useState(characterId ?? 'user');
  useEffect(() => {
    if (characterId) setSelected(characterId);
  }, [characterId]);
  const [reason, setReason] = useState('');
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const [confirmReset, setConfirmReset] = useState(false);
  const [saved, setSaved] = useState<CharacterAlignment[]>([]);
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

  const records = [
    ...saved,
    ...(data?.characterAlignments ?? []).filter(
      (record) => !saved.some((local) => local.alignmentId === record.alignmentId),
    ),
  ];
  const actors = [{ characterId: null, name: 'You' }, ...(data?.relationshipActors ?? [])];
  const selectedActor =
    actors.find((actor) => actorAlignmentKey(actor.characterId) === selected) ?? actors[0];
  const record = records.find((item) => actorAlignmentKey(item.characterId) === selected);
  const lawfulness = record?.lawfulness ?? 0;
  const goodness = record?.goodness ?? 0;
  const alignment = record?.alignment ?? alignmentOf(lawfulness, goodness);
  const meta = alignmentMeta(alignment);
  const events = record?.events ?? [];
  const chartLeft = Math.min(98, Math.max(2, (100 - lawfulness) / 2));
  const chartTop = Math.min(98, Math.max(2, (100 - goodness) / 2));

  const selectActor = (key: string) => {
    if (busy.current) return;
    setSelected(key);
    setReason('');
    setSaveError(null);
    setConfirmReset(false);
    setAnnouncement('');
  };

  const change = async (next: Change) => {
    if (busy.current || !data || error) return;
    const signature = JSON.stringify([selected, next, reason.trim()]);
    if (request.current?.signature !== signature)
      request.current = { signature, id: crypto.randomUUID() };
    const requestId = request.current.id;
    busy.current = true;
    setSaving(true);
    setSaveError(null);
    setConfirmReset(false);
    try {
      const variables = {
        characterId: selected === 'user' ? null : selected,
        reason: reason.trim() || null,
        requestId,
        ...(next.kind === 'set'
          ? { lawfulness: next.lawfulness, goodness: next.goodness }
          : {
              lawfulnessDelta: next.lawfulnessDelta,
              goodnessDelta: next.goodnessDelta,
            }),
      };
      let updated: CharacterAlignment | undefined;
      if (next.kind === 'set') {
        const result = await setAlignment({ variables });
        updated = result.data?.setCharacterAlignment;
      } else {
        const result = await adjustAlignment({ variables });
        updated = result.data?.adjustCharacterAlignment;
      }
      if (!updated) throw new Error('The alignment change could not be saved. Please retry.');
      request.current = null;
      if (!mounted.current) return;
      setSaved((previous) => [
        updated,
        ...previous.filter((item) => item.alignmentId !== updated.alignmentId),
      ]);
      onRecord?.(updated);
      setReason('');
      const updatedMeta = alignmentMeta(updated.alignment);
      setAnnouncement(
        `${updated.name}: ${updatedMeta.label}, order ${signedAlignment(
          updated.lawfulness,
        )}, morality ${signedAlignment(updated.goodness)}. Saved.`,
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

  const choose = (next: Alignment) => {
    const target = ALIGNMENTS[next];
    void change({ kind: 'set', lawfulness: target.anchorLaw, goodness: target.anchorGood });
  };

  const nudge = (axis: 'order' | 'morality', delta: number) => {
    void change(
      axis === 'order'
        ? { kind: 'adjust', lawfulnessDelta: delta, goodnessDelta: 0 }
        : { kind: 'adjust', lawfulnessDelta: 0, goodnessDelta: delta },
    );
  };

  return (
    <div>
      {loading && !data ? (
        <p role="status" className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
          <Loader2 size={18} className="animate-spin" />
          Loading alignments…
        </p>
      ) : error ? (
        <div role="alert" className="space-y-3">
          <p>Unable to load alignments.</p>
          <button className={axisButton} onClick={() => void refetch()}>
            Try again
          </button>
        </div>
      ) : (
        <div className="space-y-5">
          <section aria-label="Choose who to inspect">
            <div className="flex flex-wrap gap-2">
              {actors.map((actor) => {
                const key = actorAlignmentKey(actor.characterId);
                const actorRecord = records.find(
                  (item) => actorAlignmentKey(item.characterId) === key,
                );
                const actorMeta = alignmentMeta(actorRecord?.alignment);
                return (
                  <button
                    key={key}
                    type="button"
                    aria-pressed={selected === key}
                    disabled={saving}
                    onClick={() => selectActor(key)}
                    className={cn(
                      'inline-flex min-h-10 shrink-0 items-center gap-2 rounded-full border px-3 py-1.5 text-xs font-medium transition-colors',
                      selected === key
                        ? 'border-primary bg-primary/10 text-foreground'
                        : 'border-border bg-background hover:bg-accent',
                    )}
                  >
                    <span className={cn('h-2 w-2 rounded-full', actorMeta.dot)} aria-hidden />
                    {actor.name}
                  </button>
                );
              })}
            </div>
            {!data?.relationshipActors.length && (
              <p className="text-muted-foreground mt-2 text-xs">
                Create a character to chart their alignment. Your own alignment is always available.
              </p>
            )}
          </section>

          <section
            aria-label="Alignment chart"
            className="border-border bg-muted/20 rounded-xl border p-3 sm:p-4"
          >
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <div className="flex min-w-0 items-center gap-2">
                <Compass size={16} className="text-primary shrink-0" aria-hidden />
                <h3 className="truncate text-sm font-semibold">{selectedActor?.name}</h3>
              </div>
              <span
                className={cn(
                  'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold',
                  meta.cell,
                  meta.text,
                )}
              >
                <span className={cn('h-1.5 w-1.5 rounded-full', meta.dot)} aria-hidden />
                {meta.label}
              </span>
            </div>

            <div className="grid grid-cols-[auto_1fr] gap-1">
              <div aria-hidden />
              <div className="text-muted-foreground text-2xs grid grid-cols-3 px-1 text-center font-medium">
                <span>← Lawful</span>
                <span>Order</span>
                <span>Chaotic →</span>
              </div>
              <div className="text-muted-foreground text-2xs grid grid-rows-3 px-1 font-medium">
                <span className="flex items-center">Good ↑</span>
                <span className="flex items-center">Morality</span>
                <span className="flex items-center">Evil ↓</span>
              </div>
              <div className="relative grid min-h-[17rem] grid-cols-3 grid-rows-3 gap-1">
                {ALIGNMENT_ORDER.map((option) => {
                  const optionMeta = ALIGNMENTS[option];
                  const active = alignment === option;
                  return (
                    <button
                      key={option}
                      type="button"
                      aria-pressed={active}
                      aria-label={`Set alignment to ${optionMeta.label}: ${optionMeta.summary}`}
                      disabled={saving}
                      onClick={() => choose(option)}
                      className={cn(
                        'relative flex min-w-0 flex-col items-start justify-center gap-0.5 rounded-lg border p-1.5 text-left transition-all duration-150 disabled:opacity-60 sm:p-2.5',
                        optionMeta.cell,
                        active && optionMeta.selected,
                      )}
                    >
                      <span
                        className={cn(
                          'text-[10px] leading-tight font-semibold sm:text-xs',
                          optionMeta.text,
                        )}
                      >
                        {optionMeta.label}
                      </span>
                      <span className="text-muted-foreground text-2xs hidden leading-tight sm:block">
                        {optionMeta.summary}
                      </span>
                      <span
                        className={cn(
                          'text-2xs absolute top-1 right-1.5 hidden font-bold opacity-50 sm:block',
                          optionMeta.text,
                        )}
                      >
                        {optionMeta.short}
                      </span>
                    </button>
                  );
                })}
                <span
                  aria-hidden
                  className={cn(
                    'border-background ring-foreground/20 pointer-events-none absolute h-3.5 w-3.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 shadow-lg ring-2',
                    meta.dot,
                  )}
                  style={{ left: `${chartLeft}%`, top: `${chartTop}%` }}
                />
              </div>
            </div>
            <p className="text-muted-foreground text-2xs mt-3 leading-relaxed">
              Click a cell for its starting points, then nudge an axis. The dot marks the exact
              position. Neutral bands end at ±33; a named band starts at ±34.
            </p>
          </section>

          <section
            aria-label="Current alignment"
            className="border-border bg-background rounded-xl border p-4"
          >
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <Scale size={16} className={meta.text} aria-hidden />
                  <p className="text-sm font-semibold">{meta.label}</p>
                </div>
                <p className="text-muted-foreground mt-1 text-sm">{meta.description}</p>
                <p className="text-muted-foreground mt-1 text-xs italic">{meta.examples}</p>
              </div>
              <div className="grid shrink-0 grid-cols-2 gap-4 text-center">
                <div>
                  <p className="text-2xl font-semibold tabular-nums">
                    {signedAlignment(lawfulness)}
                  </p>
                  <p className="text-muted-foreground text-2xs tracking-wide uppercase">Order</p>
                </div>
                <div>
                  <p className="text-2xl font-semibold tabular-nums">{signedAlignment(goodness)}</p>
                  <p className="text-muted-foreground text-2xs tracking-wide uppercase">Morality</p>
                </div>
              </div>
            </div>
            <div className="mt-4 space-y-4">
              <AxisMeter
                label="Order"
                low="Chaotic"
                high="Lawful"
                value={lawfulness}
                saving={saving}
                lowColor="bg-violet-500"
                highColor="bg-sky-500"
                onNudge={(delta) => nudge('order', delta)}
              />
              <AxisMeter
                label="Morality"
                low="Evil"
                high="Good"
                value={goodness}
                saving={saving}
                lowColor="bg-rose-500"
                highColor="bg-success"
                onNudge={(delta) => nudge('morality', delta)}
              />
            </div>
          </section>

          <div>
            <Textarea
              label="Reason (optional)"
              value={reason}
              maxLength={500}
              showCount
              rows={2}
              disabled={saving}
              onChange={(event) => setReason(event.target.value)}
              placeholder="What shaped this alignment?"
              helperText="Record a choice, a betrayal, a mercy, or a moment that changed them. Feelings and alignment are separate."
            />
            <div className="mt-3 min-h-6 text-xs" aria-live="polite">
              {saving ? (
                <span className="text-muted-foreground flex items-center gap-2">
                  <Loader2 size={14} className="animate-spin" />
                  Saving alignment…
                </span>
              ) : (
                <span className="text-muted-foreground">
                  {announcement ||
                    'Changes save immediately. Alignment guides tone and choices; it does not award or remove relationship points.'}
                </span>
              )}
            </div>
            {saveError && (
              <p role="alert" className="text-destructive mt-2 text-sm">
                {saveError}
              </p>
            )}
          </div>

          <section aria-label="Alignment history" className="border-border border-t pt-4">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <h3 className="text-sm font-semibold">Recent changes</h3>
              <button
                type="button"
                disabled={saving || (lawfulness === 0 && goodness === 0)}
                onClick={() => setConfirmReset(true)}
                className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1.5 rounded-md p-1 text-xs disabled:opacity-40"
              >
                <RotateCcw size={12} />
                Reset to True Neutral
              </button>
            </div>
            {confirmReset && (
              <div className="border-border bg-muted mb-3 rounded-xl border p-3 text-sm">
                <p>Reset this alignment to 0 order and 0 morality? The history is preserved.</p>
                <div className="mt-3 flex gap-3">
                  <button
                    type="button"
                    className="bg-primary text-primary-foreground rounded-lg px-3 py-2"
                    disabled={saving}
                    onClick={() => void change({ kind: 'set', lawfulness: 0, goodness: 0 })}
                  >
                    Reset alignment
                  </button>
                  <button
                    type="button"
                    className="hover:bg-accent rounded-lg px-3 py-2"
                    onClick={() => setConfirmReset(false)}
                  >
                    Cancel
                  </button>
                </div>
              </div>
            )}
            {!events.length ? (
              <p className="text-muted-foreground text-sm">
                No changes yet. This alignment starts at True Neutral (0 / 0).
              </p>
            ) : (
              <ol className="max-h-56 space-y-3 overflow-y-auto pr-1">
                {events.map((event) => {
                  const eventMeta = alignmentMeta(event.alignment);
                  return (
                    <li key={event.eventId} className="flex items-start gap-3 text-xs">
                      <span
                        className={cn(
                          'min-w-9 rounded-md px-1.5 py-1 text-center font-semibold',
                          eventMeta.cell,
                          eventMeta.text,
                        )}
                      >
                        {eventMeta.short}
                      </span>
                      <div className="min-w-0 flex-1">
                        <p className="font-medium">
                          {event.action === 'SET' ? 'Set to ' : 'Shifted to '}
                          {eventMeta.label}{' '}
                          <span className="text-muted-foreground font-normal">
                            · order {signedAlignment(event.lawDelta)}, morality{' '}
                            {signedAlignment(event.goodDelta)}
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
                  );
                })}
              </ol>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
