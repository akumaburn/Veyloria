import { useEffect, useRef, useState } from 'react';
import { Loader2, Sparkles, Users, X } from 'lucide-react';
import { toast } from 'react-hot-toast';
import { Modal } from '@/components/ui/Modal';
import { Textarea } from '@/components/ui/Textarea';
import { useCharacterStore } from '@/stores/characterStore';
import { cn } from '@/lib/utils';
import type { CharacterDraft } from '@/graphql/types';
import { ALIGNMENTS, ALIGNMENT_ORDER, alignmentMeta, type Alignment } from './alignment';
import { AFFINITY_TIERS, signedPoints } from './relationships';

const tierStyle = (tier: string) =>
  tier === 'HATE'
    ? 'border-destructive/40 bg-destructive/10 text-destructive'
    : tier === 'DISLIKE'
      ? 'border-warning/40 bg-warning/10 text-warning-foreground'
      : tier === 'LOVE'
        ? 'border-primary/40 bg-primary/10 text-primary'
        : tier === 'LIKE'
          ? 'border-emerald-500/40 bg-success/10 text-emerald-700 dark:text-success'
          : 'border-border bg-background text-muted-foreground';

export const ROSTER_NAME_LIMIT = 40;

/**
 * A roster name must identify a cast in a list, so a long setting is replaced
 * by the first member and the rest of the count instead of a truncated
 * sentence that reads the same as every other setting.
 */
export function suggestedGroupName(context: string, memberNames: string[] = []) {
  const first = context.trim().split(/[.!?\n]/)[0]?.trim() ?? '';
  if (first && first.length <= ROSTER_NAME_LIMIT) return first;
  if (memberNames.length > 0) {
    const [lead, ...rest] = memberNames;
    if (rest.length === 0) return lead;
    return `${lead} and ${rest.length} other${rest.length === 1 ? '' : 's'}`;
  }
  return first.length > ROSTER_NAME_LIMIT
    ? `${first.slice(0, ROSTER_NAME_LIMIT - 1).trim()}…`
    : first;
}

function RosterSummary({ drafts }: { drafts: CharacterDraft[] }) {
  const counts = drafts.reduce<Record<string, number>>((all, draft) => {
    const meta = alignmentMeta(draft.alignment as Alignment);
    all[meta.label] = (all[meta.label] ?? 0) + 1;
    return all;
  }, {});
  const connections = drafts.reduce((total, draft) => total + draft.relationships.length, 0);
  const active = drafts.reduce(
    (total, draft) => total + draft.relationships.filter((item) => item.tier !== 'NEUTRAL').length,
    0,
  );
  return (
    <section
      aria-label="Roster summary"
      className="border-border bg-muted/20 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-lg border px-3 py-2"
    >
      <p className="text-sm font-medium">
        {drafts.length} characters · {connections} connections · {active} active feelings
      </p>
      <ul className="flex flex-wrap gap-1.5">
        {Object.entries(counts).map(([label, count]) => (
          <li
            key={label}
            className="border-border bg-background text-muted-foreground text-2xs rounded-md border px-2 py-0.5"
          >
            {label} × {count}
          </li>
        ))}
      </ul>
    </section>
  );
}

