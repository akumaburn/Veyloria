import { describe, expect, it } from 'vitest';
import {
  ALIGNMENT_ORDER,
  ALIGNMENTS,
  alignmentMeta,
  alignmentOf,
  signedAlignment,
} from './alignment';

describe('alignmentOf', () => {
  it('matches the nine bands and their boundaries', () => {
    expect(alignmentOf(34, 34)).toBe('LAWFUL_GOOD');
    expect(alignmentOf(0, 34)).toBe('NEUTRAL_GOOD');
    expect(alignmentOf(-34, 34)).toBe('CHAOTIC_GOOD');
    expect(alignmentOf(34, 0)).toBe('LAWFUL_NEUTRAL');
    expect(alignmentOf(0, 0)).toBe('TRUE_NEUTRAL');
    expect(alignmentOf(-34, 0)).toBe('CHAOTIC_NEUTRAL');
    expect(alignmentOf(34, -34)).toBe('LAWFUL_EVIL');
    expect(alignmentOf(0, -34)).toBe('NEUTRAL_EVIL');
    expect(alignmentOf(-34, -34)).toBe('CHAOTIC_EVIL');
    expect(alignmentOf(33, 33)).toBe('TRUE_NEUTRAL');
    expect(alignmentOf(100, -100)).toBe('LAWFUL_EVIL');
  });

  it('clamps values outside the point range', () => {
    expect(alignmentOf(400, -400)).toBe('LAWFUL_EVIL');
  });
});

describe('alignment metadata', () => {
  it('has nine chart cells with anchors that resolve to the same alignment', () => {
    expect(ALIGNMENT_ORDER).toHaveLength(9);
    for (const name of ALIGNMENT_ORDER) {
      const meta = ALIGNMENTS[name];
      expect(meta.label).not.toBe('');
      expect(meta.summary).not.toBe('');
      expect(meta.description).not.toBe('');
      expect(alignmentOf(meta.anchorLaw, meta.anchorGood)).toBe(name);
    }
  });

  it('falls back to true neutral for missing data', () => {
    expect(alignmentMeta(undefined).label).toBe('True Neutral');
    expect(alignmentMeta(null).label).toBe('True Neutral');
    expect(alignmentMeta('TRUE_NEUTRAL').label).toBe('True Neutral');
  });

  it('formats signed points consistently', () => {
    expect(signedAlignment(70)).toBe('+70');
    expect(signedAlignment(0)).toBe('0');
    expect(signedAlignment(-34)).toBe('-34');
  });
});
