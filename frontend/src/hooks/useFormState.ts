import { useState, useCallback, useMemo } from 'react';
import { isEmpty, isValidEmail } from '../lib/utils';

/**
 * Generic form state hook to eliminate duplicate form handling code.
 * Consolidates patterns from LoginForm, RegisterForm, CharacterSettings, etc.
 *
 * @template T - The form data type
 */
/**
 * Return type for useFormState hook.
 */
export interface UseFormStateReturn<T extends Record<string, unknown>> {
  formData: T;
  errors: Partial<Record<keyof T, string>>;
  touched: Partial<Record<keyof T, boolean>>;
  isSubmitting: boolean;
  isDirty: boolean;
  hasErrors: boolean;
  setFormData: React.Dispatch<React.SetStateAction<T>>;
  handleChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => void;
  handleBlur: (e: React.FocusEvent<HTMLInputElement | HTMLTextAreaElement>) => void;
  setField: <K extends keyof T>(field: K, value: T[K]) => void;
  setFields: (fields: Partial<T>) => void;
  setFieldError: <K extends keyof T>(field: K, error: string) => void;
  setFieldErrors: (newErrors: Partial<Record<keyof T, string>>) => void;
  clearErrors: () => void;
  getFieldError: <K extends keyof T>(field: K) => string | undefined;
  reset: () => void;
  resetTo: (data: T) => void;
  setIsSubmitting: React.Dispatch<React.SetStateAction<boolean>>;
}

export function useFormState<T extends Record<string, unknown>>(initialData: T): UseFormStateReturn<T> {
  const [formData, setFormData] = useState<T>(initialData);
  const [errors, setErrors] = useState<Partial<Record<keyof T, string>>>({});
  const [touched, setTouched] = useState<Partial<Record<keyof T, boolean>>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);

  /**
   * Handle input change for a form field.
   */
  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
      const { name, value, type } = e.target;
      const newValue = type === 'checkbox' ? (e.target as HTMLInputElement).checked : value;

      setFormData((prev) => ({ ...prev, [name]: newValue }));
      // Clear error when field is modified
      setErrors((prev) => ({ ...prev, [name]: undefined }));
    },
    []
  );

  /**
   * Update a specific field value programmatically.
   */
  const setField = useCallback(<K extends keyof T>(field: K, value: T[K]) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
    setErrors((prev) => ({ ...prev, [field]: undefined }));
  }, []);

  /**
   * Update multiple fields at once.
   */
  const setFields = useCallback((fields: Partial<T>) => {
    setFormData((prev) => ({ ...prev, ...fields }));
    // Clear errors for updated fields
    const clearedErrors: Partial<Record<keyof T, string>> = {};
    Object.keys(fields).forEach((key) => {
      clearedErrors[key as keyof T] = undefined;
    });
    setErrors((prev) => ({ ...prev, ...clearedErrors }));
  }, []);

  /**
   * Mark a field as touched (blurred).
   */
  const handleBlur = useCallback((e: React.FocusEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    const { name } = e.target;
    setTouched((prev) => ({ ...prev, [name]: true }));
  }, []);

  /**
   * Set an error for a specific field.
   */
  const setFieldError = useCallback(<K extends keyof T>(field: K, error: string) => {
    setErrors((prev) => ({ ...prev, [field]: error }));
  }, []);

  /**
   * Set multiple errors at once.
   */
  const setFieldErrors = useCallback((newErrors: Partial<Record<keyof T, string>>) => {
    setErrors((prev) => ({ ...prev, ...newErrors }));
  }, []);

  /**
   * Clear all errors.
   */
  const clearErrors = useCallback(() => {
    setErrors({});
  }, []);

  /**
   * Reset form to initial state.
   */
  const reset = useCallback(() => {
    setFormData(initialData);
    setErrors({});
    setTouched({});
    setIsSubmitting(false);
  }, [initialData]);

  /**
   * Reset form to specific values.
   */
  const resetTo = useCallback((data: T) => {
    setFormData(data);
    setErrors({});
    setTouched({});
    setIsSubmitting(false);
  }, []);

  /**
   * Check if form is dirty (has changes from initial).
   */
  const isDirty = useMemo(() => {
    return JSON.stringify(formData) !== JSON.stringify(initialData);
  }, [formData, initialData]);

  /**
   * Check if form has any errors.
   */
  const hasErrors = useMemo(() => {
    return Object.values(errors).some((error) => !!error);
  }, [errors]);

  /**
   * Get error for a specific field, only if touched.
   */
  const getFieldError = useCallback(
    <K extends keyof T>(field: K): string | undefined => {
      return touched[field] ? errors[field] : undefined;
    },
    [errors, touched]
  );

  return {
    formData,
    errors,
    touched,
    isSubmitting,
    isDirty,
    hasErrors,
    setFormData,
    handleChange,
    handleBlur,
    setField,
    setFields,
    setFieldError,
    setFieldErrors,
    clearErrors,
    getFieldError,
    reset,
    resetTo,
    setIsSubmitting,
  };
}

/**
 * Form validation builder for consistent validation patterns.
 */
export interface ValidationRule<T> {
  field: keyof T;
  validate: (value: unknown, formData: T) => string | undefined;
}

/**
 * Create common validation rules.
 */
export const validators = {
  required: (fieldName: string) => (value: unknown) =>
    isEmpty(String(value ?? '')) ? `${fieldName} is required` : undefined,

  minLength: (fieldName: string, min: number) => (value: unknown) =>
    String(value ?? '').length < min
      ? `${fieldName} must be at least ${min} characters`
      : undefined,

  maxLength: (fieldName: string, max: number) => (value: unknown) =>
    String(value ?? '').length > max
      ? `${fieldName} must be at most ${max} characters`
      : undefined,

  email: (fieldName: string = 'Email') => (value: unknown) =>
    !isValidEmail(String(value ?? '')) ? `${fieldName} is invalid` : undefined,

  match:
    <T extends Record<string, unknown>>(fieldName: string, matchField: keyof T) =>
    (value: unknown, formData: T) =>
      value !== formData[matchField] ? `${fieldName} must match` : undefined,

  pattern: (fieldName: string, pattern: RegExp, message?: string) => (value: unknown) =>
    !pattern.test(String(value ?? ''))
      ? message || `${fieldName} is invalid`
      : undefined,
};

/**
 * Run validation rules and return errors.
 */
export function validateForm<T extends Record<string, unknown>>(
  formData: T,
  rules: ValidationRule<T>[]
): Partial<Record<keyof T, string>> {
  const errors: Partial<Record<keyof T, string>> = {};

  for (const rule of rules) {
    const error = rule.validate(formData[rule.field], formData);
    if (error) {
      errors[rule.field] = error;
    }
  }

  return errors;
}

export default useFormState;
