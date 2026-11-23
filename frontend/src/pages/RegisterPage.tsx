import React from 'react';
import { Link } from 'react-router-dom';
import { RegisterForm } from '../components/auth/RegisterForm';
import { AuthShell } from '../components/auth/AuthShell';

const RegisterPage: React.FC = () => {
  return (
    <AuthShell
      title="Create account"
      subtitle="Join Veyloria and start exploring"
      footerPrompt="Already have an account?"
      footerLink={{ to: '/login', label: 'Sign in instead' }}
      legal={
        <>
          By creating an account, you agree to our{' '}
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
      <RegisterForm />
    </AuthShell>
  );
};

export default RegisterPage;
