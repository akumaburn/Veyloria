import { Component, ErrorInfo, ReactNode } from 'react';
import { AlertTriangle, RefreshCw, Home, Bug, ChevronDown, ChevronUp } from 'lucide-react';
import { cn } from '@/lib/utils';
import { meta } from '@/lib/env';

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
  onError?: (error: Error, errorInfo: ErrorInfo) => void;
  resetKeys?: Array<string | number>;
  resetOnPropsChange?: boolean;
}

interface State {
  hasError: boolean;
  error: Error | null;
  errorInfo: ErrorInfo | null;
  errorCount: number;
  showDetails: boolean;
}

export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = {
      hasError: false,
      error: null,
      errorInfo: null,
      errorCount: 0,
      showDetails: false,
    };
  }

  static getDerivedStateFromError(error: Error): Partial<State> {
    return {
      hasError: true,
      error,
    };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('ErrorBoundary caught an error:', error, errorInfo);
    
    this.setState(prevState => ({
      errorInfo,
      errorCount: prevState.errorCount + 1,
    }));

    // Call the optional error handler
    if (this.props.onError) {
      this.props.onError(error, errorInfo);
    }

    // Send error to monitoring service
    this.logErrorToService(error, errorInfo);
  }

  componentDidUpdate(prevProps: Props) {
    const { resetKeys, resetOnPropsChange } = this.props;
    const { hasError } = this.state;
    
    if (hasError) {
      // Reset on prop changes if enabled
      if (resetOnPropsChange && prevProps.children !== this.props.children) {
        this.resetErrorBoundary();
      }
      
      // Reset if resetKeys changed
      if (resetKeys && prevProps.resetKeys) {
        const hasResetKeyChanged = resetKeys.some(
          (key, index) => key !== prevProps.resetKeys![index]
        );
        if (hasResetKeyChanged) {
          this.resetErrorBoundary();
        }
      }
    }
  }

  logErrorToService = async (error: Error, errorInfo: ErrorInfo) => {
    // Send to error tracking service
    const errorData = {
      message: error.message,
      stack: error.stack,
      componentStack: errorInfo.componentStack,
      timestamp: new Date().toISOString(),
      userAgent: navigator.userAgent,
      url: window.location.href,
      sessionId: localStorage.getItem('sessionId') || 'anonymous',
      userId: localStorage.getItem('userId') || null,
      environment: meta.env.MODE || 'development',
    };
    
    // Log to console in development
    if (meta.env.DEV) {
      console.error('Error captured:', errorData);
    }
    
    // Send to error tracking service
    try {
      // Option 1: Send to backend endpoint
      if (meta.env.VITE_ERROR_TRACKING_ENDPOINT) {
        await fetch(meta.env.VITE_ERROR_TRACKING_ENDPOINT, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify(errorData),
        });
      }
      
      // Option 2: Use Sentry if configured
      if (typeof window !== 'undefined' && (window as any).Sentry) {
        (window as any).Sentry.captureException(error, {
          contexts: {
            react: {
              componentStack: errorInfo.componentStack,
            },
          },
          extra: errorData,
        });
      }
      
      // Option 3: Store in localStorage for later submission
      const storedErrors = JSON.parse(localStorage.getItem('errorLog') || '[]');
      storedErrors.push(errorData);
      // Keep only last 10 errors
      if (storedErrors.length > 10) {
        storedErrors.shift();
      }
      localStorage.setItem('errorLog', JSON.stringify(storedErrors));
    } catch (trackingError) {
      // Silently fail if error tracking fails
      console.error('Failed to track error:', trackingError);
    }
  };

  resetErrorBoundary = () => {
    this.setState({
      hasError: false,
      error: null,
      errorInfo: null,
      showDetails: false,
    });
  };

  toggleDetails = () => {
    this.setState(prevState => ({
      showDetails: !prevState.showDetails,
    }));
  };

  render() {
    const { hasError, error, errorInfo, errorCount, showDetails } = this.state;
    const { children, fallback } = this.props;

    if (hasError && error) {
      // Use custom fallback if provided
      if (fallback) {
        return <>{fallback}</>;
      }

      // Default error UI
      return (
        <div className="flex min-h-screen items-center justify-center bg-background p-4">
          <div className="w-full max-w-2xl">
            <div className="rounded-lg border border-destructive/30 bg-card p-6">
              {/* Error Header */}
              <div className="flex items-start space-x-3">
                <div className="rounded-lg bg-destructive/10 p-2">
                  <AlertTriangle className="h-6 w-6 text-destructive" />
                </div>
                <div className="flex-1">
                  <h1 className="text-xl font-semibold text-foreground">
                    Something went wrong
                  </h1>
                  <p className="mt-1 text-sm text-muted-foreground">
                    An unexpected error occurred while rendering this page.
                  </p>
                </div>
              </div>

              {/* Error Message */}
              <div className="mt-6 rounded-lg bg-muted/50 p-4">
                <p className="font-mono text-sm text-destructive">
                  {error.message || 'Unknown error'}
                </p>
                {errorCount > 1 && (
                  <p className="mt-2 text-xs text-muted-foreground">
                    This error has occurred {errorCount} times
                  </p>
                )}
              </div>

              {/* Error Details */}
              <div className="mt-4">
                <button
                  onClick={this.toggleDetails}
                  className="flex items-center space-x-2 text-sm text-muted-foreground hover:text-foreground"
                >
                  {showDetails ? (
                    <ChevronUp size={16} />
                  ) : (
                    <ChevronDown size={16} />
                  )}
                  <span>{showDetails ? 'Hide' : 'Show'} technical details</span>
                </button>

                {showDetails && (
                  <div className="mt-3 space-y-3">
                    {/* Stack Trace */}
                    {error.stack && (
                      <div className="rounded-lg bg-muted p-3">
                        <p className="mb-2 text-xs font-medium text-muted-foreground">
                          Stack Trace:
                        </p>
                        <pre className="overflow-x-auto text-xs text-muted-foreground/80">
                          {error.stack}
                        </pre>
                      </div>
                    )}

                    {/* Component Stack */}
                    {errorInfo?.componentStack && (
                      <div className="rounded-lg bg-muted p-3">
                        <p className="mb-2 text-xs font-medium text-muted-foreground">
                          Component Stack:
                        </p>
                        <pre className="overflow-x-auto text-xs text-muted-foreground/80">
                          {errorInfo.componentStack}
                        </pre>
                      </div>
                    )}
                  </div>
                )}
              </div>

              {/* Actions */}
              <div className="mt-6 flex flex-wrap gap-3">
                <button
                  onClick={this.resetErrorBoundary}
                  className="flex items-center space-x-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary-hover"
                >
                  <RefreshCw size={16} />
                  <span>Try Again</span>
                </button>

                <button
                  onClick={() => window.location.href = '/'}
                  className="flex items-center space-x-2 rounded-lg bg-muted px-4 py-2 text-sm font-medium text-foreground hover:bg-accent"
                >
                  <Home size={16} />
                  <span>Go Home</span>
                </button>

                <button
                  onClick={() => window.location.reload()}
                  className="flex items-center space-x-2 rounded-lg border border-border px-4 py-2 text-sm font-medium text-muted-foreground hover:bg-muted"
                >
                  <RefreshCw size={16} />
                  <span>Reload Page</span>
                </button>
              </div>

              {/* Report Issue */}
              <div className="mt-6 rounded-lg bg-muted/30 p-4">
                <div className="flex items-start space-x-2">
                  <Bug size={16} className="mt-0.5 text-muted-foreground" />
                  <div className="flex-1 text-xs text-muted-foreground">
                    <p>
                      If this problem persists, please report it to our support team
                      with the error details above.
                    </p>
                    <a
                      href="#"
                      className="mt-1 inline-block text-primary hover:text-primary-hover"
                    >
                      Report this issue →
                    </a>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      );
    }

    return children;
  }
}

