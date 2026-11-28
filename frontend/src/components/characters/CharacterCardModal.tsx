import { useQuery } from '@apollo/client';
import { Bot, Loader2, Tag } from 'lucide-react';
import { Modal } from '@/components/ui/Modal';
import { CHARACTER_QUERY } from '@/graphql/operations';
import { CHARACTER_STATS_QUERY } from '@/graphql/roleplay-operations';
import type { Character, CharacterStat } from '@/graphql/types';
import { useCharacterCardStore } from '@/stores/characterCardStore';
import { CharacterPortrait } from './CharacterPortrait';
import { cn } from '@/lib/utils';

/** One labeled paragraph. Empty fields are dropped by the caller. */
function Detail({ label, value }: { label: string; value?: string | null }) {
  if (!value || !value.trim()) return null;
  return (
    <section>
      <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        {label}
      </h3>
      <p className="mt-1 text-sm leading-relaxed break-words whitespace-pre-wrap text-foreground">
        {value}
      </p>
    </section>
  );
}

/** One physical fact: gender, height, hair, and so on. */
function Fact({ label, value }: { label: string; value?: string | number | null }) {
  if (value === null || value === undefined || String(value).trim() === '') return null;
  return (
    <div>
      <dt className="text-2xs font-semibold tracking-wide text-muted-foreground uppercase">
        {label}
      </dt>
      <dd className="mt-0.5 text-sm break-words text-foreground">{value}</dd>
    </div>
  );
}

const ATTRIBUTE_GROUPS = [
  {
    name: 'Physical',
    attributes: [
      { key: 'strength', label: 'Strength' },
      { key: 'dexterity', label: 'Dexterity' },
      { key: 'constitution', label: 'Constitution' },
    ],
  },
  {
    name: 'Mental',
    attributes: [
      { key: 'intelligence', label: 'Intelligence' },
      { key: 'wisdom', label: 'Wisdom' },
    ],
  },
  {
    name: 'Social',
    attributes: [{ key: 'charisma', label: 'Charisma' }],
  },
] as const;

