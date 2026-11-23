import { describe, expect, it } from 'vitest';
import { latestProgress, type ConversationProgress } from './conversationProgress';
const frame = (overrides: Partial<ConversationProgress> = {}): ConversationProgress => ({
  conversationId: 'conversation', runId: 'round', sequence: 10, stage: 'narrator',
  label: 'Writing the scene…', active: true, updatedAt: '2026-11-22T12:00:00.000Z', ...overrides,
});
describe('conversation progress ordering', () => {
  it('does not resurrect a completed phase from a delayed snapshot', () => {
    const complete = frame({ sequence: 12, active: false });
    expect(latestProgress(complete, frame())).toBe(complete);
    expect(latestProgress(frame(), complete)).toBe(complete);
  });
  it('prefers a newer run and rejects late events from the old run', () => {
    const old = frame();
    const current = frame({ runId: 'new', sequence: 11, updatedAt: '2026-11-22T12:01:00.000Z' });
    expect(latestProgress(old, current)).toBe(current);
    expect(latestProgress(current, old)).toBe(current);
  });
  it('handles reconnects, timestamp ties, and absent snapshots', () => {
    const old = frame();
    const current = frame({ runId: 'new', sequence: 11 });
    expect(latestProgress(old, current)).toBe(current);
    expect(latestProgress(null, current)).toBe(current);
    expect(latestProgress(current, null)).toBe(current);
  });
});
