import React from 'react';
import { Link } from 'react-router-dom';
import { LoginForm } from '../components/auth/LoginForm';
import { AuthShell } from '../components/auth/AuthShell';

const LoginPage: React.FC = () => {
  return (
    <AuthShell
      title="Welcome back"
      subtitle="Sign in to continue to Veyloria"
      footerPrompt="New to Veyloria?"
      footerLink={{ to: '/register', label: 'Create an account' }}
      legal={
        <>
          By using Veyloria, you agree to our{' '}
          <Link to="/terms" className="underline transition-colors hover:text-foreground">
            Terms of Service
          </Link>{' '}
          and{' '}
          <Link to="/privacy" className="underline transition-colors hover:text-foreground">
            Privacy Policy
          </Link>
        </>
      }
    >
      <LoginForm />
    </AuthShell>
  );
};

export default LoginPage;
