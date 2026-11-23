import { describe, expect, it } from 'vitest';
import { normalizeTimestamp } from '@/lib/utils';

describe('normalizeTimestamp', () => {
  it('fills in a missing zero seconds field', () => {
    expect(normalizeTimestamp('2026-11-17T00:19')).toBe('2026-11-17T00:19:00.000Z');
  });

  it('fills in missing milliseconds', () => {
    expect(normalizeTimestamp('2026-11-17T00:19:05')).toBe('2026-11-17T00:19:05.000Z');
  });

  it('truncates nanoseconds', () => {
    expect(normalizeTimestamp('2026-11-17T00:19:05.123456789'))
      .toBe('2026-11-17T00:19:05.123Z');
  });

  it('keeps an explicit timezone', () => {
    expect(normalizeTimestamp('2026-11-17T00:19:05.123456789Z'))
      .toBe('2026-11-17T00:19:05.123Z');
    expect(normalizeTimestamp('2026-11-17T00:19:05+02:00'))
      .toBe('2026-11-17T00:19:05.000+02:00');
  });

  it('handles a date with no time', () => {
    expect(normalizeTimestamp('2026-11-17')).toBe('2026-11-17T00:00:00.000Z');
  });

  it('never returns an invalid date', () => {
    expect(new Date(normalizeTimestamp('not a date')).getTime()).not.toBeNaN();
    expect(normalizeTimestamp(null)).toBe('1970-01-01T00:00:00.000Z');
  });
});
