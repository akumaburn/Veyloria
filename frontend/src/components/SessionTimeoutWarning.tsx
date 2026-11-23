import { useEffect, useRef, useState, useCallback, memo } from 'react';
import { AlertTriangle, Clock, LogOut, RefreshCw } from 'lucide-react';
import { useMutation } from '@apollo/client';
import { useAuthStore } from '../stores/authStore';
import { REFRESH_TOKEN_MUTATION, LOGOUT_MUTATION } from '../graphql/operations';
import { Modal } from './ui/Modal';
import { toast } from 'react-hot-toast';

interface SessionTimeoutWarningProps {
  warningTime?: number; // Time before expiry to show warning (in seconds)
  checkInterval?: number; // How often to check token expiry (in seconds)
}

const SessionTimeoutWarning = memo(function SessionTimeoutWarning({
  warningTime = 300, // 5 minutes before expiry
  checkInterval = 30, // Check every 30 seconds
}: SessionTimeoutWarningProps) {
  const { user, accessToken, refreshToken, logout: logoutStore, setTokens } = useAuthStore();
  const [showWarning, setShowWarning] = useState(false);
  const [timeRemaining, setTimeRemaining] = useState<number>(0);
  const [isRefreshing, setIsRefreshing] = useState(false);
  
  const [refreshTokenMutation] = useMutation(REFRESH_TOKEN_MUTATION, {
    onCompleted: (data) => {
      if (data.refreshToken) {
        const refreshData = data.refreshToken as { accessToken: string; refreshToken: string };
        setTokens(refreshData.accessToken, refreshData.refreshToken);
        setShowWarning(false);
        toast.success('Session extended successfully');
      }
    },
    onError: (error) => {
      console.error('Failed to refresh token:', error);
      toast.error('Failed to extend session. Please login again.');
      handleLogout();
    }
  });
  
  const [logoutMutation] = useMutation(LOGOUT_MUTATION, {
    onCompleted: () => {
      logoutStore();
      toast('Session expired. Please login again.');
    }
  });
  
  const parseJWT = (token: string) => {
    try {
      const base64Url = token.split('.')[1];
      const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
      const jsonPayload = decodeURIComponent(
        atob(base64)
          .split('')
          .map((c) => `%${  (`00${  c.charCodeAt(0).toString(16)}`).slice(-2)}`)
          .join('')
      );
      return JSON.parse(jsonPayload);
    } catch (error) {
      console.error('Error parsing JWT:', error);
      return null;
    }
  };
  
  const checkTokenExpiry = useCallback(() => {
    if (!accessToken) return;
    
    const payload = parseJWT(accessToken);
    if (!payload || !payload.exp) return;
    
    const now = Math.floor(Date.now() / 1000);
    const expiryTime = payload.exp;
    const timeUntilExpiry = expiryTime - now;
    
    // If token is already expired
    if (timeUntilExpiry <= 0) {
      handleLogout();
      return;
    }
    
    // If within warning time
    if (timeUntilExpiry <= warningTime && !showWarning) {
      setShowWarning(true);
      setTimeRemaining(timeUntilExpiry);
    }
    
    // Update time remaining if warning is showing
    if (showWarning) {
      setTimeRemaining(timeUntilExpiry);
    }
    
    // Auto-refresh if less than 1 minute remaining and not already refreshing
    if (timeUntilExpiry <= 60 && !isRefreshing && refreshToken) {
      handleRefreshToken();
    }
  }, [accessToken, refreshToken, warningTime, showWarning, isRefreshing]);
  
  const handleRefreshToken = async () => {
    if (!refreshToken || isRefreshing) return;
    
    setIsRefreshing(true);
    try {
      await refreshTokenMutation({
        variables: { refreshToken }
      });
    } finally {
      setIsRefreshing(false);
    }
  };
  
  const handleLogout = async () => {
    setShowWarning(false);
    await logoutMutation();
  };
  
  const handleContinue = () => {
    handleRefreshToken();
  };
  
  const formatTime = (seconds: number): string => {
    const minutes = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${minutes}:${secs.toString().padStart(2, '0')}`;
  };
  
  // Set up interval to check token expiry
  useEffect(() => {
    if (!user || !accessToken) return;
    
    // Initial check
    checkTokenExpiry();
    
    // Set up interval
    const interval = setInterval(checkTokenExpiry, checkInterval * 1000);
    
    return () => clearInterval(interval);
  }, [user, accessToken, checkTokenExpiry, checkInterval]);
  
  // Update countdown timer
  useEffect(() => {
    if (!showWarning || timeRemaining <= 0) return;
    
    const interval = setInterval(() => {
      setTimeRemaining((prev) => {
        if (prev <= 1) {
          handleLogout();
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
    
    return () => clearInterval(interval);
  }, [showWarning, timeRemaining]);
  
  // Activity tracking - reset warning on user activity.
  //
  // We keep the listener attached for the full duration of the warning and
  // read the latest `timeRemaining` via a ref, so we don't re-attach 4 event
  // listeners per second every time the countdown state updates.
  const timeRemainingRef = useRef(timeRemaining);
  useEffect(() => {
    timeRemainingRef.current = timeRemaining;
  }, [timeRemaining]);

  useEffect(() => {
    if (!showWarning) return;

    const handleActivity = () => {
      if (timeRemainingRef.current > 30) {
        handleRefreshToken();
      }
    };

    const events = ['mousedown', 'keydown', 'scroll', 'touchstart'];
    events.forEach(event => window.addEventListener(event, handleActivity));

    return () => {
      events.forEach(event => window.removeEventListener(event, handleActivity));
    };
  }, [showWarning]);
  
  if (!showWarning) return null;
  
  return (
    <>
      {/* Inline warning bar */}
      <div className="fixed top-0 left-0 right-0 z-40 bg-warning/10 border-b border-warning/30 px-4 py-2">
        <div className="flex items-center justify-between max-w-7xl mx-auto">
          <div className="flex items-center">
            <AlertTriangle className="h-5 w-5 text-warning mr-2" />
            <span className="text-sm text-warning">
              Your session will expire in {formatTime(timeRemaining)}
            </span>
          </div>
          <div className="flex items-center space-x-2">
            <button
              onClick={handleContinue}
              disabled={isRefreshing}
              className="px-3 py-1 text-sm bg-warning hover:bg-warning/90 text-warning-foreground rounded-md disabled:opacity-50"
            >
              {isRefreshing ? 'Extending...' : 'Extend Session'}
            </button>
            <button
              onClick={() => setShowWarning(false)}
              className="text-warning hover:text-warning/80"
            >
              <span className="sr-only">Dismiss</span>
              ×
            </button>
          </div>
        </div>
      </div>

      {/* Modal for critical warning (< 1 minute) */}
      {timeRemaining <= 60 && (
        <Modal
          isOpen={true}
          onClose={() => {}}
          title="Session Expiring Soon"
          className="max-w-md"
        >
          <div className="text-center py-6">
            <div className="mx-auto flex items-center justify-center h-16 w-16 rounded-full bg-warning/10 mb-4">
              <Clock className="h-8 w-8 text-warning" />
            </div>

            <h3 className="text-lg font-medium text-foreground mb-2">
              Your session is about to expire
            </h3>

            <div className="text-3xl font-bold text-destructive mb-4">
              {formatTime(timeRemaining)}
            </div>

            <p className="text-sm text-muted-foreground mb-6">
              For your security, you will be automatically logged out when the timer reaches zero.
              Click "Continue Session" to stay logged in.
            </p>

            <div className="flex flex-col sm:flex-row gap-3 justify-center">
              <button
                onClick={handleContinue}
                disabled={isRefreshing}
                className="inline-flex items-center justify-center px-4 py-2 border border-transparent text-sm font-medium rounded-md shadow-sm text-primary-foreground bg-primary hover:bg-primary-hover focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-primary disabled:opacity-50"
              >
                {isRefreshing ? (
                  <>
                    <RefreshCw className="animate-spin -ml-1 mr-2 h-4 w-4" />
                    Extending...
                  </>
                ) : (
                  <>
                    <RefreshCw className="-ml-1 mr-2 h-4 w-4" />
                    Continue Session
                  </>
                )}
              </button>

              <button
                onClick={handleLogout}
                className="inline-flex items-center justify-center px-4 py-2 border border-border text-sm font-medium rounded-md text-foreground bg-muted hover:bg-accent focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-primary"
              >
                <LogOut className="-ml-1 mr-2 h-4 w-4" />
                Log Out
              </button>
            </div>
          </div>
        </Modal>
      )}
    </>
  );
});
SessionTimeoutWarning.displayName = 'SessionTimeoutWarning';

export { SessionTimeoutWarning };