// Simplified error boundary for specific components
interface SimpleErrorBoundaryProps {
  children: ReactNode;
  fallbackMessage?: string;
  className?: string;
}

export const SimpleErrorBoundary = ({
  children,
  fallbackMessage = 'Failed to load this component',
  className
}: SimpleErrorBoundaryProps) => {
  return (
    <ErrorBoundary
      fallback={
        <div className={cn(
          'flex items-center justify-center rounded-lg border border-border bg-card/50 p-8',
          className
        )}>
          <div className="text-center">
            <AlertTriangle className="mx-auto mb-3 h-8 w-8 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">{fallbackMessage}</p>
            <button
              onClick={() => window.location.reload()}
              className="mt-3 text-xs text-primary hover:text-primary-hover"
            >
              Reload page
            </button>
          </div>
        </div>
      }
    >
      {children}
    </ErrorBoundary>
  );
};

// Hook to trigger error boundary
export const useErrorHandler = () => {
  return (error: Error) => {
    throw error;
  };
};

// HOC to wrap components with error boundary
export function withErrorBoundary<P extends object>(
  Component: React.ComponentType<P>,
  errorBoundaryProps?: Omit<Props, 'children'>
) {
  const WrappedComponent = (props: P) => (
    <ErrorBoundary {...errorBoundaryProps}>
      <Component {...props} />
    </ErrorBoundary>
  );
  
  WrappedComponent.displayName = `withErrorBoundary(${Component.displayName || Component.name})`;
  
  return WrappedComponent;
}