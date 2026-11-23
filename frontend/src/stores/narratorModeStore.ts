import { create } from 'zustand';

/** Per-adventure authoring preference, shared by chat and map. Defaults off on reload.
 * This controls presentation; server permissions still authorize mutations.
 */
export const useNarratorModeStore = create<{
  enabled: Record<string, boolean>;
  setEnabled: (conversationId: string, enabled: boolean) => void;
}>()((set) => ({
  enabled: {},
  setEnabled: (conversationId, enabled) => set(state => ({ enabled: { ...state.enabled, [conversationId]: enabled } })),
}));

export function useNarratorMode(conversationId?: string) {
  return useNarratorModeStore(state => !!conversationId && state.enabled[conversationId] === true);
}
