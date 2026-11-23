import { forwardRef, useId, useRef, InputHTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { AlertCircle, Eye, EyeOff, Search, X } from 'lucide-react';
import { useState } from 'react';

export interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> {
  label?: string;
  error?: string;
  helperText?: string;
  leftIcon?: ReactNode;
  rightIcon?: ReactNode;
  size?: 'sm' | 'md' | 'lg';
  variant?: 'default' | 'filled' | 'ghost';
  clearable?: boolean;
  onClear?: () => void;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(
  (
    {
      className,
      label,
      error,
      helperText,
      leftIcon,
      rightIcon,
      size = 'md',
      variant = 'default',
      clearable = false,
      onClear,
      type,
      disabled,
      ...props
    },
    ref
  ) => {
    const inputRef = useRef<HTMLInputElement | null>(null);
    const [showPassword, setShowPassword] = useState(false);
    const isPassword = type === 'password';
    const inputType = isPassword && showPassword ? 'text' : type;

    const generatedId = useId();
    const fieldId = props.id || generatedId;
    const errorId = `${fieldId}-error`;
    const helperId = `${fieldId}-help`;
    const describedBy = [props['aria-describedby'], helperText ? helperId : undefined, error ? errorId : undefined].filter(Boolean).join(' ') || undefined;

    const sizeClasses = {
      sm: 'h-9 px-3 text-sm',
      md: 'h-11 px-3 text-sm',
      lg: 'h-12 px-4 text-base',
    };

    const variantClasses = {
      default: cn(
        'border border-input bg-background',
        'hover:border-muted-foreground',
        'focus:border-primary focus:ring-2 focus:ring-primary/20',
        error && 'border-destructive focus:border-destructive focus:ring-destructive/20'
      ),
      filled: cn(
        'border border-transparent bg-muted',
        'hover:bg-accent',
        'focus:bg-background focus:border-primary focus:ring-2 focus:ring-primary/20',
        error && 'bg-destructive/10 border-destructive focus:border-destructive focus:ring-destructive/20'
      ),
      ghost: cn(
        'border border-transparent bg-transparent',
        'hover:bg-muted/50',
        'focus:bg-muted focus:border-primary focus:ring-2 focus:ring-primary/20',
        error && 'focus:border-destructive focus:ring-destructive/20'
      ),
    };

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

          <input
            ref={(node) => {
              inputRef.current = node;
              if (typeof ref === 'function') ref(node);
              else if (ref) ref.current = node;
            }}
            id={fieldId}
            type={inputType}
            disabled={disabled}
            className={cn(
              'w-full rounded-xl text-foreground placeholder-muted-foreground',
              'transition-all duration-150',
              'focus:outline-none',
              'disabled:cursor-not-allowed disabled:opacity-50',
              sizeClasses[size],
              variantClasses[variant],
              leftIcon && 'pl-10',
              (rightIcon || clearable || isPassword) && 'pr-12',
              clearable && isPassword && 'pr-20',
              className
            )}
            {...props}
            aria-invalid={error ? true : props['aria-invalid']}
            aria-describedby={describedBy}
          />

          {/* Right side icons */}
          <div className="absolute right-1 top-1/2 flex -translate-y-1/2 items-center">
            {clearable && onClear && props.value && !disabled && !props.readOnly && (
              <button
                type="button"
                onClick={() => { onClear(); inputRef.current?.focus(); }}
                aria-label="Clear input"
                className="flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
              >
                <X size={16} />
              </button>
            )}

            {isPassword && (
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                aria-label={showPassword ? "Hide password" : "Show password"}
                aria-pressed={showPassword}
                className="flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
                disabled={disabled}
              >
                {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            )}

            {rightIcon && !clearable && !isPassword && (
              <div className="text-muted-foreground">{rightIcon}</div>
            )}
          </div>
        </div>

        {error && (
          <div id={errorId} role="alert" className="mt-1.5 flex items-center gap-1.5 text-xs text-destructive">
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

Input.displayName = 'Input';

interface SearchInputProps extends Omit<InputProps, 'leftIcon' | 'type'> {
  onSearch?: (value: string) => void;
}

export const SearchInput = forwardRef<HTMLInputElement, SearchInputProps>(
  ({ onSearch, onKeyDown, ...props }, ref) => {
    const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === 'Enter' && onSearch) {
        onSearch(e.currentTarget.value);
      }
      onKeyDown?.(e);
    };

    return (
      <Input
        ref={ref}
        type="search"
        leftIcon={<Search size={18} />}
        onKeyDown={handleKeyDown}
        {...props}
      />
    );
  }
);

SearchInput.displayName = 'SearchInput';
