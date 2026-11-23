import { type ClassValue, clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * Normalize a timestamp string from the server:
 * 1. Truncates nanoseconds to milliseconds (JS Date only handles 3 digits)
 * 2. Fills in missing seconds and milliseconds
 * 3. Ensures a UTC timezone suffix (Z), because the server stores all
 *    timestamps as UTC but Java's LocalDateTime.toString() omits the Z and
 *    drops a zero seconds field.
 *
 * Accepts formats produced by:
 * - LocalDateTime.toString() -> "2024-01-15T10:30", "2024-01-15T10:30:45",
 *   or "2024-01-15T10:30:45.123456789"
 * - Instant.toString()       -> "2024-01-15T10:30:45.123456789Z"
 *
 * Returns a UTC ISO string parseable by new Date() in all environments. An
 * unparseable value returns the epoch instead of an invalid date, so a bad
 * row cannot crash every screen that formats a relative time.
 */
export function normalizeTimestamp(ts: string | undefined | null): string {
  if (!ts) return '1970-01-01T00:00:00.000Z';

  let value = ts.trim().replace(' ', 'T');
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    value += 'T00:00:00.000Z';
  }

  const zoneMatch = value.match(/(Z|[+-]\d{2}:?\d{2})$/);
  const zone = zoneMatch ? zoneMatch[1] : 'Z';
  let body = zoneMatch ? value.slice(0, -zone.length) : value;

  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(body)) {
    body += ':00';
  }
  body = body.replace(
    /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}).*$/,
    '$1'
  );
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(body)) {
    body += '.000';
  }

  const normalized = body + zone;
  const ms = new Date(normalized).getTime();
  return isNaN(ms) ? '1970-01-01T00:00:00.000Z' : normalized;
}

/**
 * Parse a server timestamp to epoch milliseconds.
 * Returns 0 for null/undefined/unparseable values.
 */
export function toTimestamp(ts: string | undefined | null): number {
  const normalized = normalizeTimestamp(ts);
  const ms = new Date(normalized).getTime();
  return isNaN(ms) ? 0 : ms;
}

function getTimezoneSuffix(d: Date): string {
  try {
    const offset = -d.getTimezoneOffset();
    const sign = offset >= 0 ? '+' : '-';
    const hours = String(Math.floor(Math.abs(offset) / 60)).padStart(2, '0');
    const minutes = String(Math.abs(offset) % 60).padStart(2, '0');
    return `GMT${sign}${hours}:${minutes}`;
  } catch {
    return '';
  }
}

export function formatDate(date: string | Date): string {
  const d = typeof date === 'string' ? new Date(normalizeTimestamp(date)) : date;
  const ms = d.getTime();
  if (isNaN(ms)) return '';
  const now = Date.now();
  const diff = now - ms;
  const seconds = Math.floor(diff / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);

  if (seconds < 60) return 'just now';
  if (minutes < 60) return `${minutes} minute${minutes > 1 ? 's' : ''} ago`;
  if (hours < 24) return `${hours} hour${hours > 1 ? 's' : ''} ago`;
  if (days < 7) return `${days} day${days > 1 ? 's' : ''} ago`;

  return `${d.toLocaleString(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  })} ${getTimezoneSuffix(d)}`;
}

export function truncateText(text: string, maxLength: number): string {
  if (text.length <= maxLength) return text;
  return `${text.substring(0, maxLength)}...`;
}

export function generateId(): string {
  return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
}

export function debounce<T extends (...args: unknown[]) => void>(
  func: T,
  wait: number
): (...args: Parameters<T>) => void {
  let timeoutId: ReturnType<typeof setTimeout> | null = null;
  
  return (...args: Parameters<T>) => {
    if (timeoutId !== null) {
      clearTimeout(timeoutId);
    }
    
    timeoutId = setTimeout(() => {
      func(...args);
    }, wait);
  };
}

export function throttle<T extends (...args: unknown[]) => void>(
  func: T,
  limit: number
): (...args: Parameters<T>) => void {
  let inThrottle = false;
  
  return (...args: Parameters<T>) => {
    if (!inThrottle) {
      func(...args);
      inThrottle = true;
      setTimeout(() => {
        inThrottle = false;
      }, limit);
    }
  };
}

/**
 * Get consistent participant color index based on ID.
 * Uses position-weighted character sum for good distribution across 8 color indices.
 * Same ID will always return the same color index.
 */
export function getParticipantColorIndex(id: string | undefined | null): number {
  if (!id) return 1;

  // Position-weighted character sum provides better distribution for UUIDs
  // than traditional hash functions which can collide on similar hex strings
  let sum = 0;
  for (let i = 0; i < id.length; i++) {
    sum += id.charCodeAt(i) * (i + 1);
  }

  // Map to 1-8 range (we have 8 participant colors)
  return (sum % 8) + 1;
}

/**
 * Get the CSS class for participant message background color.
 * Returns Tailwind classes for background and border based on participant color index.
 */
