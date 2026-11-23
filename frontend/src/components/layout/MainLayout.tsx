import { useState, useEffect } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { Header } from './Header';
import { Sidebar } from './Sidebar';
import { WorkspaceNavigation } from './WorkspaceNavigation';
import { ChatToolbarContext } from './ChatToolbarContext';
import { CharacterCardModal } from '@/components/characters/CharacterCardModal';
import { PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { cn } from '@/lib/utils';

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
        <Header
          chatToolbarRef={setChatToolbarHost}
          onMenuToggle={() => {
            setIsSidebarOpen(false);
            setIsNavigationOpen((open) => !open);
          }}
          isMenuOpen={isNavigationOpen}

        />
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
          <button
            id="conversation-toggle-mobile"
            type="button"
            onClick={() => { setIsNavigationOpen(false); setIsSidebarOpen(open => !open); }}
            className="absolute left-0 top-1/2 z-30 flex h-12 w-7 -translate-y-1/2 items-center justify-center rounded-r-lg border border-l-0 border-border bg-card text-muted-foreground shadow-sm hover:bg-accent hover:text-foreground lg:hidden"
            aria-label={isSidebarOpen ? 'Close conversations' : 'Open conversations'}
            title="Show conversations"
            aria-expanded={isSidebarOpen}
            aria-controls="app-sidebar"
          ><PanelLeftOpen size={16} /></button>
          <Sidebar
            isOpen={isSidebarOpen}
            onClose={() => setIsSidebarOpen(false)}
            isCollapsed={isSidebarCollapsed}
          />
          <main
            id="main-content"
            tabIndex={-1}
            className="min-h-0 min-w-0 flex-1 overflow-y-auto pl-7 outline-none lg:pl-0"
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
