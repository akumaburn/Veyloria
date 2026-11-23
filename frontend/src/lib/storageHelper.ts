/**
 * Type-safe localStorage wrapper.
 * Eliminates duplication of try/catch and JSON serialization logic.
 */

/**
 * Get an item from localStorage and parse it.
 * Returns null if not found or if parsing fails.
 */
export const getStoredItem = <T = any>(key: string, defaultValue?: T): T | null => {
  try {
    const item = localStorage.getItem(key);
    if (item === null) {
      return defaultValue ?? null;
    }
    return JSON.parse(item) as T;
  } catch (error) {
    console.warn(`Failed to parse localStorage item "${key}":`, error);
    return defaultValue ?? null;
  }
};

/**
 * Set an item in localStorage with JSON serialization.
 * Returns true if successful, false if storage is full or error occurs.
 */
export const setStoredItem = <T = any>(key: string, value: T): boolean => {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch (error) {
    console.warn(`Failed to set localStorage item "${key}":`, error);
    return false;
  }
};

/**
 * Remove an item from localStorage.
 */
export const removeStoredItem = (key: string): void => {
  try {
    localStorage.removeItem(key);
  } catch (error) {
    console.warn(`Failed to remove localStorage item "${key}":`, error);
  }
};

/**
 * Clear all items from localStorage.
 */
export const clearStorage = (): void => {
  try {
    localStorage.clear();
  } catch (error) {
    console.warn('Failed to clear localStorage:', error);
  }
};

/**
 * Check if a key exists in localStorage.
 */
export const hasStoredItem = (key: string): boolean => {
  try {
    return localStorage.getItem(key) !== null;
  } catch (error) {
    console.warn(`Failed to check localStorage item "${key}":`, error);
    return false;
  }
};

/**
 * Storage helper for typed keys.
 * Usage: const userStorage = createStorageHelper<User>('user');
 */
export const createStorageHelper = <T>(key: string) => ({
  get: (defaultValue?: T): T | null => getStoredItem<T>(key, defaultValue),
  set: (value: T): boolean => setStoredItem(key, value),
  remove: () => removeStoredItem(key),
  exists: (): boolean => hasStoredItem(key),
});
