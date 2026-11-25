import React, { useEffect, lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ApolloProvider, useQuery, gql } from '@apollo/client';
import { Toaster } from 'react-hot-toast';

// Core imports
import { apolloClient } from './lib/apollo-client';
import { setDebugMode } from './lib/debug-mode';
import { AuthProvider } from './providers/AuthProvider';
import { useAuthStore } from './stores/authStore';
import { useSettingsStore } from './stores/settingsStore';

// Components
import { ErrorBoundary } from './components/ui/ErrorBoundary';
import { LoadingSpinner } from './components/ui/LoadingStates';
import { SessionTimeoutWarning } from './components/SessionTimeoutWarning';
import { MainLayout } from './components/layout/MainLayout';

// Lazy load pages for code splitting
const LoginPage = lazy(() => import('./pages/LoginPage'));
const RegisterPage = lazy(() => import('./pages/RegisterPage'));
const ChatPage = lazy(() => import('./pages/ChatPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));
const AdminPage = lazy(() => import('./pages/AdminPage'));
const ProfilePage = lazy(() => import('./pages/ProfilePage'));
const CharactersPage = lazy(() => import('./pages/CharactersPage'));
const DataManagementPage = lazy(() => import('./pages/DataManagementPage'));
const NotFoundPage = lazy(() => import('./pages/NotFoundPage'));
const TermsPage = lazy(() => import('./pages/TermsPage'));
const PrivacyPage = lazy(() => import('./pages/PrivacyPage'));

// Roleplay pages
const RoleplayPage = lazy(() => import('./pages/RoleplayPage'));
const AchievementsPage = lazy(() => import('./pages/AchievementsPage'));
const MapPage = lazy(() => import('./pages/MapPage'));
const PartyPage = lazy(() => import('./pages/PartyPage'));
const CommunityPage = lazy(() => import('./pages/CommunityPage'));
const StoryReaderPage = lazy(() => import('./pages/StoryReaderPage'));
const TestMapPage = lazy(() => import('./pages/TestMapPage'));

const DEBUG_MODE_QUERY = gql`
  query DebugMode {
    debugMode
  }
`;

// Protected Route Component
const ProtectedRoute: React.FC<{ children: React.ReactNode; requireAdmin?: boolean }> = ({
  children,
  requireAdmin = false
}) => {
  const { user, isAuthenticated } = useAuthStore();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (requireAdmin && user?.role !== 'admin') {
    return <Navigate to="/chat" replace />;
  }

  return <>{children}</>;
};

// Public Route Component (redirect if already logged in)
const PublicRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated } = useAuthStore();

  if (isAuthenticated) {
    return <Navigate to="/chat" replace />;
  }

  return <>{children}</>;
};

// Loading fallback component
const PageLoader: React.FC = () => (
  <div className="flex items-center justify-center min-h-dvh">
    <LoadingSpinner size="lg" />
  </div>
);

