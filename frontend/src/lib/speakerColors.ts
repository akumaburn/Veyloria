/**
 * Speaker colors for group scenes. Each speaker gets a slot, and every slot maps
 * to a distinct pigment and pattern, so no two speakers in a conversation look
 * alike until the set of styles runs out.
 */

/**
 * The eight participant pigments, grouped by how they read as pale bubble
 * fills. Pigments in the same group (pine and teal, burnt and umber, violet and
 * purple) are near twins, so a pattern always tells them apart.
 */
const PIGMENT_FAMILIES: readonly (readonly number[])[] = [[1], [7, 4], [3, 6], [5], [2, 8]];

/** Fill patterns, applied in order once every family has been used. */
const PATTERNS = ['', 'speaker-stripes', 'speaker-edge', 'speaker-stripes speaker-edge'] as const;

export const SPEAKER_STYLE_COUNT = PIGMENT_FAMILIES.length * PATTERNS.length;

export interface SpeakerStyle {
  /** Bubble and avatar tile fill. */
  bg: string;
  border: string;
  /** Name and icon color. */
  accent: string;
  /** Sets the pigment for the pattern and adds the slot's pattern, if any. */
  pattern: string;
}

// Literal class names, so Tailwind finds every one of them.
const PIGMENTS: Record<number, Omit<SpeakerStyle, 'pattern'> & { tone: string }> = {
  1: { bg: 'bg-participant-1-bg', border: 'border-participant-1/30', accent: 'text-participant-1', tone: 'speaker-tone-1' },
  2: { bg: 'bg-participant-2-bg', border: 'border-participant-2/30', accent: 'text-participant-2', tone: 'speaker-tone-2' },
  3: { bg: 'bg-participant-3-bg', border: 'border-participant-3/30', accent: 'text-participant-3', tone: 'speaker-tone-3' },
  4: { bg: 'bg-participant-4-bg', border: 'border-participant-4/30', accent: 'text-participant-4', tone: 'speaker-tone-4' },
  5: { bg: 'bg-participant-5-bg', border: 'border-participant-5/30', accent: 'text-participant-5', tone: 'speaker-tone-5' },
  6: { bg: 'bg-participant-6-bg', border: 'border-participant-6/30', accent: 'text-participant-6', tone: 'speaker-tone-6' },
  7: { bg: 'bg-participant-7-bg', border: 'border-participant-7/30', accent: 'text-participant-7', tone: 'speaker-tone-7' },
  8: { bg: 'bg-participant-8-bg', border: 'border-participant-8/30', accent: 'text-participant-8', tone: 'speaker-tone-8' },
};

/**
 * The style for a speaker slot. Slots cycle through the five families first,
 * then repeat them with stripes, a shaded edge, and both, taking the twin
 * pigment on the patterned rounds so neighbouring hues also differ.
 */
export function speakerStyle(slot: number): SpeakerStyle {
  const index = ((Math.trunc(slot) % SPEAKER_STYLE_COUNT) + SPEAKER_STYLE_COUNT) % SPEAKER_STYLE_COUNT;
  const family = PIGMENT_FAMILIES[index % PIGMENT_FAMILIES.length];
  const round = Math.floor(index / PIGMENT_FAMILIES.length);
  const { tone, ...pigment } = PIGMENTS[family[round % family.length]];
  return { ...pigment, pattern: [tone, PATTERNS[round]].filter(Boolean).join(' ') };
}

/** A stable slot for a speaker the conversation has not assigned one to. */
export function fallbackSpeakerSlot(id: string): number {
  let sum = 0;
  for (let i = 0; i < id.length; i++) sum += id.charCodeAt(i) * (i + 1);
  return sum % SPEAKER_STYLE_COUNT;
}

/**
 * Slots for everyone who speaks in a conversation. Characters take the slots
 * the server assigns in order of introduction; other players count down from
 * the last style, so the two groups only meet once all styles are in use.
 */
export function assignSpeakerSlots(
  characterSlots: readonly { characterId: string; slot: number }[],
  userIds: readonly string[],
): ReadonlyMap<string, number> {
  const slots = new Map<string, number>();
  characterSlots.forEach(({ characterId, slot }) => slots.set(characterId, slot));
  userIds.forEach((id, index) => {
    if (!slots.has(id)) slots.set(id, SPEAKER_STYLE_COUNT - 1 - index);
  });
  return slots;
}
