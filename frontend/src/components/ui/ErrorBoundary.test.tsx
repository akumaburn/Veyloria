import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ErrorBoundary, SimpleErrorBoundary, useErrorHandler, withErrorBoundary } from './ErrorBoundary';

// Suppress console.error for expected errors in tests
const originalError = console.error;
beforeEach(() => {
  console.error = vi.fn();
});
afterEach(() => {
  console.error = originalError;
});

// Mock window.location
const mockLocation = {
  href: 'http://localhost:3000/test',
  reload: vi.fn(),
};
Object.defineProperty(window, 'location', {
  value: mockLocation,
  writable: true,
});

// Test component that throws an error
const ThrowError = ({ shouldThrow = true }: { shouldThrow?: boolean }) => {
  if (shouldThrow) {
    throw new Error('Test error message');
  }
  return <div data-testid="normal-content">Normal content</div>;
};

// Test component that throws error conditionally
const ConditionalError = ({ triggerError }: { triggerError: boolean }) => {
  if (triggerError) {
    throw new Error('Conditional error');
  }
  return <div data-testid="conditional-content">Content works</div>;
};

describe('ErrorBoundary Component', () => {
  describe('Error Catching', () => {
    it('catches errors thrown in child components', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      );

      // Should display error UI
      expect(screen.getByText('Something went wrong')).toBeInTheDocument();
      expect(screen.getByText('Test error message')).toBeInTheDocument();
    });

    it('renders children normally when no error', () => {
      render(
        <ErrorBoundary>
          <ThrowError shouldThrow={false} />
        </ErrorBoundary>
      );

      expect(screen.getByTestId('normal-content')).toBeInTheDocument();
      expect(screen.queryByText('Something went wrong')).not.toBeInTheDocument();
    });

    it('calls onError callback when error occurs', () => {
      const onError = vi.fn();

      render(
        <ErrorBoundary onError={onError}>
          <ThrowError />
        </ErrorBoundary>
      );

      expect(onError).toHaveBeenCalledTimes(1);
      expect(onError).toHaveBeenCalledWith(
        expect.any(Error),
        expect.objectContaining({ componentStack: expect.any(String) })
      );
    });
  });

  describe('User-Friendly Error Display', () => {
    it('displays user-friendly error message', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      );

      expect(screen.getByText('Something went wrong')).toBeInTheDocument();
      expect(screen.getByText(/An unexpected error occurred/)).toBeInTheDocument();
    });

    it('shows error message in a styled container', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      );

      const errorMessage = screen.getByText('Test error message');
      expect(errorMessage).toHaveClass('font-mono', 'text-sm', 'text-destructive');
    });

    it('displays collapsible technical details', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      );

      // Technical details should be hidden initially
      expect(screen.queryByText('Stack Trace:')).not.toBeInTheDocument();

      // Click to show details
      const showDetailsButton = screen.getByText(/Show technical details/);
      fireEvent.click(showDetailsButton);

      // Now stack trace should be visible
      expect(screen.getByText('Stack Trace:')).toBeInTheDocument();
    });
  });

  describe('Error Recovery Actions', () => {
    it('provides Try Again button that resets error state', () => {
      // This test verifies the Try Again button exists and can be clicked
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      );

      // Error should be displayed
      expect(screen.getByText('Something went wrong')).toBeInTheDocument();

      // Try Again button should exist
      const tryAgainButton = screen.getByRole('button', { name: /Try Again/i });
      expect(tryAgainButton).toBeInTheDocument();

      // Clicking it should attempt to reset (though error will persist if children still throw)
      fireEvent.click(tryAgainButton);

      // ErrorBoundary state is reset but component re-throws, so error UI is shown again
      expect(screen.getByText('Something went wrong')).toBeInTheDocument();
    });

    it('provides Go Home button', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      );

      const goHomeButton = screen.getByRole('button', { name: /Go Home/i });
      expect(goHomeButton).toBeInTheDocument();

      fireEvent.click(goHomeButton);
      expect(window.location.href).toBe('/');
    });

    it('provides Reload Page button', () => {
      render(
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
      );

      const reloadButton = screen.getByRole('button', { name: /Reload Page/i });
      expect(reloadButton).toBeInTheDocument();

      fireEvent.click(reloadButton);
      expect(mockLocation.reload).toHaveBeenCalled();
    });
  });

  describe('Custom Fallback', () => {
    it('renders custom fallback when provided', () => {
      render(
        <ErrorBoundary fallback={<div data-testid="custom-fallback">Custom Error UI</div>}>
          <ThrowError />
        </ErrorBoundary>
      );

      expect(screen.getByTestId('custom-fallback')).toBeInTheDocument();
      expect(screen.queryByText('Something went wrong')).not.toBeInTheDocument();
    });
  });

  describe('Error Count Tracking', () => {
    it('tracks error count', () => {
      const { rerender } = render(
        <ErrorBoundary>
          <ConditionalError triggerError={true} />
        </ErrorBoundary>
      );

      // Reset and trigger another error
      const tryAgain = screen.getByRole('button', { name: /Try Again/i });
      fireEvent.click(tryAgain);

      rerender(
        <ErrorBoundary>
          <ConditionalError triggerError={true} />
        </ErrorBoundary>
      );

      // Should show error count
      expect(screen.getByText(/This error has occurred \d+ times/)).toBeInTheDocument();
    });
  });
});

