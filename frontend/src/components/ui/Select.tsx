import { forwardRef, useId, SelectHTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { ChevronDown, AlertCircle } from 'lucide-react';

export interface SelectOption {
  value: string | number;
  label: string;
  disabled?: boolean;
  group?: string;
}

export interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'size'> {
  label?: string;
  error?: string;
  helperText?: string;
  options: SelectOption[];
  placeholder?: string;
  size?: 'sm' | 'md' | 'lg';
  variant?: 'default' | 'filled' | 'ghost';
  leftIcon?: ReactNode;
}

export const Select = forwardRef<HTMLSelectElement, SelectProps>(
  (
    {
      className,
      label,
      error,
      helperText,
      options,
      placeholder = 'Select an option',
      size = 'md',
      variant = 'default',
      leftIcon,
      disabled,
      ...props
    },
    ref
  ) => {
    const generatedId = useId();
    const fieldId = props.id || generatedId;
    const errorId = `${fieldId}-error`;
    const helperId = `${fieldId}-help`;
    const describedBy = [props['aria-describedby'], helperText ? helperId : undefined, error ? errorId : undefined].filter(Boolean).join(' ') || undefined;

    const sizeClasses = {
      sm: 'h-9 px-3 pr-8 text-sm',
      md: 'h-11 px-3 pr-10 text-sm',
      lg: 'h-12 px-4 pr-10 text-base',
    };

    const variantClasses = {
      default: cn(
        'border border-input bg-background',
        'hover:border-muted-foreground',
        'focus:border-primary focus:ring-1 focus:ring-primary',
        error && 'border-destructive focus:border-destructive focus:ring-destructive'
      ),
      filled: cn(
        'border border-transparent bg-muted',
        'hover:bg-accent',
        'focus:bg-muted focus:border-primary focus:ring-1 focus:ring-primary',
        error && 'bg-destructive/10 border-destructive focus:border-destructive focus:ring-destructive'
      ),
      ghost: cn(
        'border border-transparent bg-transparent',
        'hover:bg-accent/50',
        'focus:bg-muted focus:border-primary focus:ring-1 focus:ring-primary',
        error && 'focus:border-destructive focus:ring-destructive'
      ),
    };

    // Group options by group property
    const groupedOptions = options.reduce((acc, option) => {
      const group = option.group || '';
      if (!acc[group]) acc[group] = [];
      acc[group].push(option);
      return acc;
    }, {} as Record<string, SelectOption[]>);

    // A real option already answers the empty value (for example "All genres"),
    // so a placeholder would hide that label instead of describing the field.
    const showPlaceholder = Boolean(placeholder) && !options.some((option) => option.value === '');

    return (
      <div className="w-full">
        {label && (
          <label htmlFor={fieldId} className="mb-1.5 block text-sm font-medium text-foreground">
            {label}{props.required && <span className="ml-1 text-xs font-normal text-muted-foreground">(required)</span>}
          </label>
        )}

        <div className="relative">
          {leftIcon && (
            <div className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground">
              {leftIcon}
            </div>
          )}

          <select
            ref={ref}
            id={fieldId}
            disabled={disabled}
            defaultValue={props.value === undefined ? props.defaultValue ?? '' : undefined}
            className={cn(
              'w-full appearance-none rounded-xl text-foreground',
              'transition-all duration-200',
              'focus:outline-none',
              'disabled:cursor-not-allowed disabled:opacity-50',
              sizeClasses[size],
              variantClasses[variant],
              leftIcon && 'pl-10',
              className
            )}
            {...props}
            aria-invalid={error ? true : props['aria-invalid']}
            aria-describedby={describedBy}
          >
            {showPlaceholder && (
              <option value="" disabled>
                {placeholder}
              </option>
            )}

            {Object.entries(groupedOptions).map(([group, groupOptions]) => (
              group ? (
                <optgroup key={group} label={group}>
                  {groupOptions.map((option) => (
                    <option
                      key={option.value}
                      value={option.value}
                      disabled={option.disabled}
                    >
                      {option.label}
                    </option>
                  ))}
                </optgroup>
              ) : (
                groupOptions.map((option) => (
                  <option
                    key={option.value}
                    value={option.value}
                    disabled={option.disabled}
                  >
                    {option.label}
                  </option>
                ))
              )
            ))}
          </select>

          <div className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground">
            <ChevronDown size={16} />
          </div>
        </div>

        {error && (
          <div id={errorId} role="alert" className="mt-1.5 flex items-center space-x-1 text-xs text-destructive">
            <AlertCircle size={12} />
            <span>{error}</span>
          </div>
        )}

        {helperText && (
          <p id={helperId} className="mt-1.5 text-xs text-muted-foreground">
            {helperText}
          </p>
        )}
      </div>
    );
  }
);

Select.displayName = 'Select';

// Native selection preserves keyboard, touch, and assistive technology behavior.
interface CustomSelectProps {
  label?: string;
  value: string | number;
  onChange: (value: string | number) => void;
  options: SelectOption[];
  placeholder?: string;
  error?: string;
  helperText?: string;
  disabled?: boolean;
  className?: string;
}

export const CustomSelect = ({
  label,
  value,
  onChange,
  options,
  placeholder = 'Select an option',
  error,
  helperText,
  disabled,
  className,
}: CustomSelectProps) => {
  return (
    <Select
      label={label}
      value={value}
      onChange={(event) => {
        const option = options.find(item => String(item.value) === event.target.value);
        if (option) onChange(option.value);
      }}
      options={options}
      placeholder={placeholder}
      error={error}
      helperText={helperText}
      disabled={disabled}
      className={className}
    />
  );
};
