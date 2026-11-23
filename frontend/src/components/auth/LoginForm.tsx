import React, { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation } from '@apollo/client';
import { LogIn } from 'lucide-react';
import { useAuthStore } from '../../stores/authStore';
import { LOGIN_MUTATION } from '../../graphql/operations';
import { Input } from '../ui/Input';
import { Button } from '../ui/Button';
import { toast } from 'react-hot-toast';
import { useFormState } from '../../hooks/useFormState';

interface LoginFormProps {
  onSuccess?: () => void;
  redirectTo?: string;
}

export const LoginForm: React.FC<LoginFormProps> = ({ onSuccess, redirectTo = '/chat' }) => {
  const navigate = useNavigate();
  const { setAuth } = useAuthStore();
  const formRef = useRef<HTMLFormElement>(null);
  const submitting = useRef(false);
  const { formData, errors, handleChange, setFieldErrors, clearErrors } = useFormState({
    username: '',
    password: '',
  });
  const [generalError, setGeneralError] = useState<string | null>(null);
  const [login, { loading }] = useMutation(LOGIN_MUTATION, {
    onCompleted: (data) => {
      if (!data.login) return;
      setAuth(data.login.user, data.login.accessToken, data.login.refreshToken);
      toast.success('Welcome back!');
      if (onSuccess) onSuccess();
      else navigate(redirectTo);
    },
  });

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (submitting.current || loading) return;
    clearErrors();
    setGeneralError(null);
    const nextErrors: typeof errors = {};
    if (!formData.username.trim()) nextErrors.username = 'Enter your username or email.';
    if (!formData.password) nextErrors.password = 'Enter your password.';
    if (Object.keys(nextErrors).length) {
      setFieldErrors(nextErrors);
      formRef.current
        ?.querySelector<HTMLInputElement>(`[name="${Object.keys(nextErrors)[0]}"]`)
        ?.focus();
      return;
    }
    submitting.current = true;
    try {
      await login({
        variables: { username: formData.username.trim(), password: formData.password },
      });
    } catch (error) {
      const message =
        error instanceof Error ? error.message : 'Unable to sign in. Please try again.';
      setGeneralError(
        /Invalid credentials|User not found/i.test(message)
          ? 'The username or password is incorrect. Check both fields and try again.'
          : /Account locked/i.test(message)
            ? 'Account locked. Please contact support.'
            : message,
      );
    } finally {
      submitting.current = false;
    }
  };

  return (
    <form
      ref={formRef}
      onSubmit={handleSubmit}
      noValidate
      aria-label="Sign in"
      aria-busy={loading}
      className="space-y-5"
    >
      {generalError && (
        <p
          role="alert"
          className="border-destructive/30 bg-destructive/10 text-destructive rounded-xl border px-4 py-3 text-sm"
        >
          {generalError}
        </p>
      )}
      <Input
        label="Username or email"
        name="username"
        type="text"
        autoComplete="username"
        autoCapitalize="none"
        spellCheck={false}
        required
        value={formData.username}
        onChange={handleChange}
        error={errors.username}
        disabled={loading}
      />
      <Input
        label="Password"
        name="password"
        type="password"
        autoComplete="current-password"
        required
        value={formData.password}
        onChange={handleChange}
        error={errors.password}
        disabled={loading}
      />
      <Button
        type="submit"
        isLoading={loading}
        loadingText="Signing in…"
        leftIcon={<LogIn size={18} />}
        className="w-full"
      >
        Sign in
      </Button>
    </form>
  );
};
