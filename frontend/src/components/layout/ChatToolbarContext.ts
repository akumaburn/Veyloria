import { createContext } from 'react';

/** The main header hosts regular chat controls without adding a second toolbar. */
export const ChatToolbarContext = createContext<HTMLDivElement | null>(null);
