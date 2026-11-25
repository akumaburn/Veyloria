import { useState, useEffect, useRef } from 'react';
import { useCollapsingHeader } from './useCollapsingHeader';
import { Outlet, useLocation } from 'react-router-dom';
import { Header } from './Header';
import { Sidebar } from './Sidebar';
import { WorkspaceNavigation } from './WorkspaceNavigation';
import { ChatToolbarContext } from './ChatToolbarContext';
import { CharacterCardModal } from '@/components/characters/CharacterCardModal';
import { PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { cn } from '@/lib/utils';

/** Phones hide the app header after this long without interaction. */
const HEADER_IDLE_MS = 5000;

export const MainLayout = () => {
  const [chatToolbarHost, setChatToolbarHost] = useState<HTMLDivElement | null>(null);
  const [isNavigationOpen, setIsNavigationOpen] = useState(false);
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(() => {
    try {
      return localStorage.getItem('conversation-sidebar-collapsed') === 'true';
    } catch {
      return false;
    }
  });
  const location = useLocation();
  const mainRef = useRef<HTMLElement>(null);
  const headerRef = useRef<HTMLDivElement>(null);
  const { collapsed: headerCollapsed } = useCollapsingHeader(mainRef, location.pathname, {
    idleMs: HEADER_IDLE_MS,
    // Stay put while a drawer or one of the header's own menus is open, or while
    // the header holds focus.
    isPinned: () => {
      const header = headerRef.current;
      return isNavigationOpen || isSidebarOpen || !!header?.contains(document.activeElement)
        || !!header?.querySelector('[aria-expanded="true"], [data-state="open"]');
    },
  });

  // A collapsed header is out of the tab order and hidden from assistive tech.
  useEffect(() => {
    const header = headerRef.current;
    if (!header) return;
    if (headerCollapsed) header.setAttribute('inert', '');
    else header.removeAttribute('inert');
  }, [headerCollapsed]);

  useEffect(() => {
    try {
      localStorage.setItem('conversation-sidebar-collapsed', String(isSidebarCollapsed));
    } catch {
      // Collapsing still works when browser storage is unavailable.
    }
  }, [isSidebarCollapsed]);

  useEffect(() => {
    setIsSidebarOpen(false);
    setIsNavigationOpen(false);
  }, [location.pathname]);

  useEffect(() => {
    const queries = ['(min-width: 768px)', '(min-width: 1024px)'].map((query) =>
      window.matchMedia(query),
    );
    const closeDrawers = () => {
      setIsNavigationOpen(false);
      setIsSidebarOpen(false);
    };
    queries.forEach((query) => query.addEventListener('change', closeDrawers));
    return () => queries.forEach((query) => query.removeEventListener('change', closeDrawers));
  }, []);

  return (
    <div className="workspace-shell bg-background flex h-dvh min-h-0 flex-col overflow-hidden">
      <a
        href="#main-content"
        className="focus:bg-card sr-only focus:not-sr-only focus:fixed focus:top-4 focus:left-4 focus:z-50 focus:rounded-lg focus:px-4 focus:py-3 focus:shadow-lg"
      >
        Skip to content
      </a>
      <WorkspaceNavigation isOpen={isNavigationOpen} onClose={() => setIsNavigationOpen(false)} />
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        {/* On phones the header slides away while content scrolls down and
            returns on the way back up. */}
        <div
          className={cn(
            'grid shrink-0 transition-[grid-template-rows] duration-200 ease-out motion-reduce:transition-none',
            headerCollapsed ? 'grid-rows-[0fr]' : 'grid-rows-[1fr]',
          )}
        >
        <div ref={headerRef} className="min-h-0 overflow-hidden">
        <Header
          chatToolbarRef={setChatToolbarHost}
          onMenuToggle={() => {
            setIsSidebarOpen(false);
            setIsNavigationOpen((open) => !open);
          }}
          isMenuOpen={isNavigationOpen}
          onConversationsToggle={() => {
            setIsNavigationOpen(false);
            setIsSidebarOpen((open) => !open);
          }}
          isConversationsOpen={isSidebarOpen}
        />
        </div>
        </div>
        <div className="relative flex min-h-0 flex-1 overflow-hidden">
          <button
            type="button"
            onClick={() => setIsSidebarCollapsed(collapsed => !collapsed)}
            className={cn('absolute top-1/2 z-30 hidden h-12 w-7 -translate-y-1/2 items-center justify-center rounded-r-lg border border-border bg-card text-muted-foreground shadow-sm transition-colors hover:bg-accent hover:text-foreground lg:flex', isSidebarCollapsed ? 'left-0 border-l-0' : 'left-60 -translate-x-1/2 rounded-lg')}
            aria-label={isSidebarCollapsed ? 'Expand conversation sidebar' : 'Collapse conversation sidebar'}
            title={isSidebarCollapsed ? 'Show conversations' : 'Hide conversations'}
            aria-expanded={!isSidebarCollapsed}
            aria-controls="app-sidebar"
          >
            {isSidebarCollapsed ? <PanelLeftOpen size={16} /> : <PanelLeftClose size={16} />}
          </button>
          <Sidebar
            isOpen={isSidebarOpen}
            onClose={() => setIsSidebarOpen(false)}
            isCollapsed={isSidebarCollapsed}
          />
          <main
            ref={mainRef}
            id="main-content"
            tabIndex={-1}
            className="min-h-0 min-w-0 flex-1 overflow-y-auto outline-none"
          >
            <div className="h-full min-h-0 min-w-0">
              <ChatToolbarContext.Provider value={chatToolbarHost}>
                <Outlet />
              </ChatToolbarContext.Provider>
            </div>
          </main>
        </div>
      </div>
      <div aria-hidden="true" className="sunset-stripe h-1 shrink-0" />
      <CharacterCardModal />
    </div>
  );
};
