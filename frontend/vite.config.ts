import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'path';

// The dev server must run under Node, not Bun: `bun run dev` executes Vite with
// Bun's JS engine, and Bun's node:http compatibility layer breaks WebSocket
// upgrades through http-proxy (it destroys the client socket right after
// emitting `upgrade` and delivers the backend's 101 as a plain response), so
// /graphql WS subscriptions silently time out. package.json pins the dev script
// to `node .../vite.js` for this reason.

// The dev backend runs on 8300 by default. BACKEND_DEV_URL lets a second
// checkout run its backend on another port while the dev server stays on one
// origin.
const backendTarget = process.env.BACKEND_DEV_URL || 'http://127.0.0.1:8300';

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  server: {
    port: 3000,
    host: true,
    allowedHosts: ['rpg.local'],
    proxy: {
      '/tts': {
        target: process.env.TTS_DEV_URL || 'http://127.0.0.1:9797',
        changeOrigin: true,
        ws: true,
        rewrite: path => path.replace(/^\/tts/, ''),
        timeout: 600_000,
        proxyTimeout: 600_000,
      },
      // Both HTTP and WebSocket are served by SmallRye GraphQL on the same
      // /graphql path. Proxy them through the dev server so the frontend uses a
      // single origin (matching production behind Nginx) instead of connecting
      // to the backend port directly.
      '/graphql': {
        target: backendTarget,
        changeOrigin: true,
        ws: true,
        // Roster generation can run for many minutes; without a long timeout
        // the proxy would abort those HTTP queries mid-flight.
        timeout: 660_000,
        proxyTimeout: 660_000,
      },
      '/health': {
        target: backendTarget,
        changeOrigin: true,
      },
      '/metrics': {
        target: backendTarget,
        changeOrigin: true,
      },
      '/api': {
        target: backendTarget,
        changeOrigin: true,
      },
    },
  },
  build: {
    target: 'es2022',
    outDir: 'dist',
    assetsDir: 'assets',
    // Don't ship source maps to production (they leak internal source).
    sourcemap: process.env.NODE_ENV === 'development',
    rollupOptions: {
      output: {
        manualChunks: (id: string) => {
          if (id.includes('node_modules')) {
            if (id.includes('/react/') || id.includes('/react-dom/') || id.includes('/scheduler/') || id.includes('/react-is/')) {
              return 'react-vendor';
            }
            if (id.includes('react-router')) {
              return 'react-vendor';
            }
            if (id.includes('@apollo') || id.includes('/graphql/')) {
              return 'apollo-vendor';
            }
            if (id.includes('/three/') || id.includes('@react-three')) {
              return 'three-vendor';
            }
            if (id.includes('@radix-ui')) {
              return 'ui-vendor';
            }
            if (id.includes('zustand') || id.includes('immer')) {
              return 'state-vendor';
            }
            if (id.includes('lucide-react') ||
                id.includes('react-hot-toast') ||
                id.includes('react-markdown') ||
                id.includes('react-intersection-observer') ||
                id.includes('react-syntax-highlighter') ||
                id.includes('@emotion') ||
                id.includes('tailwind-merge') ||
                id.includes('highlight.js') ||
                id.includes('clsx')) {
              return 'ui-vendor';
            }
            if (id.includes('date-fns') || id.includes('js-base64') || id.includes('graphql-ws')) {
              return 'utils-vendor';
            }
          }
        },
        chunkFileNames: (chunkInfo: any) => {
          const facadeModuleId = chunkInfo.facadeModuleId ? chunkInfo.facadeModuleId.split('/').pop() : 'chunk';
          return `assets/${facadeModuleId}-[hash].js`;
        },
      },
    },
    chunkSizeWarningLimit: 600,
  },
  optimizeDeps: {
    include: [
      'react',
      'react-dom',
      '@apollo/client',
      'graphql',
      'zustand',
    ],
  },
});