/** A base attribute on its 1-20 scale. */
function AttributeBar({ label, value }: { label: string; value: number }) {
  const percent = Math.max(0, Math.min(100, (value / 20) * 100));
  return (
    <div>
      <div className="flex items-baseline justify-between gap-2 text-xs">
        <span className="text-muted-foreground">{label}</span>
        <span className="font-medium tabular-nums text-foreground">{value}</span>
      </div>
      <div
        role="meter"
        aria-label={`${label}: ${value} of 20`}
        aria-valuenow={value}
        aria-valuemin={1}
        aria-valuemax={20}
        className="bg-muted mt-1 h-1.5 overflow-hidden rounded-full"
      >
        <div className="bg-primary h-full rounded-full" style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

function AttributeBreakdown({ character }: { character: Character }) {
  const groups = ATTRIBUTE_GROUPS.map((group) => ({
    ...group,
    attributes: group.attributes
      .map((attribute) => ({
        ...attribute,
        value: character[attribute.key] as number | undefined,
      }))
      .filter((attribute): attribute is typeof attribute & { value: number } =>
        typeof attribute.value === 'number',
      ),
  })).filter((group) => group.attributes.length > 0);

  if (groups.length === 0) return null;

  return (
    <section aria-label="Base attributes">
      <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        Base attributes
      </h3>
      <div className="mt-2 grid gap-4 sm:grid-cols-3">
        {groups.map((group) => (
          <div key={group.name}>
            <h4 className="text-2xs font-semibold tracking-wide text-muted-foreground uppercase">
              {group.name}
            </h4>
            <div className="mt-2 space-y-2">
              {group.attributes.map((attribute) => (
                <AttributeBar
                  key={attribute.key}
                  label={attribute.label}
                  value={attribute.value}
                />
              ))}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

/** Stats recorded for this character in the current story. */
function CurrentStats({ stats }: { stats: CharacterStat[] }) {
  const visible = stats.filter((stat) => stat.isVisible !== false);
  if (visible.length === 0) return null;

  const groups = new Map<string, CharacterStat[]>();
  for (const stat of visible) {
    const category = stat.statCategory?.trim() || 'General';
    const list = groups.get(category) ?? [];
    list.push(stat);
    groups.set(category, list);
  }

  return (
    <section aria-label="Current stats">
      <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        Current stats
      </h3>
      <p className="mt-1 text-xs text-muted-foreground">
        Recorded for this character in this story. The base attributes above describe the body;
        these are what play has added or changed.
      </p>
      <div className="mt-3 space-y-3">
        {[...groups.entries()].map(([category, entries]) => (
          <div key={category}>
            <h4 className="text-2xs font-semibold tracking-wide text-muted-foreground uppercase">
              {category}
            </h4>
            <dl className="mt-1.5 space-y-2">
              {entries.map((stat) => {
                const max = stat.statMax ?? undefined;
                const isText = stat.statType === 'text' || (stat.textValue && !stat.statValue);
                const percent =
                  max && max > 0
                    ? Math.max(0, Math.min(100, (stat.statValue / max) * 100))
                    : Math.max(0, Math.min(100, stat.statValue));
                return (
                  <div key={stat.statId} className="text-sm">
                    <div className="flex items-baseline justify-between gap-2">
                      <dt className="min-w-0 break-words text-foreground">{stat.statName}</dt>
                      <dd className="shrink-0 font-medium tabular-nums text-foreground">
                        {isText
                          ? stat.textValue
                          : max
                            ? `${stat.statValue} / ${max}`
                            : stat.statValue}
                      </dd>
                    </div>
                    {!isText && max !== undefined && (
                      <div
                        role="meter"
                        aria-label={`${stat.statName}: ${stat.statValue} of ${max}`}
                        aria-valuenow={stat.statValue}
                        aria-valuemin={0}
                        aria-valuemax={max}
                        className="bg-muted mt-1 h-1.5 overflow-hidden rounded-full"
                      >
                        <div
                          className="bg-primary/60 h-full rounded-full"
                          style={{ width: `${percent}%` }}
                        />
                      </div>
                    )}
                  </div>
                );
              })}
            </dl>
          </div>
        ))}
      </div>
    </section>
  );
}

function CharacterCardBody({
  character,
  stats,
  conversationId,
  onPortraitComplete,
}: {
  character: Character;
  stats: CharacterStat[];
  conversationId?: string;
  onPortraitComplete?: () => void;
}) {
  const tags = character.tags ?? [];
  const facts = [
    { label: 'Gender', value: character.gender },
    { label: 'Pronouns', value: character.pronouns },
    { label: 'Age', value: character.ageYears },
    { label: 'Height', value: character.height },
    { label: 'Build', value: character.build },
    { label: 'Hair', value: character.hair },
    { label: 'Eyes', value: character.eyes },
  ].filter((fact) => fact.value !== null && fact.value !== undefined && String(fact.value).trim() !== '');

  return (
    <div className="space-y-5">
      <CharacterPortrait
        character={character}
        conversationId={conversationId}
        onPortraitComplete={onPortraitComplete}
      />
      <div className="flex items-center gap-3 sm:gap-4">
        {character.avatar ? (
          <img
            src={character.avatar}
            alt={character.name}
            className="h-16 w-16 shrink-0 rounded-2xl object-cover shadow-lg sm:h-20 sm:w-20"
          />
        ) : (
          <div
            className={cn(
              'flex h-16 w-16 shrink-0 items-center justify-center rounded-2xl sm:h-20 sm:w-20',
              'bg-gradient-to-br from-secondary to-secondary-hover shadow-lg',
            )}
            aria-hidden="true"
          >
            <Bot size={28} className="text-secondary-foreground sm:size-8" />
          </div>
        )}
        <div className="min-w-0">
          {character.category && (
            <p className="text-xs text-muted-foreground">{character.category}</p>
          )}
          {tags.length > 0 && (
            <ul className="mt-2 flex flex-wrap gap-1.5" aria-label="Tags">
              {tags.map((tag) => (
                <li
                  key={tag}
                  className="inline-flex items-center gap-1 rounded-md bg-accent px-2 py-0.5 text-2xs text-muted-foreground"
                >
                  <Tag size={10} aria-hidden="true" />
                  {tag}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      {character.description && (
        <p className="text-sm leading-relaxed break-words whitespace-pre-wrap text-muted-foreground">
          {character.description}
        </p>
      )}

      {facts.length > 0 && (
        <dl
          aria-label="Physical profile"
          className="grid grid-cols-2 gap-x-4 gap-y-3 sm:grid-cols-3"
        >
          {facts.map((fact) => (
            <Fact key={fact.label} label={fact.label} value={fact.value} />
          ))}
        </dl>
      )}

      <AttributeBreakdown character={character} />
      <CurrentStats stats={stats} />

      <div className="grid gap-4 sm:grid-cols-2">
        <Detail label="Personality" value={character.personality} />
        <Detail label="Appearance" value={character.appearance} />
        <Detail label="Likes" value={character.likes} />
        <Detail label="Dislikes" value={character.dislikes} />
      </div>

      <Detail label="Background" value={character.backstory} />
      <Detail label="Goals" value={character.goals} />
    </div>
  );
}

/**
 * The one character card in the app.
 *
 * Mounted once by the layout and driven by the character card store, so any
 * avatar can open it. The full profile comes from the character query rather
 * than from the summary attached to a chat message. When the card was opened
 * from a story, it also shows that story's recorded stats.
 */
export function CharacterCardModal() {
  const characterId = useCharacterCardStore((state) => state.characterId);
  const conversationId = useCharacterCardStore((state) => state.conversationId);
  const closeCharacterCard = useCharacterCardStore((state) => state.closeCharacterCard);

  const { data, loading, error, refetch } = useQuery<{ character: Character | null }>(CHARACTER_QUERY, {
    variables: { id: characterId ?? '' },
    skip: !characterId,
    fetchPolicy: 'cache-and-network',
    nextFetchPolicy: 'cache-first',
  });

  const { data: statsData } = useQuery<{ characterStats: CharacterStat[] }>(
    CHARACTER_STATS_QUERY,
    {
      variables: { conversationId: conversationId ?? '', characterId: characterId ?? '' },
      skip: !conversationId || !characterId,
      fetchPolicy: 'cache-and-network',
    },
  );

  if (!characterId) return null;

  const character = data?.character ?? null;
  const stats = statsData?.characterStats ?? [];

  return (
    <Modal
      isOpen
      onClose={closeCharacterCard}
      title={character?.name || 'Character card'}
      size="lg"
      description={character ? 'Character card' : undefined}
    >
      {loading && !character ? (
        <div className="flex items-center justify-center gap-2 py-10 text-sm text-muted-foreground">
          <Loader2 size={16} className="animate-spin" aria-hidden="true" />
          Loading character…
        </div>
      ) : error && !character ? (
        <p role="alert" className="py-8 text-sm text-muted-foreground">
          This character could not be loaded.
        </p>
      ) : character ? (
        <CharacterCardBody
          character={character}
          stats={stats}
          conversationId={conversationId ?? undefined}
          onPortraitComplete={() => void refetch()}
        />
      ) : (
        <p className="py-8 text-sm text-muted-foreground">
          This character is no longer available.
        </p>
      )}
    </Modal>
  );
}
