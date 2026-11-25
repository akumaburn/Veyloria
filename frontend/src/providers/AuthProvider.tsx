import { useEffect, ReactNode } from 'react';
import { useAuthStore } from '@/stores/authStore';
import { useNavigate, useLocation } from 'react-router-dom';
import { isDebugMode } from '@/lib/debug-mode';

interface AuthProviderProps {
  children: ReactNode;
}

// Routes that redirect to dashboard when already authenticated (login/register pages)
const authOnlyRoutes = ['/login', '/register', '/forgot-password', '/reset-password'];

// Routes accessible without authentication (no redirect to login)
const alwaysAccessibleRoutes = ['/terms', '/privacy'];

export const AuthProvider = ({ children }: AuthProviderProps) => {
  const { isAuthenticated, isLoading, refreshAuth } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => {
    // Try to refresh auth on mount
    void refreshAuth();
  }, [refreshAuth]);

  useEffect(() => {
    // Redirect logic based on auth state
    if (!isLoading) {
      const isAuthOnlyRoute = authOnlyRoutes.some((route) => location.pathname.startsWith(route));
      const debugAccessible = isDebugMode && location.pathname.startsWith('/testmap');
      const isAlwaysAccessible = alwaysAccessibleRoutes.some((route) => location.pathname.startsWith(route))
        || debugAccessible;

      if (!isAuthenticated && !isAuthOnlyRoute && !isAlwaysAccessible) {
        // Redirect to login if not authenticated and not on a public/accessible route
        navigate('/login', {
          replace: true,
          state: { from: location.pathname }
        });
      } else if (isAuthenticated && isAuthOnlyRoute) {
        // Redirect to dashboard if authenticated and on a login/register route
        const from = (location.state as { from?: string })?.from || '/';
        navigate(from, { replace: true });
      }
    }
  }, [isAuthenticated, isLoading, location, navigate]);

  // Show loading state while checking auth
  if (isLoading && !isAuthenticated) {
    return (
      <div className="flex h-dvh items-center justify-center bg-background">
        <div className="flex flex-col items-center space-y-4">
          <div className="h-12 w-12 animate-spin rounded-full border-4 border-border border-t-primary" />
          <p className="text-muted-foreground">Loading...</p>
        </div>
      </div>
    );
  }

  return <>{children}</>;
};