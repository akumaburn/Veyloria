import React from 'react';
import { Link } from 'react-router-dom';
import { Home, ArrowLeft } from 'lucide-react';

const NotFoundPage: React.FC = () => {
  return (
    <div className="min-h-dvh flex items-center justify-center bg-background px-4 py-8">
      <div className="max-w-lg w-full text-center">
        <div className="mb-6 sm:mb-8">
          <h1 className="font-display text-8xl leading-none text-muted-foreground/30 sm:text-9xl">
            404
          </h1>
          <div className="mx-auto h-1 w-24 bg-gradient-to-r from-primary to-primary-hover rounded-full" />
        </div>

        <h2 className="text-2xl font-bold text-foreground mb-3 sm:mb-4 sm:text-3xl">
          Page Not Found
        </h2>

        <p className="text-muted-foreground mb-8">
          The page you're looking for doesn't exist or has been moved.
        </p>

        <div className="flex flex-col sm:flex-row gap-3 sm:gap-4 justify-center">
          <Link
            to="/"
            className="inline-flex items-center justify-center px-6 py-3 border border-transparent text-base font-medium rounded-xl text-primary-foreground bg-primary hover:bg-primary-hover focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-primary"
          >
            <Home className="-ml-1 mr-2 h-5 w-5" />
            Go Home
          </Link>

          <button
            type="button"
            onClick={() => window.history.back()}
            className="inline-flex items-center justify-center px-6 py-3 border border-border text-base font-medium rounded-xl text-foreground bg-muted hover:bg-accent focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-primary"
          >
            <ArrowLeft className="-ml-1 mr-2 h-5 w-5" />
            Go Back
          </button>
        </div>

        <div className="mt-8 pt-6 border-t border-border sm:mt-12 sm:pt-8">
          <p className="text-xs text-muted-foreground">
            If you believe this is an error, please{' '}
            <a href="mailto:support@veyloria.example.com" className="text-primary hover:text-primary-hover">
              contact support
            </a>
          </p>
        </div>
      </div>
    </div>
  );
};

export default NotFoundPage;