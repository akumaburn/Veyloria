import { cleanTextForTTS } from './ttsService';
import type { VoiceProfile } from './voiceProfile';

export interface ConversationSpeechMessage {
  id: string;
  content: string;
  role: string;
  messageType?: string;
  status?: string;
  character?: VoiceProfile;
}

export function isReadableMessage(message: ConversationSpeechMessage): boolean {
  const role = message.role.toLowerCase();
  return role !== 'user' &&
    (role === 'assistant' || message.messageType === 'NARRATION') &&
    !['generating', 'error', 'sending'].includes(message.status || '') &&
    !['OOC', 'SYSTEM', 'DICE'].includes(message.messageType || '') &&
    cleanTextForTTS(message.content).trim().length > 0;
}

export function messageVoiceProfile(message: Pick<ConversationSpeechMessage, 'messageType' | 'character'>): VoiceProfile | undefined {
  return !message.messageType || ['DIALOGUE', 'ACTION'].includes(message.messageType)
    ? message.character : undefined;
}

/** Fingerprint content so edited/regenerated replies become unread again without storing prose. */
export function speechSignature(message: ConversationSpeechMessage): string {
  const text = cleanTextForTTS(message.content);
  let hash = 2166136261;
  for (let i = 0; i < text.length; i++) hash = Math.imul(hash ^ text.charCodeAt(i), 16777619);
  return `${text.length}:${hash >>> 0}`;
}
