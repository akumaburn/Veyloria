import { createContext } from 'react';

/**
 * A slot in a host panel's header bar. Content rendered inside the panel can
 * portal its own controls there instead of adding another bar of its own.
 */
export const PanelToolbarContext = createContext<HTMLElement | null>(null);