/** Inner shell rendered inside ApolloProvider so hooks have access to the client. */
function AppContent() {
  const { uiSettings, fetchSettings } = useSettingsStore();
  const { theme, fontSize, soundEnabled } = uiSettings;
  const { refreshAuth, isAuthenticated } = useAuthStore();

  // Query backend debug mode flag (no auth required).
  // Must resolve BEFORE AuthProvider renders so the redirect logic sees the flag.
  const { data: debugData, loading: debugLoading } = useQuery(DEBUG_MODE_QUERY, {
    fetchPolicy: 'cache-first',
  });
  const debugMode = debugData?.debugMode === true;

  // Set module-level flag synchronously (not in useEffect) so AuthProvider
  // reads the correct value on its very first render/effect cycle.
  setDebugMode(debugMode);

  // Fetch settings from backend when authenticated
  useEffect(() => {
    if (isAuthenticated) {
      fetchSettings();
    }
  }, [isAuthenticated, fetchSettings]);

  // Apply theme
  useEffect(() => {
    const root = document.documentElement;

    // Apply theme - handle 'auto' as alias for 'system'
    const effectiveTheme = theme === 'auto' ? 'system' : theme;
    if (effectiveTheme === 'dark' || (effectiveTheme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
      root.classList.add('dark');
    } else {
      root.classList.remove('dark');
    }

    // Apply font size - map string values to actual pixel sizes
    const fontSizeMap: Record<string, string> = {
      small: '14px',
      medium: '16px',
      large: '18px',
    };
    root.style.fontSize = fontSizeMap[fontSize] || '16px';

    // Listen for system theme changes
    if (effectiveTheme === 'system') {
      const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
      const handleChange = (e: MediaQueryListEvent) => {
        if (e.matches) {
          root.classList.add('dark');
        } else {
          root.classList.remove('dark');
        }
      };

      mediaQuery.addEventListener('change', handleChange);
      return () => mediaQuery.removeEventListener('change', handleChange);
    }

    // Return undefined for non-system themes
    return undefined;
  }, [theme, fontSize]);

  // Check authentication on mount
  useEffect(() => {
    void refreshAuth();
  }, [refreshAuth]);

  // Set up sound effects
  useEffect(() => {
    if (!soundEnabled) return;

    // Play sound on new message
    const playMessageSound = () => {
      const audio = new Audio('/sounds/message.mp3');
      audio.volume = 0.3;
      audio.play().catch(() => {});
    };

    // Listen for new message events
    const handleNewMessage = () => playMessageSound();
    window.addEventListener('newMessage', handleNewMessage);
    return () => window.removeEventListener('newMessage', handleNewMessage);
  }, [soundEnabled]);

  // Prevent accidental navigation away with unsaved settings changes
  const { hasUnsavedChanges } = useSettingsStore();
  useEffect(() => {
    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      if (hasUnsavedChanges) {
        e.preventDefault();
        e.returnValue = '';
      }
    };

    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [hasUnsavedChanges]);

  // Wait for the debug-mode query so AuthProvider sees the correct flag
  if (debugLoading) {
    return <PageLoader />;
  }

  return (
    <BrowserRouter>
      <AuthProvider>
        <div className="min-h-dvh bg-background text-foreground">
          {/* Global components */}
          <SessionTimeoutWarning />
          <Toaster
            position="bottom-center"
            containerStyle={{ bottom: 88 }}
            toastOptions={{
              duration: 4000,
              className: 'bg-card text-card-foreground border border-border shadow-lg',
              style: {
                background: 'hsl(var(--card))',
                color: 'hsl(var(--card-foreground))',
                border: '1px solid hsl(var(--border))',
              },
              success: {
                iconTheme: {
                  primary: 'hsl(var(--success))',
                  secondary: 'hsl(var(--success-foreground))',
                },
              },
              error: {
                iconTheme: {
                  primary: 'hsl(var(--destructive))',
                  secondary: 'hsl(var(--destructive-foreground))',
                },
              },
            }}
          />

          {/* Routes */}
          <Suspense fallback={<PageLoader />}>
            <Routes>
              {/* Public routes */}
              <Route path="/login" element={
                <PublicRoute>
                  <LoginPage />
                </PublicRoute>
              } />
              <Route path="/register" element={
                <PublicRoute>
                  <RegisterPage />
                </PublicRoute>
              } />

              {/* Public auth pages */}
              <Route path="/forgot-password" element={
                <PublicRoute>
                  <div className="min-h-dvh flex items-center justify-center">
                    <p className="text-muted-foreground">Password reset coming soon</p>
                  </div>
                </PublicRoute>
              } />
              <Route path="/reset-password" element={
                <PublicRoute>
                  <div className="min-h-dvh flex items-center justify-center">
                    <p className="text-muted-foreground">Password reset coming soon</p>
                  </div>
                </PublicRoute>
              } />

              {/* Public legal pages */}
              <Route path="/terms" element={<TermsPage />} />
              <Route path="/privacy" element={<PrivacyPage />} />

              {/* Debug-only public route (no MainLayout, so provide full-height container) */}
              {debugMode && (
                <Route path="/testmap" element={
                  <div className="h-dvh flex flex-col">
                    <TestMapPage />
                  </div>
                } />
              )}

              {/* Protected routes with layout */}
              <Route path="/" element={
                <ProtectedRoute>
                  <MainLayout />
                </ProtectedRoute>
              }>
                <Route index element={<Navigate to="/chat" replace />} />
                <Route path="chat" element={<ChatPage />} />
                <Route path="chat/:conversationId" element={<ChatPage />} />
                <Route path="characters" element={<CharactersPage />} />
                <Route path="settings" element={<SettingsPage />} />
                <Route path="profile" element={<ProfilePage />} />
                <Route path="data" element={<DataManagementPage />} />

                {/* Roleplay routes */}
                <Route path="roleplay" element={<RoleplayPage />} />
                <Route path="achievements" element={<AchievementsPage />} />
                <Route path="map/:conversationId" element={<MapPage />} />
                <Route path="map" element={<MapPage />} />
                <Route path="party" element={<PartyPage />} />
                <Route path="community" element={<CommunityPage />} />
                <Route path="community/story/:storyId" element={<StoryReaderPage />} />
                {!debugMode && <Route path="testmap" element={<TestMapPage />} />}

                {/* Admin route */}
                <Route path="admin" element={
                  <ProtectedRoute requireAdmin>
                    <AdminPage />
                  </ProtectedRoute>
                } />
              </Route>

              {/* 404 page */}
              <Route path="*" element={<NotFoundPage />} />
            </Routes>
          </Suspense>
        </div>
      </AuthProvider>
    </BrowserRouter>
  );
}

/** Outer shell — provides ApolloProvider so AppContent hooks have access to the client. */
function App() {
  return (
    <ApolloProvider client={apolloClient}>
      <ErrorBoundary>
        <AppContent />
      </ErrorBoundary>
    </ApolloProvider>
  );
}

export default App;