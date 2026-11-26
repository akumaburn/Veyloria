import { describe, expect, it } from 'vitest';
import { SPEAKER_STYLE_COUNT, assignSpeakerSlots, fallbackSpeakerSlot, speakerStyle } from './speakerColors';

const look = (slot: number) => {
  const style = speakerStyle(slot);
  return `${style.bg} ${style.pattern}`;
};
// Pigments that read as the same color when tinted into a bubble fill.
const family = (bg: string) => ({ 1: 'blue', 7: 'warm', 4: 'warm', 3: 'green', 6: 'green', 5: 'magenta', 2: 'violet', 8: 'violet' })[
  Number(bg.match(/participant-(\d)/)?.[1])];

describe('speaker colors', () => {
  it('gives every slot its own look until the styles run out', () => {
    const looks = Array.from({ length: SPEAKER_STYLE_COUNT }, (_, slot) => look(slot));
    expect(new Set(looks).size).toBe(SPEAKER_STYLE_COUNT);
    expect(look(SPEAKER_STYLE_COUNT)).toBe(look(0));
  });

  it('uses five clearly different hues, unpatterned, before any pattern', () => {
    const first = Array.from({ length: 5 }, (_, slot) => speakerStyle(slot));
    expect(new Set(first.map((style) => family(style.bg))).size).toBe(5);
    first.forEach((style) => expect(style.pattern).not.toMatch(/stripes|edge/));
  });

  it('never gives two slots of the same hue family the same pattern', () => {
    const seen = new Set<string>();
    for (let slot = 0; slot < SPEAKER_STYLE_COUNT; slot++) {
      const style = speakerStyle(slot);
      const key = `${family(style.bg)}|${style.pattern.replace(/speaker-tone-\d ?/, '')}`;
      expect(seen.has(key)).toBe(false);
      seen.add(key);
    }
  });

  it('keeps server slots for characters and counts other players down from the end', () => {
    const slots = assignSpeakerSlots(
      [{ characterId: 'elara', slot: 0 }, { characterId: 'sylvia', slot: 1 }, { characterId: 'arthur', slot: 2 }],
      ['ana', 'ben'],
    );
    expect([...slots.values()]).toEqual([0, 1, 2, SPEAKER_STYLE_COUNT - 1, SPEAKER_STYLE_COUNT - 2]);
    expect(new Set(['elara', 'sylvia', 'arthur'].map((id) => look(slots.get(id)!))).size).toBe(3);
  });

  it('gives an unassigned speaker a stable slot', () => {
    expect(fallbackSpeakerSlot('abc')).toBe(fallbackSpeakerSlot('abc'));
    expect(fallbackSpeakerSlot('abc')).toBeLessThan(SPEAKER_STYLE_COUNT);
  });
});