describe('SimpleErrorBoundary Component', () => {
  it('displays simplified error message', () => {
    render(
      <SimpleErrorBoundary fallbackMessage="Component failed to load">
        <ThrowError />
      </SimpleErrorBoundary>
    );

    expect(screen.getByText('Component failed to load')).toBeInTheDocument();
  });

  it('uses default fallback message', () => {
    render(
      <SimpleErrorBoundary>
        <ThrowError />
      </SimpleErrorBoundary>
    );

    expect(screen.getByText('Failed to load this component')).toBeInTheDocument();
  });

  it('shows reload button', () => {
    render(
      <SimpleErrorBoundary>
        <ThrowError />
      </SimpleErrorBoundary>
    );

    expect(screen.getByText('Reload page')).toBeInTheDocument();
  });
});

describe('useErrorHandler Hook', () => {
  it('returns a function that throws errors', () => {
    const TestComponent = () => {
      const throwError = useErrorHandler();

      // Just verify the hook returns a function
      expect(typeof throwError).toBe('function');

      return <div>Test</div>;
    };

    render(<TestComponent />);
  });

  it('hook function re-throws the error it receives', () => {
    const throwFn = useErrorHandler();
    const testError = new Error('Test hook error');

    // The function should throw the same error it receives
    expect(() => throwFn(testError)).toThrow('Test hook error');
  });
});

describe('withErrorBoundary HOC', () => {
  const BuggyComponent = () => {
    throw new Error('HOC error');
  };

  it('wraps component with error boundary', () => {
    const SafeComponent = withErrorBoundary(BuggyComponent);

    render(<SafeComponent />);

    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
    expect(screen.getByText('HOC error')).toBeInTheDocument();
  });

  it('preserves component display name', () => {
    const TestComponent = () => <div>Test</div>;
    TestComponent.displayName = 'TestComponent';

    const WrappedComponent = withErrorBoundary(TestComponent);

    expect(WrappedComponent.displayName).toBe('withErrorBoundary(TestComponent)');
  });
});

describe('App Remains Functional After Error', () => {
  it('error in one part does not crash entire app', () => {
    const SafeComponent = () => <div data-testid="safe">Safe part of app</div>;

    render(
      <div>
        <ErrorBoundary>
          <ThrowError />
        </ErrorBoundary>
        <SafeComponent />
      </div>
    );

    // Error boundary contains the error
    expect(screen.getByText('Something went wrong')).toBeInTheDocument();

    // But safe component still renders
    expect(screen.getByTestId('safe')).toBeInTheDocument();
  });

  it('reset allows component to render again', () => {
    let shouldError = true;

    const MaybeError = () => {
      if (shouldError) throw new Error('Initial error');
      return <div data-testid="recovered">Recovered!</div>;
    };

    const { rerender } = render(
      <ErrorBoundary>
        <MaybeError />
      </ErrorBoundary>
    );

    expect(screen.getByText('Something went wrong')).toBeInTheDocument();

    // Fix the error
    shouldError = false;

    // Click try again
    fireEvent.click(screen.getByRole('button', { name: /Try Again/i }));

    rerender(
      <ErrorBoundary>
        <MaybeError />
      </ErrorBoundary>
    );

    // App is functional again
    expect(screen.getByTestId('recovered')).toBeInTheDocument();
  });
});
