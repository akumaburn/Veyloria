import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { NavLink, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';
import {
  Menu,
  MessagesSquare,
  Settings,
  LogOut,
  User,
  ChevronDown,
  UsersRound,
  Map,
  Trophy,
  Database,
  Globe,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { BrandMark } from './WorkspaceNavigation';

interface HeaderProps {
  chatToolbarRef: (element: HTMLDivElement | null) => void;
  onMenuToggle: () => void;
  isMenuOpen: boolean;
  /** Opens the conversation drawer, which is only a drawer below 1024px. */
  onConversationsToggle: () => void;
  isConversationsOpen: boolean;
}

export const Header = ({
  chatToolbarRef,
  onMenuToggle,
  isMenuOpen,
  onConversationsToggle,
  isConversationsOpen,
}: HeaderProps) => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuthStore();
  const section = location.pathname.split('/')[1];
  const sectionNames: Record<string, string> = {
    chat: 'AI conversations',
    characters: 'Characters',
    roleplay: 'Roleplay',
    community: 'Community',
    achievements: 'Achievements',
    party: 'Parties',
    map: 'Maps',
    settings: 'Settings',
    profile: 'Profile',
    data: 'Your data',
    admin: 'Administration',
  };
  const itemClass =
    'flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-sm outline-none data-[highlighted]:bg-accent';

  return (
    <header className="border-border bg-background-secondary relative z-20 shrink-0 border-b">
      <div className="flex h-14 items-center justify-between gap-2 px-2 sm:px-5">
        <div className="flex min-w-0 items-center gap-1 sm:gap-3">
          <button
            type="button"
            id="navigation-toggle"
            onClick={onMenuToggle}
            className="workspace-icon-button md:hidden"
            title="Navigate"
            aria-label={isMenuOpen ? 'Close navigation' : 'Open navigation'}
            aria-expanded={isMenuOpen}
            aria-controls="mobile-workspace-navigation"
          >
            <Menu size={21} />
          </button>
          <button
            type="button"
            id="conversation-toggle-mobile"
            onClick={onConversationsToggle}
            className={cn('workspace-icon-button lg:hidden', isConversationsOpen && 'bg-accent text-foreground')}
            title="Conversations"
            aria-label={isConversationsOpen ? 'Close conversations' : 'Open conversations'}
            aria-expanded={isConversationsOpen}
            aria-controls="app-sidebar"
          >
            <MessagesSquare size={20} />
          </button>
          <span className="flex shrink-0 items-center gap-1.5" aria-label="Veyloria">
            <BrandMark className="scale-75" />
            <span className="hidden text-base font-semibold tracking-tight sm:inline">
              Veyloria<span className="text-brand-orange">_</span>
            </span>
          </span>
          <nav aria-label="Quick navigation" className="ml-2 hidden items-center gap-0.5 md:flex">
            {[
              { to: '/chat', label: 'Chat' },
              { to: '/characters', label: 'Characters' },
              { to: '/roleplay', label: 'Roleplay' },
            ].map(({ to, label }) => (
              <NavLink
                key={to}
                to={to}
                className={({ isActive }) =>
                  cn(
                    'hover:bg-accent rounded-md px-2.5 py-2 text-xs font-medium transition-colors',
                    isActive ? 'bg-accent text-foreground' : 'text-muted-foreground',
                  )
                }
              >
                {label}
              </NavLink>
            ))}
            <DropdownMenu.Root>
              <DropdownMenu.Trigger asChild>
                <button
                  type="button"
                  aria-label="More workspace tools"
                  className="text-muted-foreground hover:bg-accent flex items-center gap-1 rounded-md px-2.5 py-2 text-xs font-medium"
                >
                  Explore
                  <ChevronDown size={13} />
                </button>
              </DropdownMenu.Trigger>
              <DropdownMenu.Portal>
                <DropdownMenu.Content
                  align="start"
                  sideOffset={10}
                  className="border-border bg-popover text-popover-foreground z-50 w-48 rounded-xl border p-1.5 shadow-lg"
                >
                  {[
                    { to: '/community', label: 'Community', icon: Globe },
                    { to: '/party', label: 'Parties', icon: UsersRound },
                    {
                      to:
                        section === 'chat' && location.pathname.split('/')[2]
                          ? `/map/${location.pathname.split('/')[2]}`
                          : '/map',
                      label: 'Maps',
                      icon: Map,
                    },
                    { to: '/achievements', label: 'Achievements', icon: Trophy },
                    { to: '/data', label: 'Your data', icon: Database },
                  ].map(({ to, label, icon: Icon }) => (
                    <DropdownMenu.Item key={to} asChild>
                      <NavLink to={to} className={itemClass}>
                        <Icon size={16} />
                        {label}
                      </NavLink>
                    </DropdownMenu.Item>
                  ))}
                </DropdownMenu.Content>
              </DropdownMenu.Portal>
            </DropdownMenu.Root>
          </nav>
          {/*
            Chat has no page header, so the shell names that section. Every other
            route puts its own h1 in the main region.
          */}
          {section === 'chat' && (
            <h1 className="sr-only">{sectionNames[section] || 'Workspace'}</h1>
          )}
        </div>
        <div className="flex min-w-0 items-center gap-1 sm:gap-2">
          <div ref={chatToolbarRef} className="flex min-w-0 items-center gap-1" />
          <span className="bg-border hidden h-6 w-px sm:block" aria-hidden="true" />
          <DropdownMenu.Root>
            <DropdownMenu.Trigger asChild>
              <button
                className="hover:bg-accent flex items-center gap-1 rounded-lg p-1 transition-colors"
                aria-label="Account menu"
              >
                <span className="border-border bg-accent flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border text-sm font-semibold">
                  {(user?.displayName || user?.username || 'U').slice(0, 1).toUpperCase()}
                </span>
                <span className="hidden max-w-24 truncate text-xs font-medium 2xl:block">
                  {user?.displayName || user?.username || 'Account'}
                </span>
                <ChevronDown size={14} className="text-muted-foreground hidden 2xl:block" />
              </button>
            </DropdownMenu.Trigger>
            <DropdownMenu.Portal>
              <DropdownMenu.Content
                align="end"
                sideOffset={12}
                className="border-border bg-popover text-popover-foreground z-50 w-64 max-w-[calc(100vw-2rem)] rounded-xl border p-2 shadow-xl"
              >
                <DropdownMenu.Label className="px-3 py-2">
                  <span className="block truncate text-sm font-semibold">
                    {user?.displayName || user?.username}
                  </span>
                  <span className="text-muted-foreground mt-0.5 block truncate text-xs font-normal">
                    {user?.email}
                  </span>
                </DropdownMenu.Label>
                <DropdownMenu.Separator className="bg-border my-1 h-px" />
                <DropdownMenu.Item onSelect={() => navigate('/profile')} className={itemClass}>
                  <User size={16} />
                  Profile
                </DropdownMenu.Item>
                <DropdownMenu.Item onSelect={() => navigate('/settings')} className={itemClass}>
                  <Settings size={16} />
                  Settings
                </DropdownMenu.Item>
                <DropdownMenu.Separator className="bg-border my-1 h-px" />
                <DropdownMenu.Item
                  onSelect={() => {
                    void logout().then(() => navigate('/login'));
                  }}
                  className={cn(itemClass, 'text-destructive data-[highlighted]:bg-destructive/10')}
                >
                  <LogOut size={16} />
                  Log out
                </DropdownMenu.Item>
              </DropdownMenu.Content>
            </DropdownMenu.Portal>
          </DropdownMenu.Root>
        </div>
      </div>
    </header>
  );
};