export function getParticipantColorClasses(id: string | undefined | null): {
  bg: string;
  border: string;
  accent: string;
} {
  const index = getParticipantColorIndex(id);

  // Map index to Tailwind classes using the theme colors defined in tailwind.config.js
  // These are theme-aware (different values for light/dark mode)
  const colorMap: Record<number, { bg: string; border: string; accent: string }> = {
    1: { bg: 'bg-participant-1-bg', border: 'border-participant-1/30', accent: 'text-participant-1' },
    2: { bg: 'bg-participant-2-bg', border: 'border-participant-2/30', accent: 'text-participant-2' },
    3: { bg: 'bg-participant-3-bg', border: 'border-participant-3/30', accent: 'text-participant-3' },
    4: { bg: 'bg-participant-4-bg', border: 'border-participant-4/30', accent: 'text-participant-4' },
    5: { bg: 'bg-participant-5-bg', border: 'border-participant-5/30', accent: 'text-participant-5' },
    6: { bg: 'bg-participant-6-bg', border: 'border-participant-6/30', accent: 'text-participant-6' },
    7: { bg: 'bg-participant-7-bg', border: 'border-participant-7/30', accent: 'text-participant-7' },
    8: { bg: 'bg-participant-8-bg', border: 'border-participant-8/30', accent: 'text-participant-8' },
  };

  return colorMap[index] || colorMap[1];
}

// Note: Toast notifications are now handled by react-hot-toast.
// Import { toast } from 'react-hot-toast' in components instead.

// ================================================================
// Error Handling Utilities
// ================================================================

/**
 * Extract error message from unknown error type.
 * Consolidates the repeated pattern: error instanceof Error ? error.message : 'fallback'
 *
 * @param error - The error to extract message from
 * @param fallback - Fallback message if error is not an Error instance
 * @returns The error message string
 */
export function getErrorMessage(error: unknown, fallback: string = 'An unexpected error occurred'): string {
  if (error instanceof Error) {
    return error.message;
  }
  if (typeof error === 'string') {
    return error;
  }
  if (error && typeof error === 'object' && 'message' in error) {
    return String((error as { message: unknown }).message);
  }
  return fallback;
}

/**
 * Create error message for specific operations.
 * Useful for creating consistent error messages across stores.
 */
export const errorMessages = {
  fetch: (entity: string) => `Failed to fetch ${entity}`,
  create: (entity: string) => `Failed to create ${entity}`,
  update: (entity: string) => `Failed to update ${entity}`,
  delete: (entity: string) => `Failed to delete ${entity}`,
  login: 'Login failed',
  register: 'Registration failed',
  unauthorized: 'You are not authorized to perform this action',
  notFound: (entity: string) => `${entity} not found`,
  network: 'Network error - please check your connection',
  validation: (field: string) => `Invalid ${field}`,
} as const;

// ================================================================
// Validation Utilities
// ================================================================

/**
 * Check if a string is empty or only whitespace.
 */
export function isEmpty(value: string | null | undefined): boolean {
  return !value || value.trim().length === 0;
}

/**
 * Check if a string has content (not empty).
 */
export function hasContent(value: string | null | undefined): boolean {
  return !isEmpty(value);
}

export type PasswordStrength = 0 | 1 | 2 | 3 | 4;

/**
 * Password validation requirements.
 * Extracted from RegisterForm to be reusable.
 */
export const passwordValidation = {
  minLength: 8,
  requirements: [
    { test: (p: string) => p.length >= 8, text: 'At least 8 characters' },
    { test: (p: string) => /[a-z]/.test(p), text: 'One lowercase letter' },
    { test: (p: string) => /[A-Z]/.test(p), text: 'One uppercase letter' },
    { test: (p: string) => /[0-9]/.test(p), text: 'One number' },
    { test: (p: string) => /[^a-zA-Z0-9]/.test(p), text: 'One special character' },
  ],

  /**
   * Check if password meets all requirements.
   */
  isValid(password: string): boolean {
    return this.requirements.every((req) => req.test(password));
  },

  /**
   * Get list of unmet requirements.
   */
  getUnmetRequirements(password: string): string[] {
    return this.requirements
      .filter((req) => !req.test(password))
      .map((req) => req.text);
  },

  /**
   * Get requirements with met status.
   */
  getRequirementStatus(password: string): Array<{ met: boolean; text: string }> {
    return this.requirements.map((req) => ({
      met: req.test(password),
      text: req.text,
    }));
  },

  /**
   * Calculate password strength score (0-4).
   * 0: Very weak, 1: Weak, 2: Fair, 3: Good, 4: Strong
   */
  calculateStrength(password: string): PasswordStrength {
    let score = 0;
    if (password.length >= 8) score++;
    if (password.length >= 12) score++;
    if (/[a-z]/.test(password) && /[A-Z]/.test(password)) score++;
    if (/[0-9]/.test(password)) score++;
    if (/[^a-zA-Z0-9]/.test(password)) score++;
    return Math.min(score, 4) as PasswordStrength;
  },

  /**
   * Get strength label for display.
   */
  getStrengthLabel(score: PasswordStrength): string {
    const labels = ['Very Weak', 'Weak', 'Fair', 'Good', 'Strong'];
    return labels[score];
  },
};

/**
 * Email validation regex pattern.
 */
export const emailPattern = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/;

/**
 * Validate email format.
 */
export function isValidEmail(email: string): boolean {
  return emailPattern.test(email);
}

// ================================================================
// UI Style Constants
// ================================================================

/**
 * Common badge style variants.
 * Extracted from SettingsComponents to be reusable.
 */
export const badgeStyles = {
  default: 'bg-primary/20 text-primary',
  warning: 'bg-warning/20 text-warning',
  success: 'bg-success/20 text-success',
  destructive: 'bg-destructive/20 text-destructive',
  info: 'bg-info/20 text-info',
} as const;

export type BadgeVariant = keyof typeof badgeStyles;