export function CharacterRosterModal({
  onClose,
  onSaved,
}: {
  onClose: () => void;
  onSaved: () => void;
}) {
  const generateRoster = useCharacterStore((state) => state.generateCharacterRoster);
  const saveRoster = useCharacterStore((state) => state.createCharacterRoster);
  const [context, setContext] = useState('');
  const [count, setCount] = useState(6);
  const [drafts, setDrafts] = useState<CharacterDraft[] | null>(null);
  const [groupName, setGroupName] = useState('');
  const [isGenerating, setIsGenerating] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const request = useRef<AbortController | null>(null);
  const saving = useRef(false);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      request.current?.abort();
    };
  }, []);

  const [newTargets, setNewTargets] = useState<Record<string, string>>({});
  const updateAlignment = (index: number, alignment: Alignment) => {
    const points = ALIGNMENTS[alignment];
    setDrafts((current) =>
      current
        ? current.map((draft, position) =>
            position === index
              ? {
                  ...draft,
                  alignment,
                  lawfulness: points.anchorLaw,
                  goodness: points.anchorGood,
                }
              : draft,
          )
        : current,
    );
  };
  const updateFeeling = (index: number, targetName: string, tier: string) => {
    setDrafts((current) =>
      current
        ? current.map((draft, position) =>
            position === index
              ? {
                  ...draft,
                  relationships: draft.relationships.map((item) =>
                    item.targetName === targetName ? { ...item, tier } : item,
                  ),
                }
              : draft,
          )
        : current,
    );
  };
  const removeFeeling = (index: number, targetName: string) => {
    setDrafts((current) =>
      current
        ? current.map((draft, position) =>
            position === index
              ? {
                  ...draft,
                  relationships: draft.relationships.filter(
                    (item) => item.targetName !== targetName,
                  ),
                }
              : draft,
          )
        : current,
    );
  };
  const addFeeling = (index: number, characterId: string) => {
    const target = newTargets[characterId];
    if (!target) return;
    setDrafts((current) =>
      current
        ? current.map((draft, position) =>
            position === index
              ? {
                  ...draft,
                  relationships: [
                    ...draft.relationships,
                    { targetName: target, tier: 'LIKE', reason: '' },
                  ],
                }
              : draft,
          )
        : current,
    );
    setNewTargets((current) => ({ ...current, [characterId]: '' }));
  };
  const close = () => {
    if (saving.current) return;
    request.current?.abort();
    onClose();
  };
  const generate = async (event: React.FormEvent) => {
    event.preventDefault();
    if (request.current || saving.current) return;
    if (context.trim().length < 10) {
      setError('Add at least 10 characters of setting or history.');
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    setIsGenerating(true);
    setError(null);
    try {
      const roster = await generateRoster(context.trim(), count, controller.signal);
      if (controller.signal.aborted || !mounted.current) return;
      if (roster.length !== count)
        throw new Error('The roster is incomplete. Please generate again.');
      setDrafts(roster);
      setGroupName((current) => current || suggestedGroupName(context, roster.map((draft) => draft.character.name)));
    } catch (error) {
      if (!controller.signal.aborted && mounted.current)
        setError(
          error instanceof Error
            ? error.message
            : 'Unable to generate the roster. Please try again.',
        );
    } finally {
      if (request.current === controller) request.current = null;
      if (mounted.current && !controller.signal.aborted) setIsGenerating(false);
    }
  };
  const save = async () => {
    if (!drafts || saving.current) return;
    saving.current = true;
    setIsSaving(true);
    setError(null);
    try {
      const saved = await saveRoster(drafts, groupName.trim() || undefined, context.trim());
      if (!mounted.current) return;
      toast.success(`${saved.length} characters added to your roster`);
      onSaved();
      onClose();
    } catch (error) {
      if (mounted.current)
        setError(
          error instanceof Error
            ? error.message
            : 'Unable to save the roster. Your drafts are still here.',
        );
    } finally {
      saving.current = false;
      if (mounted.current) setIsSaving(false);
    }
  };
  const secondary =
    'min-h-10 rounded-lg border border-border px-4 py-2 text-sm hover:bg-accent disabled:opacity-50';
  const primary =
    'inline-flex min-h-10 items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary-hover disabled:opacity-50';

  return (
    <Modal
      isOpen
      onClose={close}
      size="xl"
      title={drafts ? 'Review your roster' : 'Generate a character roster'}
      description={
        drafts
          ? `${drafts.length} interconnected characters, grounded in one shared setting.`
          : 'Describe a location, world, or historical moment. Generate a cast with shared history and meaningful connections.'
      }
      closeOnEscape={!isSaving}
      closeOnOverlayClick={!isSaving}
      showCloseButton={!isSaving}
      footer={
        <div
          className={
            drafts
              ? 'grid grid-cols-2 gap-2 sm:flex sm:items-center sm:justify-between sm:gap-3'
              : 'flex flex-wrap items-center justify-between gap-3'
          }
        >
          <button type="button" onClick={close} disabled={isSaving} className={secondary}>
            {isGenerating ? 'Cancel generation' : 'Cancel'}
          </button>
          {drafts ? (
            <div className="contents sm:flex sm:flex-wrap sm:gap-2">
              <button
                type="button"
                onClick={() => {
                  setDrafts(null);
                  setGroupName('');
                  setError(null);
                }}
                disabled={isSaving}
                className={secondary}
              >
                Edit setting
              </button>
              <button
                type="button"
                onClick={() => void save()}
                disabled={isSaving}
                className={`${primary} col-span-2`}
              >
                {isSaving ? <Loader2 size={16} className="animate-spin" /> : <Users size={16} />}
                {isSaving ? 'Saving roster…' : `Save all ${drafts.length} characters`}
              </button>
            </div>
          ) : (
            <button
              type="submit"
              form="character-roster-form"
              disabled={isGenerating || context.trim().length < 10}
              className={primary}
            >
              {isGenerating ? (
                <Loader2 size={16} className="animate-spin" />
              ) : (
                <Sparkles size={16} />
              )}
              {isGenerating ? `Generating ${count} characters…` : 'Generate roster'}
            </button>
          )}
        </div>
      }
    >
      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/10 text-destructive mb-4 rounded-lg border p-3 text-sm"
        >
          {error}
        </div>
      )}
      {drafts ? (
        <div className="space-y-4">
          <p className="text-muted-foreground text-xs">
            Saving creates new, private characters together. Review the cast, adjust alignments and
            feelings, then save. You can edit each character again from this page later.
          </p>
          <label className="block text-sm">
            <span className="mb-1 block font-medium">Roster name</span>
            <input
              type="text"
              value={groupName}
              maxLength={255}
              onChange={(event) => setGroupName(event.target.value)}
              placeholder="Name this roster"
              className="border-border bg-background text-foreground min-h-10 w-full rounded-lg border px-3 text-sm focus:border-primary focus:ring-primary/20 focus:ring-2 focus:outline-none"
            />
            <span className="text-muted-foreground mt-1 block text-xs">
              The Rosters view uses this name. You can rename it later.
            </span>
          </label>
          <details className="border-border bg-muted/30 rounded-lg border p-3">
            <summary className="cursor-pointer text-sm font-medium">
              Shared setting &amp; history
            </summary>
            <p className="text-muted-foreground mt-2 text-sm break-words whitespace-pre-wrap">
              {context}
            </p>
          </details>
          <RosterSummary drafts={drafts} />
          <ol className="grid gap-3 xl:grid-cols-2">
            {drafts.map((draft, index) => {
              const character = draft.character;
              const meta = alignmentMeta(draft.alignment as Alignment);
              const cast = drafts
                .map((entry) => entry.character.name)
                .filter((name) => name !== character.name);
              const connected = new Set(draft.relationships.map((item) => item.targetName));
              const available = cast.filter((name) => !connected.has(name));
              return (
                <li
                  key={character.id}
                  className="border-border bg-background min-w-0 rounded-xl border p-3 sm:p-4"
                >
                  <div className="flex items-start gap-2">
                    <span className="bg-accent text-muted-foreground flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-xs">
                      {index + 1}
                    </span>
                    <div className="min-w-0 flex-1">
                      <h3 className="text-sm font-semibold break-words">{character.name}</h3>
                      <p className="text-muted-foreground mt-1 line-clamp-3 text-sm break-words">
                        {character.description}
                      </p>
                      <dl className="text-muted-foreground mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
                        {[
                          ['Gender', character.gender],
                          ['Age', character.ageYears],
                          ['Height', character.height],
                          ['Build', character.build],
                          ['Hair', character.hair],
                          ['Eyes', character.eyes],
                        ]
                          .filter(([, value]) => value !== null && value !== undefined && value !== '')
                          .map(([label, value]) => (
                            <div key={label} className="flex min-w-0 gap-1">
                              <dt className="shrink-0 font-medium">{label}</dt>
                              <dd className="truncate">{value}</dd>
                            </div>
                          ))}
                      </dl>
                      <p className="text-muted-foreground mt-1 text-xs tabular-nums">
                        {[
                          ['STR', character.strength],
                          ['DEX', character.dexterity],
                          ['CON', character.constitution],
                          ['INT', character.intelligence],
                          ['WIS', character.wisdom],
                          ['CHA', character.charisma],
                        ]
                          .filter(([, value]) => typeof value === 'number')
                          .map(([label, value]) => `${label} ${value}`)
                          .join(' · ')}
                      </p>
                    </div>
                  </div>

                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <label className="flex items-center gap-1.5 text-xs">
                      <span className="text-muted-foreground">Alignment</span>
                      <select
                        aria-label={`Alignment for ${character.name}`}
                        value={draft.alignment}
                        onChange={(event) =>
                          updateAlignment(index, event.target.value as Alignment)
                        }
                        className={cn(
                          'min-h-10 rounded-md border px-2 text-xs font-medium focus:ring-1 focus:outline-none sm:min-h-8',
                          meta.cell,
                          meta.text,
                        )}
                      >
                        {ALIGNMENT_ORDER.map((option) => (
                          <option key={option} value={option}>
                            {ALIGNMENTS[option].label}
                          </option>
                        ))}
                      </select>
                    </label>
                  </div>

                  <section
                    aria-label={`Feelings of ${character.name}`}
                    className="border-border mt-3 rounded-lg border p-2.5"
                  >
                    <div className="mb-2 flex items-center justify-between gap-2">
                      <h4 className="text-xs font-semibold">Feels toward</h4>
                      <span className="text-muted-foreground text-2xs">
                        {draft.relationships.filter((item) => item.tier !== 'NEUTRAL').length}{' '}
                        active
                      </span>
                    </div>
                    {draft.relationships.length === 0 ? (
                      <p className="text-muted-foreground text-xs">
                        No feelings recorded. Add a connection below.
                      </p>
                    ) : (
                      <ul className="space-y-1.5">
                        {draft.relationships.map((relationship) => (
                          <li
                            key={relationship.targetName}
                            className="flex flex-wrap items-center gap-1.5"
                          >
                            <span className="min-w-0 flex-1 text-xs font-medium break-words">
                              {relationship.targetName}
                            </span>
                            <select
                              aria-label={`Feeling of ${character.name} toward ${relationship.targetName}`}
                              value={relationship.tier}
                              onChange={(event) =>
                                updateFeeling(index, relationship.targetName, event.target.value)
                              }
                              className={cn(
                                'text-2xs min-h-10 rounded-md border px-1.5 font-medium focus:ring-1 focus:outline-none sm:min-h-8',
                                tierStyle(relationship.tier),
                              )}
                            >
                              {AFFINITY_TIERS.map((tier) => (
                                <option key={tier.tier} value={tier.tier}>
                                  {tier.label} ({signedPoints(tier.points)})
                                </option>
                              ))}
                            </select>
                            <button
                              type="button"
                              onClick={() => removeFeeling(index, relationship.targetName)}
                              aria-label={`Remove ${character.name}'s feeling toward ${relationship.targetName}`}
                              title="Remove feeling"
                              className="text-muted-foreground hover:text-destructive hover:bg-destructive/10 flex h-10 w-10 shrink-0 items-center justify-center rounded-md sm:h-auto sm:w-auto sm:p-1"
                            >
                              <X size={13} />
                            </button>
                            {relationship.reason && (
                              <p className="text-muted-foreground w-full text-xs leading-relaxed break-words">
                                {relationship.reason}
                              </p>
                            )}
                          </li>
                        ))}
                      </ul>
                    )}
                    {available.length > 0 && (
                      <div className="border-border mt-2 flex flex-wrap items-center gap-1.5 border-t pt-2">
                        <select
                          aria-label={`Add connection for ${character.name}`}
                          value={newTargets[character.id] ?? ''}
                          onChange={(event) =>
                            setNewTargets((current) => ({
                              ...current,
                              [character.id]: event.target.value,
                            }))
                          }
                          className="border-border bg-background text-foreground min-h-10 min-w-0 flex-1 rounded-md border px-2 text-xs focus:ring-1 focus:outline-none sm:min-h-8"
                        >
                          <option value="">Choose a cast member…</option>
                          {available.map((name) => (
                            <option key={name} value={name}>
                              {name}
                            </option>
                          ))}
                        </select>
                        <button
                          type="button"
                          disabled={!newTargets[character.id]}
                          onClick={() => addFeeling(index, character.id)}
                          className="border-border bg-background hover:bg-accent min-h-10 rounded-md border px-3 text-xs font-medium disabled:opacity-40 sm:min-h-8 sm:px-2"
                        >
                          Add
                        </button>
                      </div>
                    )}
                  </section>

                  <details className="mt-3">
                    <summary className="text-primary cursor-pointer text-xs font-medium">
                      Background, connections &amp; instructions
                    </summary>
                    <dl className="mt-3 space-y-3 text-xs">
                      {(
                        [
                          ['Personality', character.personality],
                          ['Appearance', character.appearance],
                          ['History & connections', character.backstory],
                          ['Goals', character.goals],
                          ['Likes', character.likes],
                          ['Dislikes', character.dislikes],
                          ['Greeting', character.greeting],
                          ['Character instructions', character.systemPrompt],
                        ] as const
                      )
                        .filter(([, value]) => value)
                        .map(([label, value]) => (
                          <div key={label}>
                            <dt className="mb-1 font-semibold">{label}</dt>
                            <dd className="text-muted-foreground leading-relaxed break-words whitespace-pre-wrap">
                              {value}
                            </dd>
                          </div>
                        ))}
                    </dl>
                  </details>
                </li>
              );
            })}
          </ol>
        </div>
      ) : (
        <form
          id="character-roster-form"
          onSubmit={(event) => void generate(event)}
          className="space-y-6"
          aria-busy={isGenerating}
        >
          <Textarea
            label="Location, world & history"
            name="roster-context"
            value={context}
            onChange={(event) => setContext(event.target.value)}
            disabled={isGenerating}
            rows={7}
            maxLength={6000}
            required
            placeholder="A coastal city twenty years after its trading empire collapsed. Rival guilds control the docks, an old naval fort shelters displaced families, and a missing ship threatens a fragile peace…"
            showCount
            helperText="Use 10–6,000 characters. Include the era, culture, key events, factions, tensions, and facts the cast should share."
          />
          <div>
            <div className="mb-3 flex items-center justify-between">
              <label htmlFor="roster-count" className="text-sm font-medium">
                Number of characters
              </label>
              <output
                htmlFor="roster-count"
                className="bg-accent rounded-md px-3 py-1 text-sm font-semibold"
              >
                {count}
              </output>
            </div>
            <input
              id="roster-count"
              type="range"
              min={2}
              max={12}
              step={1}
              value={count}
              onChange={(event) => setCount(Number(event.target.value))}
              disabled={isGenerating}
              className="accent-primary h-8 w-full"
              aria-valuetext={`${count} characters`}
              aria-describedby="roster-count-help"
            />
            <div aria-hidden="true" className="text-muted-foreground flex justify-between text-xs">
              <span>2 characters</span>
              <span>12 characters</span>
            </div>
            <p
              id="roster-count-help"
              className="text-muted-foreground mt-3 text-xs leading-relaxed"
            >
              The cast is generated together, with distinct roles and connections to one another.
              Larger rosters can take several minutes.
            </p>
          </div>
          {isGenerating && (
            <p role="status" className="text-muted-foreground flex items-center gap-2 text-sm">
              <Loader2 size={16} className="shrink-0 animate-spin" />
              Building a shared history, relationships, and {count} distinct personalities…
            </p>
          )}
        </form>
      )}
    </Modal>
  );
}
