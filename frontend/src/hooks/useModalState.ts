import { useState, useCallback, useEffect } from 'react';

/**
 * Return type for useModalState hook.
 */
export interface UseModalStateReturn {
  isOpen: boolean;
  open: () => void;
  close: () => void;
  toggle: () => void;
  setIsOpen: React.Dispatch<React.SetStateAction<boolean>>;
}

/**
 * Return type for useModalWithReset hook.
 */
export interface UseModalWithResetReturn<T> {
  isOpen: boolean;
  state: T;
  setState: React.Dispatch<React.SetStateAction<T>>;
  open: () => void;
  close: () => void;
  openWith: (newState: Partial<T>) => void;
  reset: () => void;
}

/**
 * Return type for useConfirmation hook.
 */
export interface UseConfirmationReturn<T> {
  isOpen: boolean;
  pending: T | null;
  requestConfirm: (data: T) => void;
  confirm: () => T | null;
  cancel: () => void;
}

/**
 * Return type for useMultipleModals hook.
 */
export interface UseMultipleModalsReturn<T extends string> {
  openModal: T | null;
  open: (name: T) => void;
  close: () => void;
  isOpen: (name: T) => boolean;
  modals: Record<T, { isOpen: boolean; open: () => void; close: () => void }>;
}

/**
 * Modal state management hook to eliminate duplicate modal handling code.
 * Consolidates patterns from InviteUserModal, InterjectionModal, GroupConversationCreator, etc.
 */
export function useModalState(initialOpen: boolean = false): UseModalStateReturn {
  const [isOpen, setIsOpen] = useState(initialOpen);

  const open = useCallback(() => setIsOpen(true), []);
  const close = useCallback(() => setIsOpen(false), []);
  const toggle = useCallback(() => setIsOpen((prev) => !prev), []);

  return {
    isOpen,
    open,
    close,
    toggle,
    setIsOpen,
  };
}

/**
 * Modal state with form reset on close.
 * Useful when modal contains a form that should reset when closed.
 */
export function useModalWithReset<T>(initialState: T): UseModalWithResetReturn<T> {
  const [isOpen, setIsOpen] = useState(false);
  const [state, setState] = useState<T>(initialState);

  const open = useCallback(() => setIsOpen(true), []);

  const close = useCallback(() => {
    setIsOpen(false);
    setState(initialState);
  }, [initialState]);

  const openWith = useCallback((newState: Partial<T>) => {
    setState((prev) => ({ ...prev, ...newState }));
    setIsOpen(true);
  }, []);

  const reset = useCallback(() => {
    setState(initialState);
  }, [initialState]);

  return {
    isOpen,
    state,
    setState,
    open,
    close,
    openWith,
    reset,
  };
}

/**
 * Confirmation dialog state hook.
 * Handles the pattern of showing a confirmation before destructive actions.
 */
export function useConfirmation<T = void>(): UseConfirmationReturn<T> {
  const [isOpen, setIsOpen] = useState(false);
  const [pending, setPending] = useState<T | null>(null);

  const requestConfirm = useCallback((data: T) => {
    setPending(data);
    setIsOpen(true);
  }, []);

  const confirm = useCallback(() => {
    const result = pending;
    setIsOpen(false);
    setPending(null);
    return result;
  }, [pending]);

  const cancel = useCallback(() => {
    setIsOpen(false);
    setPending(null);
  }, []);

  return {
    isOpen,
    pending,
    requestConfirm,
    confirm,
    cancel,
  };
}

/**
 * Multiple modals management hook.
 * Useful when a component has multiple modals (e.g., ParticipantList with invite, remove, transfer modals).
 */
export function useMultipleModals<T extends string>(modalNames: readonly T[]): UseMultipleModalsReturn<T> {
  const [openModal, setOpenModal] = useState<T | null>(null);

  const open = useCallback((name: T) => setOpenModal(name), []);
  const close = useCallback(() => setOpenModal(null), []);
  const isOpen = useCallback((name: T) => openModal === name, [openModal]);

  // Create typed helpers for each modal
  const modals = modalNames.reduce(
    (acc, name) => {
      acc[name] = {
        isOpen: openModal === name,
        open: () => setOpenModal(name),
        close: () => setOpenModal(null),
      };
      return acc;
    },
    {} as Record<T, { isOpen: boolean; open: () => void; close: () => void }>
  );

  return {
    openModal,
    open,
    close,
    isOpen,
    modals,
  };
}

/**
 * Hook to handle modal cleanup on unmount or close.
 * Ensures state is properly reset when modal is closed.
 */
export function useModalCleanup(isOpen: boolean, cleanup: () => void) {
  useEffect(() => {
    if (!isOpen) {
      cleanup();
    }
  }, [isOpen, cleanup]);
}

/**
 * Hook to handle escape key to close modal.
 */
export function useEscapeKey(onEscape: () => void, isActive: boolean = true) {
  useEffect(() => {
    if (!isActive) return;

    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onEscape();
      }
    };

    document.addEventListener('keydown', handleEscape);
    return () => document.removeEventListener('keydown', handleEscape);
  }, [onEscape, isActive]);
}

/**
 * Hook to lock body scroll when modal is open.
 */
export function useBodyScrollLock(isLocked: boolean) {
  useEffect(() => {
    if (isLocked) {
      const originalOverflow = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
      return () => {
        document.body.style.overflow = originalOverflow;
      };
    }
    return undefined;
  }, [isLocked]);
}

export default useModalState;
