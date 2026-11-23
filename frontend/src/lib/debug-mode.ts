/**
 * Module-level debug mode flag.
 * Set by App.tsx based on the backend `debugMode` GraphQL query.
 * Read by AuthProvider to conditionally allow /testmap without auth.
 */
export let isDebugMode = false;

export function setDebugMode(value: boolean) {
  isDebugMode = value;
}
