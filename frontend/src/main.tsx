import React from 'react';
import ReactDOM from 'react-dom/client';
import { enableMapSet } from 'immer';
import App from './App';
import './index.css';

// Enable Immer MapSet plugin for stores that use Map/Set
enableMapSet();

// Declare React DevTools global hook type
declare global {
  interface Window {
    __REACT_DEVTOOLS_GLOBAL_HOOK__?: {
      supportsFiber: boolean;
      inject: () => void;
      onCommitFiberRoot: () => void;
      onCommitFiberUnmount: () => void;
    };
  }
}

// VITE_API_URL / VITE_WS_URL are optional overrides. The defaults
// (`/graphql` for HTTP, `wss?://{window.location.host}/graphql` for WS) work
// under both `bun run dev` (via the Vite proxy) and the production Docker
// image (via the Nginx sidecar). Only set them when the frontend must talk
// to a backend on a different origin.

import { isDev } from '@/lib/env';

// Enable React DevTools in development
if (isDev) {
  window.__REACT_DEVTOOLS_GLOBAL_HOOK__ = {
    supportsFiber: true,
    inject: () => {},
    onCommitFiberRoot: () => {},
    onCommitFiberUnmount: () => {},
  };
}

// Service Worker - DISABLED to prevent auth state caching issues
// Unregister any existing service workers
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.getRegistrations().then((registrations) => {
      for (const registration of registrations) {
        registration.unregister();
      }
    });
  });
}

// Render the app
const root = ReactDOM.createRoot(
  document.getElementById('root') as HTMLElement
);

root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);