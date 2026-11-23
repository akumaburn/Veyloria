import { forwardRef, useId, useState, InputHTMLAttributes } from 'react';
import { cn } from '@/lib/utils';
import { Check, Minus } from 'lucide-react';

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'size'> {
  label?: string;
  description?: string;
  error?: string;
  size?: 'sm' | 'md' | 'lg';
  indeterminate?: boolean;
}

export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(
  (
    {
      className,
      label,
      description,
      error,
      size = 'md',
      indeterminate = false,
      disabled,
      checked,
      ...props
    },
    ref
  ) => {
    const id = useId();
    const [internalChecked, setInternalChecked] = useState(!!props.defaultChecked);
    const isChecked = checked ?? internalChecked;
    const describedBy = [props['aria-describedby'], description && `${id}-description`, error && `${id}-error`].filter(Boolean).join(' ') || undefined;
    const sizeClasses = {
      sm: 'h-4 w-4',
      md: 'h-5 w-5',
      lg: 'h-6 w-6',
    };

    const iconSizes = {
      sm: 12,
      md: 14,
      lg: 16,
    };

    return (
      <label className={cn(
        'flex cursor-pointer items-start space-x-3',
        disabled && 'cursor-not-allowed opacity-50',
        className
      )}>
        <div className="relative flex items-center">
          <input
            ref={(node) => {
              if (node) node.indeterminate = indeterminate;
              if (typeof ref === 'function') ref(node);
              else if (ref) ref.current = node;
            }}
            type="checkbox"
            disabled={disabled}
            {...props}
            checked={isChecked}
            defaultChecked={undefined}
            onChange={(event) => { setInternalChecked(event.target.checked); props.onChange?.(event); }}
            className="peer sr-only"
            aria-checked={indeterminate ? 'mixed' : isChecked}
            aria-labelledby={label ? `${id}-label` : props['aria-labelledby']}
            aria-describedby={describedBy}
            aria-invalid={error ? true : props['aria-invalid']}
          />
          <div
            className={cn(
              'flex items-center justify-center rounded border-2 transition-all peer-focus-visible:ring-2 peer-focus-visible:ring-ring peer-focus-visible:ring-offset-2 peer-focus-visible:ring-offset-background',
              sizeClasses[size],
              isChecked || indeterminate
                ? 'border-primary bg-primary'
                : 'border-input bg-background',
              !disabled && !isChecked && !indeterminate && 'hover:border-muted-foreground',
              error && 'border-destructive'
            )}
          >
            {indeterminate ? (
              <Minus size={iconSizes[size]} className="text-primary-foreground" />
            ) : isChecked ? (
              <Check size={iconSizes[size]} className="text-primary-foreground" />
            ) : null}
          </div>
        </div>

        {(label || description) && (
          <div className="flex-1">
            {label && (
              <span id={`${id}-label`} className={cn(
                'text-sm font-medium text-foreground',
                error && 'text-destructive'
              )}>
                {label}
              </span>
            )}
            {description && (
              <p id={`${id}-description`} className="mt-0.5 text-xs text-muted-foreground">
                {description}
              </p>
            )}
            {error && (
              <p id={`${id}-error`} role="alert" className="mt-0.5 text-xs text-destructive">
                {error}
              </p>
            )}
          </div>
        )}
      </label>
    );
  }
);

Checkbox.displayName = 'Checkbox';

// Radio Button Component
export interface RadioProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'size'> {
  label?: string;
  description?: string;
  error?: string;
  size?: 'sm' | 'md' | 'lg';
}

