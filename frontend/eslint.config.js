import js from '@eslint/js';
import globals from 'globals';
import reactPlugin from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tsPlugin from '@typescript-eslint/eslint-plugin';
import tsParser from '@typescript-eslint/parser';
import prettierConfig from 'eslint-config-prettier';

export default [
  {
    // Global ignores: build output, dependencies, coverage, and config files.
    // Must be the first object so no earlier config can claim these files, and
    // patterns are **-prefixed so they match regardless of the base path
    // ESLint resolves for this config.
    ignores: ['**/dist/**', '**/coverage/**', '**/node_modules/**', '**/*.config.*'],
  },
  js.configs.recommended,
  prettierConfig,
  {
    files: ['**/*.{ts,tsx}'],
    plugins: {
      '@typescript-eslint': tsPlugin,
      'react': reactPlugin,
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        ecmaFeatures: { jsx: true },
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
      globals: {
        ...globals.browser,
        ...globals.es2022,
        ...globals.node,
        // JSX transform (react-jsx) doesn't need explicit React import
        React: 'writable',
        // Node.js types used in Timeout/Interval references
        NodeJS: 'readonly',
        // Three.js event listener options type
        EventListenerOptions: 'readonly',
      },
    },
    settings: {
      react: {
        version: 'detect',
      },
    },
    rules: {
      // TypeScript handles these better than ESLint base rules
      'no-undef': 'off',
      'no-unused-vars': 'off',

      ...reactHooks.configs.recommended.rules,
      'react/react-in-jsx-scope': 'off',
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-unused-vars': ['warn', {
        argsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
        caughtErrorsIgnorePattern: '^_',
      }],
      'prefer-template': 'warn',
      // Diagnostics use debug/info for connection and cache tracing.
      'no-console': ['warn', { allow: ['debug', 'info', 'warn', 'error'] }],
    },
  },
];
