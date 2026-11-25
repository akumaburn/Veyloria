import * as Dialog from '@radix-ui/react-dialog';
import { NavLink, useLocation } from 'react-router-dom';
import {
  MessageSquare,
  Users,
  Swords,
  Globe,
  Trophy,
  Map,
  UsersRound,
  Database,
  X,
} from 'lucide-react';
import { cn } from '@/lib/utils';

const groups = [
  {
    title: 'Create & explore',
    items: [
      { to: '/chat', label: 'AI conversations', icon: MessageSquare },
      { to: '/characters', label: 'Characters', icon: Users },
      { to: '/roleplay', label: 'Roleplay', icon: Swords },
    ],
  },
  {
    title: 'Your world',
    items: [
      { to: '/community', label: 'Community', icon: Globe },
      { to: '/party', label: 'Parties', icon: UsersRound },
      { to: '/map', label: 'Maps', icon: Map },
      { to: '/achievements', label: 'Achievements', icon: Trophy },
    ],
  },
];

export function BrandMark({ className }: { className?: string }) {
  return (
    <span aria-hidden="true" className={cn('brand-mark', className)}>
      <i />
      <i />
      <i />
      <i />
      <i />
    </span>
  );
}

export function WorkspaceNavigation({ isOpen, onClose }: { isOpen: boolean; onClose: () => void }) {
  const location = useLocation();
  const conversationId = location.pathname.startsWith('/chat/')
    ? location.pathname.split('/')[2]
    : undefined;
  const content = (mobile = false) => (
    <div className="bg-background-secondary flex h-full min-h-0 flex-col">
      <div className="border-border flex h-14 shrink-0 items-center justify-between border-b px-6">
        <span className="text-sm font-semibold">Navigate</span>
        {mobile && (
          <button
            type="button"
            onClick={onClose}
            aria-label="Close navigation"
            className="workspace-icon-button"
          >
            <X size={19} />
          </button>
        )}
      </div>
      <nav
        aria-label="Workspace"
        className="custom-scrollbar min-h-0 flex-1 space-y-5 overflow-y-auto px-3 py-4"
      >
        {groups.map((group) => (
          <div key={group.title}>
            <h2 className="text-muted-foreground mb-3 px-3 text-[10px] font-semibold tracking-[0.16em] uppercase">
              {group.title}
            </h2>
            <div className="space-y-1">
              {group.items.map(({ to, label, icon: Icon }) => (
                <NavLink
                  key={to}
                  to={to === '/map' && conversationId ? `/map/${conversationId}` : to}
                  onClick={onClose}
                  className={({ isActive }) =>
                    cn('workspace-nav-link', isActive && 'workspace-nav-link-active')
                  }
                >
                  <Icon size={19} strokeWidth={1.6} />
                  <span className="flex-1">{label}</span>
                </NavLink>
              ))}
            </div>
          </div>
        ))}
        <div>
          <h2 className="text-muted-foreground mb-3 px-3 text-[10px] font-semibold tracking-[0.16em] uppercase">
            Manage
          </h2>
          <NavLink
            to="/data"
            onClick={onClose}
            className={({ isActive }) =>
              cn('workspace-nav-link', isActive && 'workspace-nav-link-active')
            }
          >
            <Database size={19} strokeWidth={1.6} />
            Your data
          </NavLink>
        </div>
      </nav>
      <div aria-hidden="true" className="sunset-stripe h-2 shrink-0" />
    </div>
  );

  return (
    <>
      <Dialog.Root
        open={isOpen}
        onOpenChange={(open) => {
          if (!open) onClose();
        }}
      >
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-40 bg-black/45 backdrop-blur-sm md:hidden" />
          <Dialog.Content
            id="mobile-workspace-navigation"
            aria-describedby={undefined}
            className="border-border fixed inset-y-0 left-0 z-50 w-72 max-w-[calc(100vw-3rem)] border-r outline-none md:hidden"
            onCloseAutoFocus={(event) => {
              event.preventDefault();
              document.getElementById('navigation-toggle')?.focus();
            }}
          >
            <Dialog.Title className="sr-only">Workspace navigation</Dialog.Title>
            {content(true)}
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </>
  );
}