export const Radio = forwardRef<HTMLInputElement, RadioProps>(
  (
    {
      className,
      label,
      description,
      error,
      size = 'md',
      disabled,
      checked,
      ...props
    },
    ref
  ) => {
    const sizeClasses = {
      sm: 'h-4 w-4',
      md: 'h-5 w-5',
      lg: 'h-6 w-6',
    };

    const dotSizes = {
      sm: 'h-1.5 w-1.5',
      md: 'h-2 w-2',
      lg: 'h-2.5 w-2.5',
    };

    return (
      <label className={cn(
        'flex cursor-pointer items-start space-x-3',
        disabled && 'cursor-not-allowed opacity-50',
        className
      )}>
        <div className="relative flex items-center">
          <input
            ref={ref}
            type="radio"
            disabled={disabled}
            checked={checked}
            className="peer sr-only"
            aria-invalid={!!error}
            {...props}
          />
          <div
            className={cn(
              'flex items-center justify-center rounded-full border-2 transition-all peer-focus-visible:ring-2 peer-focus-visible:ring-ring peer-focus-visible:ring-offset-2 peer-focus-visible:ring-offset-background',
              sizeClasses[size],
              checked
                ? 'border-primary'
                : 'border-input',
              !disabled && !checked && 'hover:border-muted-foreground',
              error && 'border-destructive'
            )}
          >
            {checked && (
              <div className={cn(
                'rounded-full bg-primary',
                dotSizes[size]
              )} />
            )}
          </div>
        </div>

        {(label || description) && (
          <div className="flex-1">
            {label && (
              <span className={cn(
                'text-sm font-medium text-foreground',
                error && 'text-destructive'
              )}>
                {label}
              </span>
            )}
            {description && (
              <p className="mt-0.5 text-xs text-muted-foreground">
                {description}
              </p>
            )}
            {error && (
              <p className="mt-0.5 text-xs text-destructive">
                {error}
              </p>
            )}
          </div>
        )}
      </label>
    );
  }
);

Radio.displayName = 'Radio';

// Radio Group Component
export interface RadioOption {
  value: string | number;
  label: string;
  description?: string;
  disabled?: boolean;
}

interface RadioGroupProps {
  name: string;
  value: string | number;
  onChange: (value: string | number) => void;
  options: RadioOption[];
  label?: string;
  error?: string;
  helperText?: string;
  orientation?: 'horizontal' | 'vertical';
  size?: 'sm' | 'md' | 'lg';
  disabled?: boolean;
  className?: string;
}

export const RadioGroup = ({
  name,
  value,
  onChange,
  options,
  label,
  error,
  helperText,
  orientation = 'vertical',
  size = 'md',
  disabled,
  className,
}: RadioGroupProps) => {
  return (
    <div className={className}>
      {label && (
        <p className="mb-2 text-sm font-medium text-foreground">
          {label}
        </p>
      )}

      <div className={cn(
        'space-y-2',
        orientation === 'horizontal' && 'flex flex-wrap gap-4 space-y-0'
      )}>
        {options.map((option) => (
          <Radio
            key={option.value}
            name={name}
            value={option.value}
            checked={value === option.value}
            onChange={() => onChange(option.value)}
            label={option.label}
            description={option.description}
            disabled={disabled || option.disabled}
            size={size}
          />
        ))}
      </div>

      {error && (
        <p className="mt-1.5 text-xs text-destructive">
          {error}
        </p>
      )}

      {helperText && !error && (
        <p className="mt-1.5 text-xs text-muted-foreground">
          {helperText}
        </p>
      )}
    </div>
  );
};

// Checkbox Group Component
export interface CheckboxOption {
  value: string | number;
  label: string;
  description?: string;
  disabled?: boolean;
}

interface CheckboxGroupProps {
  values: (string | number)[];
  onChange: (values: (string | number)[]) => void;
  options: CheckboxOption[];
  label?: string;
  error?: string;
  helperText?: string;
  orientation?: 'horizontal' | 'vertical';
  size?: 'sm' | 'md' | 'lg';
  disabled?: boolean;
  className?: string;
}

export const CheckboxGroup = ({
  values,
  onChange,
  options,
  label,
  error,
  helperText,
  orientation = 'vertical',
  size = 'md',
  disabled,
  className,
}: CheckboxGroupProps) => {
  const handleChange = (optionValue: string | number, checked: boolean) => {
    if (checked) {
      onChange([...values, optionValue]);
    } else {
      onChange(values.filter(v => v !== optionValue));
    }
  };

  return (
    <div className={className}>
      {label && (
        <p className="mb-2 text-sm font-medium text-foreground">
          {label}
        </p>
      )}

      <div className={cn(
        'space-y-2',
        orientation === 'horizontal' && 'flex flex-wrap gap-4 space-y-0'
      )}>
        {options.map((option) => (
          <Checkbox
            key={option.value}
            value={option.value}
            checked={values.includes(option.value)}
            onChange={(e) => handleChange(option.value, e.target.checked)}
            label={option.label}
            description={option.description}
            disabled={disabled || option.disabled}
            size={size}
          />
        ))}
      </div>

      {error && (
        <p className="mt-1.5 text-xs text-destructive">
          {error}
        </p>
      )}

      {helperText && !error && (
        <p className="mt-1.5 text-xs text-muted-foreground">
          {helperText}
        </p>
      )}
    </div>
  );
};
