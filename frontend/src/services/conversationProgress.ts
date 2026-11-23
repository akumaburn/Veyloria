export interface ConversationProgress {
  conversationId: string;
  runId: string;
  sequence: number;
  stage: string;
  label: string;
  active: boolean;
  updatedAt: string;
}

/** Preserve terminal frames and reject delayed frames from superseded work. */
export function latestProgress(previous: ConversationProgress | null | undefined, next: ConversationProgress | null | undefined): ConversationProgress | null {
  if (!next) return previous ?? null;
  if (!previous || previous.conversationId !== next.conversationId) return next;
  if (previous.runId === next.runId) return next.sequence > previous.sequence || (next.sequence === previous.sequence && !next.active) ? next : previous;
  const newer = Date.parse(next.updatedAt) - Date.parse(previous.updatedAt);
  return newer > 0 || (newer === 0 && next.sequence > previous.sequence) ? next : previous;
}
