import React, { useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation } from '@apollo/client';
import { UserPlus, Check, X } from 'lucide-react';
import { useAuthStore } from '../../stores/authStore';
import { REGISTER_MUTATION } from '../../graphql/operations';
import { Input } from '../ui/Input';
import { Button } from '../ui/Button';
import { toast } from 'react-hot-toast';
import { passwordValidation, isValidEmail, PasswordStrength as PasswordStrengthScore } from '../../lib/utils';
import { useFormState } from '../../hooks/useFormState';

interface RegisterFormProps {
  onSuccess?: () => void;
  redirectTo?: string;
}

interface PasswordStrengthUI {
  score: PasswordStrengthScore;
  label: string;
  color: string;
}

export const RegisterForm: React.FC<RegisterFormProps> = ({
  onSuccess,
  redirectTo = '/chat'
}) => {
  const navigate = useNavigate();
  const formRef = useRef<HTMLFormElement>(null);
  const submitting = useRef(false);
  const { setAuth } = useAuthStore();

  const {
    formData,
    errors,
    handleChange: hookHandleChange,
    setFieldError,
    setFieldErrors,
    clearErrors,
  } = useFormState({
    username: '',
    email: '',
    password: '',
    confirmPassword: '',
  });

  const [passwordStrength, setPasswordStrength] = useState<PasswordStrengthUI>({
    score: 0,
    label: '',
    color: ''
  });
  const [agreedToTerms, setAgreedToTerms] = useState(false);
  const [generalError, setGeneralError] = useState<string | null>(null);
  const [termsError, setTermsError] = useState<string | null>(null);

  // Map strength score to UI properties
  const getStrengthUI = (score: PasswordStrengthScore): PasswordStrengthUI => {
    const colorMap: Record<PasswordStrengthScore, string> = {
      0: '',
      1: 'bg-destructive',
      2: 'bg-warning',
      3: 'bg-warning/80',
      4: 'bg-success'
    };
    return {
      score,
      label: passwordValidation.getStrengthLabel(score),
      color: colorMap[score]
    };
  };
  
  const [register, { loading }] = useMutation(REGISTER_MUTATION, {
    onCompleted: (data) => {
      if (data.register) {
        // Store auth data
        setAuth(
          data.register.user,
          data.register.accessToken,
          data.register.refreshToken
        );

        toast.success('Account created successfully!');

        // Callback or redirect
        if (onSuccess) {
          onSuccess();
        } else {
          navigate(redirectTo);
        }
      }
    },
    onError: (error) => {
      if (error.message.includes('already exists')) {
        if (error.message.includes('username')) {
          setFieldError('username', 'Username already taken');
        } else if (error.message.includes('email')) {
          setFieldError('email', 'Email already registered');
        } else {
          setGeneralError(error.message);
        }
      } else if (error.message.includes('Invalid email')) {
        setFieldError('email', 'Invalid email format');
      } else {
        setGeneralError(error.message);
      }
      toast.error('Registration failed');
    }
  });

  // Calculate password strength using utility function
  const calculatePasswordStrength = (password: string): PasswordStrengthUI => {
    const score = passwordValidation.calculateStrength(password);
    return getStrengthUI(score);
  };

  // Custom handleChange that also updates password strength
  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    hookHandleChange(e);

    // Update password strength
    if (e.target.name === 'password') {
      setPasswordStrength(calculatePasswordStrength(e.target.value));
    }
  };
  
  const validateForm = (): boolean => {
    const newErrors: Partial<Record<keyof typeof formData, string>> = {};
    let hasTermsError = false;

    // Username validation
    if (!formData.username.trim()) {
      newErrors.username = 'Username is required';
    } else if (formData.username.trim().length < 3) {
      newErrors.username = 'Username must be at least 3 characters';
    } else if (!/^[a-zA-Z0-9_]+$/.test(formData.username.trim())) {
      newErrors.username = 'Username can only contain letters, numbers, and underscores';
    }

    // Email validation - use shared utility
    if (!formData.email.trim()) {
      newErrors.email = 'Email is required';
    } else if (!isValidEmail(formData.email.trim())) {
      newErrors.email = 'Invalid email format';
    }

    // Password validation
    if (!formData.password) {
      newErrors.password = 'Password is required';
    } else if (formData.password.length < 8) {
      newErrors.password = 'Password must be at least 8 characters';
    }

    // Confirm password validation
    if (!formData.confirmPassword) {
      newErrors.confirmPassword = 'Please confirm your password';
    } else if (formData.password !== formData.confirmPassword) {
      newErrors.confirmPassword = 'Passwords do not match';
    }

    // Terms agreement
    if (!agreedToTerms) {
      setTermsError('You must agree to the terms and conditions');
      hasTermsError = true;
    } else {
      setTermsError(null);
    }

    if (Object.keys(newErrors).length > 0 || hasTermsError) {
      setFieldErrors(newErrors);
      formRef.current?.querySelector<HTMLInputElement>(`[name="${Object.keys(newErrors)[0] || 'terms'}"]`)?.focus();
      return false;
    }
    return true;
  };
  
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting.current || loading) return;
    
    clearErrors();
    setGeneralError(null);
    if (!validateForm()) return;
    submitting.current = true;
    try {
      await register({
        variables: {
          username: formData.username.trim(),
          email: formData.email.trim().toLowerCase(),
          password: formData.password
        }
      });
    } catch {
      // The mutation's onError callback presents field and form feedback.
    } finally {
      submitting.current = false;
    }
  };
  
  // Use shared passwordValidation utility to eliminate duplicate validation logic
  const passwordRequirements = passwordValidation.getRequirementStatus(formData.password);
  
  return (
    <div className="w-full">
      <form ref={formRef} onSubmit={handleSubmit} noValidate aria-label="Create account" aria-busy={loading} className="space-y-5">
        <p className="text-xs text-muted-foreground">All fields are required.</p>
        {generalError && (
          <div role="alert" className="bg-destructive/10 border border-destructive/30 text-destructive px-4 py-3 rounded-xl animate-fade-up">
            {generalError}
          </div>
        )}

        <div>
          <label htmlFor="username" className="block text-sm font-medium text-foreground mb-1.5">
            Username
          </label>
          <Input
            id="username"
            name="username"
            type="text"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            helperText="At least 3 characters. Use letters, numbers, and underscores."
            required
            value={formData.username}
            onChange={handleChange}
            error={errors.username}
            placeholder="e.g., alex_morgan"
            disabled={loading}
          />
        </div>

        <div>
          <label htmlFor="email" className="block text-sm font-medium text-foreground mb-1.5">
            Email Address
          </label>
          <Input
            id="email"
            name="email"
            type="email"
            autoComplete="email"
            autoCapitalize="none"
            spellCheck={false}
            required
            value={formData.email}
            onChange={handleChange}
            error={errors.email}
            placeholder="your@email.com"
            disabled={loading}
          />
        </div>

        <div>
          <label htmlFor="password" className="block text-sm font-medium text-foreground mb-1.5">
            Password
          </label>
          <Input
            id="password"
            name="password"
            type="password"
            autoComplete="new-password"
            required
            value={formData.password}
            onChange={handleChange}
            error={errors.password}
            helperText="Use at least 8 characters. A longer password is stronger."
            disabled={loading}
          />

          {formData.password && (
            <>
              <div className="mt-3">
                <div className="flex items-center justify-between mb-1.5">
                  <span className="text-xs text-muted-foreground">
                    Password strength:
                  </span>
                  <span className={`text-xs font-medium ${
                    passwordStrength.score <= 2 ? 'text-destructive' :
                    passwordStrength.score === 3 ? 'text-warning' :
                    'text-success'
                  }`}>
                    {passwordStrength.label}
                  </span>
                </div>
                <div className="h-2 bg-muted rounded-full overflow-hidden">
                  <div
                    className={`h-full transition-all duration-300 ${
                      passwordStrength.score <= 2 ? 'bg-destructive' :
                      passwordStrength.score === 3 ? 'bg-warning' :
                      'bg-success'
                    }`}
                    style={{ width: `${(passwordStrength.score / 4) * 100}%` }}
                  />
                </div>
              </div>

              <div className="mt-3 space-y-1.5">
                <p className="text-xs text-muted-foreground">Suggestions for a stronger password:</p>
                {passwordRequirements.filter(req => req.text !== 'At least 8 characters').map((req, idx) => (
                  <div key={idx} className="flex items-center text-xs">
                    {req.met ? (
                      <Check className="h-3.5 w-3.5 text-success mr-1.5" />
                    ) : (
                      <X className="h-3.5 w-3.5 text-muted-foreground mr-1.5" />
                    )}
                    <span className={req.met ? 'text-success' : 'text-muted-foreground'}>
                      {req.text}
                    </span>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>

        <div>
          <label htmlFor="confirmPassword" className="block text-sm font-medium text-foreground mb-1.5">
            Confirm Password
          </label>
          <Input
            id="confirmPassword"
            name="confirmPassword"
            type="password"
            autoComplete="new-password"
            required
            value={formData.confirmPassword}
            onChange={handleChange}
            error={errors.confirmPassword}
            disabled={loading}
          />
        </div>

        <div>
          <label className="flex items-start cursor-pointer">
            <input
              type="checkbox"
              name="terms"
              required
              disabled={loading}
              aria-invalid={!!termsError}
              aria-describedby={termsError ? 'registration-terms-error' : undefined}
              className="h-4 w-4 rounded border-border bg-muted/50 text-primary focus:ring-primary/20 mt-0.5"
              checked={agreedToTerms}
              onChange={(e) => {
                setAgreedToTerms(e.target.checked);
                setTermsError(null);
              }}
            />
            <span className="ml-2 text-sm text-muted-foreground">
              I agree to the{' '}
              <Link to="/terms" className="text-primary hover:text-primary-hover transition-colors">
                Terms and Conditions
              </Link>
              {' '}and{' '}
              <Link to="/privacy" className="text-primary hover:text-primary-hover transition-colors">
                Privacy Policy
              </Link>
            </span>
          </label>
          {termsError && (
            <p id="registration-terms-error" role="alert" className="mt-1.5 text-sm text-destructive">{termsError}</p>
          )}
        </div>

        <Button type="submit" isLoading={loading} loadingText="Creating account…" leftIcon={<UserPlus size={18} />} className="w-full">
          Create account
        </Button>

      </form>
    </div>
  );
};