interface ImportMetaEnv {
  DEV: boolean;
  PROD: boolean;
  MODE: string;
  VITE_API_URL?: string;
  VITE_GRAPHQL_ENDPOINT?: string;
  VITE_WS_URL?: string;
  VITE_WS_ENDPOINT?: string;
  VITE_ERROR_TRACKING_ENDPOINT?: string;
}

interface ImportMeta {
  hot?: {
    dispose(cb: () => void): void;
  };
}

function getMeta(): ImportMeta & { env: ImportMetaEnv } {
  // import.meta is typed by the GLOBAL ImportMeta (lib), which doesn't declare
  // `env` (only the Vite-augmented one does). Cast to check for env at runtime
  // without a type error, so this module works under Vite and in plain tsc.
  const raw = import.meta as unknown as { env?: ImportMetaEnv };
  if (typeof raw.env === 'undefined') {
    return { env: { DEV: false, PROD: true, MODE: 'production' } } as never;
  }
  return import.meta as never;
}

export const meta = getMeta();

export const isDev = meta.env.DEV;
export const isProd = meta.env.PROD;
export const mode = meta.env.MODE;

export const apiUrl = meta.env.VITE_API_URL || meta.env.VITE_GRAPHQL_ENDPOINT || '/graphql';
export const wsUrl = meta.env.VITE_WS_URL || undefined;

export const hot = meta.hot ?? null;
