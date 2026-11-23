import { useMutation, DocumentNode } from '@apollo/client';
import { useNavigate } from 'react-router-dom';
import { toast } from 'react-hot-toast';
import { useAuthStore } from '../stores/authStore';

/**
 * useAuthMutation Hook
 *
 * Consolidates common authentication mutation patterns from LoginForm and RegisterForm.
 * Handles mutation execution, auth state updates, toasts, navigation, and error mapping.
 *
 * @example
 * const { mutate, loading } = useAuthMutation(LOGIN_MUTATION, {
 *   successMessage: 'Welcome back!',
 *   redirectTo: '/chat',
 *   mapError: (error) => {
 *     if (error.message.includes('Invalid credentials')) {
 *       return { field: 'password', message: 'Invalid username or password' };
 *     }
 *     return { field: 'general', message: error.message };
 *   }
 * });
 */

interface AuthMutationOptions {
  /**
   * Success toast message
   */
  successMessage?: string;

  /**
   * Path to redirect to after successful auth
   */
  redirectTo?: string;

  /**
   * Callback to execute on success (alternative to redirect)
   */
  onSuccess?: () => void;

  /**
   * Maps GraphQL errors to form field errors
   * Returns { field: string, message: string } or null for unmapped errors
   */
  mapError?: (error: Error) => { field: string; message: string } | null;
}

interface UseAuthMutationReturn {
  /**
   * Execute the mutation with variables
   */
  mutate: (variables: Record<string, any>) => Promise<void>;

  /**
   * Loading state
   */
  loading: boolean;

  /**
   * Error state (general errors not mapped to specific fields)
   */
  error: string | null;
}

export function useAuthMutation(
  mutation: DocumentNode,
  options: AuthMutationOptions = {}
): UseAuthMutationReturn {
  const navigate = useNavigate();
  const { setAuth } = useAuthStore();

  const {
    successMessage = 'Success!',
    redirectTo = '/chat',
    onSuccess,
    mapError,
  } = options;

  const [mutationFn, { loading, error: apolloError }] = useMutation(mutation, {
    onCompleted: (data) => {
      // Extract auth data from response (works for both login and register)
      const authData = data.login || data.register;

      if (authData) {
        // Store auth data
        setAuth(
          authData.user,
          authData.accessToken,
          authData.refreshToken
        );

        // Show success toast
        toast.success(successMessage);

        // Callback or redirect
        if (onSuccess) {
          onSuccess();
        } else {
          navigate(redirectTo);
        }
      }
    },
    onError: (error) => {
      // Map error if mapper provided, otherwise show generic error
      const mappedError = mapError ? mapError(error) : null;

      if (!mappedError || mappedError.field === 'general') {
        // Show toast for general errors
        toast.error('Authentication failed');
      }
    },
  });

  const mutate = async (variables: Record<string, any>) => {
    try {
      await mutationFn({ variables });
    } catch (error) {
      // Error handled by onError callback
      console.error('Auth mutation error:', error);
    }
  };

  return {
    mutate,
    loading,
    error: apolloError?.message || null,
  };
}

/**
 * Predefined error mappers for common authentication scenarios
 */
export const authErrorMappers = {
  /**
   * Login error mapper
   */
  login: (error: Error) => {
    if (error.message.includes('Invalid credentials')) {
      return { field: 'password', message: 'Invalid username or password' };
    }
    if (error.message.includes('User not found')) {
      return { field: 'username', message: 'User not found' };
    }
    if (error.message.includes('Account locked')) {
      return { field: 'general', message: 'Account locked. Please contact support.' };
    }
    return { field: 'general', message: error.message };
  },

  /**
   * Register error mapper
   */
  register: (error: Error) => {
    if (error.message.includes('username') && error.message.includes('already exists')) {
      return { field: 'username', message: 'Username already taken' };
    }
    if (error.message.includes('email') && error.message.includes('already exists')) {
      return { field: 'email', message: 'Email already registered' };
    }
    if (error.message.includes('Invalid email')) {
      return { field: 'email', message: 'Invalid email format' };
    }
    return { field: 'general', message: error.message };
  },
};
