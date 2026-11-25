import { useEffect, useRef, useState, type RefObject } from 'react';

const PHONE_QUERY = '(max-width: 767px)';
/** Scroll distance before the header reacts, so small jitters do nothing. */
const THRESHOLD_PX = 24;
/** A downward swipe this long reveals the header, even where nothing scrolls. */
const REVEAL_SWIPE_PX = 32;
/** Scrolls this soon after a touch or wheel gesture count as the reader's own. */
const GESTURE_WINDOW_MS = 600;
/**
 * After the header moves, the content area changes height and the browser may
 * clamp scroll positions. Those shifts are not the reader's, so they are ignored
 * for slightly longer than the 200ms transition.
 */
const SETTLE_MS = 320;

interface CollapsingHeaderOptions {
  /** Hide the header after this long without interaction. */
  idleMs: number;
  /** True while the header must stay put, such as when one of its menus is open. */
  isPinned: () => boolean;
}

/**
 * Collapses the app header on phones while the reader scrolls content down, or
 * after a spell without interaction, and restores it when they scroll or swipe
 * back down the page.
 *
 * Scroll events do not bubble, so one capture listener on the content region sees
 * every scroller inside it. Only scrolls that follow a touch or wheel gesture
 * count: programmatic scrolling, such as the chat following a new message, never
 * moves the header.
 */
export function useCollapsingHeader(
  contentRef: RefObject<HTMLElement>,
  resetKey: unknown,
  { idleMs, isPinned }: CollapsingHeaderOptions,
) {
  const [collapsed, setCollapsed] = useState(false);
  const lastGesture = useRef(0);
  const anchors = useRef(new WeakMap<EventTarget, number>());
  const collapsedRef = useRef(false);
  const settleUntil = useRef(0);
  const isPinnedRef = useRef(isPinned);
  isPinnedRef.current = isPinned;

  useEffect(() => {
    collapsedRef.current = false;
    setCollapsed(false);
    anchors.current = new WeakMap();
  }, [resetKey]);

  useEffect(() => {
    const content = contentRef.current;
    if (!content) return undefined;
    const phone = window.matchMedia(PHONE_QUERY);
    let idleTimer: number | undefined;
    let swipeStartY: number | null = null;

    const apply = (next: boolean) => {
      if (collapsedRef.current === next) return;
      collapsedRef.current = next;
      settleUntil.current = Date.now() + SETTLE_MS;
      setCollapsed(next);
      scheduleIdle();
    };

    const scheduleIdle = () => {
      window.clearTimeout(idleTimer);
      if (!phone.matches || collapsedRef.current) return;
      idleTimer = window.setTimeout(() => {
        if (isPinnedRef.current()) scheduleIdle();
        else apply(true);
      }, idleMs);
    };

    const onInteraction = () => scheduleIdle();
    const markGesture = () => { lastGesture.current = Date.now(); };

    const onTouchStart = (event: TouchEvent) => {
      swipeStartY = event.touches[0]?.clientY ?? null;
      markGesture();
    };
    const onTouchMove = (event: TouchEvent) => {
      markGesture();
      const y = event.touches[0]?.clientY;
      if (swipeStartY === null || y === undefined) return;
      // A finger moving down pulls content down: the reader is heading back up.
      if (y - swipeStartY > REVEAL_SWIPE_PX) {
        swipeStartY = y;
        if (phone.matches) apply(false);
      }
    };
    const onWheel = (event: WheelEvent) => {
      markGesture();
      if (event.deltaY < 0 && phone.matches) apply(false);
    };

    const onScroll = (event: Event) => {
      const target = event.target;
      // Sideways strips (tabs, chips) say nothing about reading direction.
      if (!(target instanceof HTMLElement) || target.scrollHeight <= target.clientHeight) return;
      const top = target.scrollTop;
      if (!phone.matches || top <= 0) {
        anchors.current.set(target, Math.max(top, 0));
        if (top <= 0) apply(false);
        return;
      }
      const anchor = anchors.current.get(target);
      const now = Date.now();
      if (anchor === undefined || now < settleUntil.current || now - lastGesture.current > GESTURE_WINDOW_MS) {
        anchors.current.set(target, top);
        return;
      }
      const delta = top - anchor;
      if (Math.abs(delta) < THRESHOLD_PX) return;
      anchors.current.set(target, top);
      apply(delta > 0);
    };
    const onViewportChange = () => {
      if (!phone.matches) apply(false);
      scheduleIdle();
    };

    const interactions = ['pointerdown', 'keydown', 'touchstart', 'wheel', 'focusin'] as const;
    interactions.forEach((type) => window.addEventListener(type, onInteraction, { passive: true, capture: true }));
    content.addEventListener('touchstart', onTouchStart, { passive: true });
    content.addEventListener('touchmove', onTouchMove, { passive: true });
    content.addEventListener('wheel', onWheel, { passive: true });
    content.addEventListener('scroll', onScroll, { capture: true, passive: true });
    phone.addEventListener('change', onViewportChange);
    scheduleIdle();
    return () => {
      window.clearTimeout(idleTimer);
      interactions.forEach((type) => window.removeEventListener(type, onInteraction, { capture: true }));
      content.removeEventListener('touchstart', onTouchStart);
      content.removeEventListener('touchmove', onTouchMove);
      content.removeEventListener('wheel', onWheel);
      content.removeEventListener('scroll', onScroll, { capture: true });
      phone.removeEventListener('change', onViewportChange);
    };
  }, [contentRef, idleMs, resetKey]);

  return { collapsed };
}
