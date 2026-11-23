/**
 * Character Card Store
 *
 * Holds the character whose card is open. Any avatar in the app can open the
 * card without owning a modal, and one mounted <CharacterCardModal /> renders
 * it. The store is deliberately not persisted: a refresh closes the card.
 */

import { create } from 'zustand';

interface CharacterCardState {
  characterId: string | null;
  /** The story the card was opened from, when there is one. */
  conversationId: string | null;
  openCharacterCard: (characterId: string, conversationId?: string) => void;
  closeCharacterCard: () => void;
}

export const useCharacterCardStore = create<CharacterCardState>()((set) => ({
  characterId: null,
  conversationId: null,
  openCharacterCard: (characterId, conversationId) =>
    set({ characterId, conversationId: conversationId ?? null }),
  closeCharacterCard: () => set({ characterId: null, conversationId: null }),
}));
