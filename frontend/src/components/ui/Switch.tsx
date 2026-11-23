import { forwardRef, InputHTMLAttributes, useState } from 'react';
import { cn } from '@/lib/utils';
import { Check, X } from 'lucide-react';

export interface SwitchProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'size'> {
  label?: string;
  description?: string;
  error?: string;
  size?: 'sm' | 'md' | 'lg';
  variant?: 'default' | 'ios' | 'android';
  showIcons?: boolean;
  onLabel?: string;
  offLabel?: string;
}

export const Switch = forwardRef<HTMLInputElement, SwitchProps>(
  (
    {
      className,
      label,
      description,
      error,
      size = 'md',
      variant = 'default',
      showIcons = false,
      onLabel,
      offLabel,
      disabled,
      checked,
      onChange,
      ...props
    },
    ref
  ) => {
    const sizeClasses = {
      sm: {
        track: 'h-5 w-9',
        thumb: 'h-3.5 w-3.5',
        translate: 'translate-x-4',
        icon: 10,
      },
      md: {
        track: 'h-6 w-11',
        thumb: 'h-4 w-4',
        translate: 'translate-x-5',
        icon: 12,
      },
      lg: {
        track: 'h-7 w-14',
        thumb: 'h-5 w-5',
        translate: 'translate-x-7',
        icon: 14,
      },
    };

    const variantStyles = {
      default: {
        track: cn(
          checked ? 'bg-primary' : 'bg-muted',
          'hover:bg-opacity-80'
        ),
        thumb: checked ? 'bg-primary-foreground' : 'bg-muted-foreground',
      },
      ios: {
        track: cn(
          checked ? 'bg-success' : 'bg-muted-foreground/30',
          'hover:bg-opacity-80'
        ),
        thumb: cn('shadow-md', checked ? 'bg-success-foreground' : 'bg-muted-foreground'),
      },
      android: {
        track: cn(
          checked ? 'bg-primary/30' : 'bg-muted/30',
          'hover:bg-opacity-50'
        ),
        thumb: cn(
          checked ? 'bg-primary' : 'bg-muted-foreground',
          'shadow-lg'
        ),
      },
    };

    const sizes = sizeClasses[size];
    const styles = variantStyles[variant];

    return (
      <label className={cn(
        'flex cursor-pointer items-start space-x-3',
        disabled && 'cursor-not-allowed opacity-50',
        className
      )}>
        <div className="relative inline-flex items-center">
          <input
            ref={ref}
            type="checkbox"
            disabled={disabled}
            checked={checked}
            onChange={onChange}
            className="peer sr-only"
            aria-invalid={!!error}
            {...props}
          />
          
          <div
            className={cn(
              'relative inline-flex items-center rounded-full transition-all duration-300 peer-focus-visible:ring-2 peer-focus-visible:ring-ring peer-focus-visible:ring-offset-2 peer-focus-visible:ring-offset-background',
              sizes.track,
              styles.track,
              error && 'ring-2 ring-destructive ring-offset-2 ring-offset-background'
            )}
          >
            {variant === 'default' && showIcons && (
              <>
                <span className={cn(
                  'absolute left-1 transition-opacity',
                  checked ? 'opacity-100' : 'opacity-0'
                )}>
                  <Check size={sizes.icon} className="text-primary-foreground" />
                </span>
                <span className={cn(
                  'absolute right-1 transition-opacity',
                  checked ? 'opacity-0' : 'opacity-100'
                )}>
                  <X size={sizes.icon} className="text-muted-foreground" />
                </span>
              </>
            )}

            <span
              className={cn(
                'absolute left-1 inline-block rounded-full transition-transform duration-300',
                sizes.thumb,
                styles.thumb,
                checked && sizes.translate
              )}
            />
          </div>

          {(onLabel || offLabel) && (
            <span className="ml-2 text-sm text-muted-foreground">
              {checked ? onLabel : offLabel}
            </span>
          )}
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

Switch.displayName = 'Switch';

interface SwitchGroupProps {
  switches: Array<{
    id: string;
    label: string;
    description?: string;
    defaultChecked?: boolean;
    disabled?: boolean;
  }>;
  onChange?: (id: string, checked: boolean) => void;
  label?: string;
  description?: string;
  size?: 'sm' | 'md' | 'lg';
  variant?: 'default' | 'ios' | 'android';
  className?: string;
}

export const SwitchGroup = ({
  switches,
  onChange,
  label,
  description,
  size = 'md',
  variant = 'default',
  className,
}: SwitchGroupProps) => {
  const [states, setStates] = useState<Record<string, boolean>>(
    switches.reduce((acc, sw) => ({
      ...acc,
      [sw.id]: sw.defaultChecked || false,
    }), {})
  );

  const handleChange = (id: string, checked: boolean) => {
    setStates(prev => ({ ...prev, [id]: checked }));
    onChange?.(id, checked);
  };

  return (
    <div className={className}>
      {label && (
        <h3 className="mb-1 text-sm font-medium text-foreground">{label}</h3>
      )}
      {description && (
        <p className="mb-3 text-xs text-muted-foreground">{description}</p>
      )}

      <div className="space-y-3">
        {switches.map((sw) => (
          <Switch
            key={sw.id}
            label={sw.label}
            description={sw.description}
            checked={states[sw.id]}
            onChange={(e) => handleChange(sw.id, e.target.checked)}
            disabled={sw.disabled}
            size={size}
            variant={variant}
          />
        ))}
      </div>
    </div>
  );
};

interface ToggleButtonGroupProps {
  value: string | number;
  onChange: (value: string | number) => void;
  options: Array<{
    value: string | number;
    label: string;
    icon?: React.ReactNode;
    disabled?: boolean;
  }>;
  size?: 'sm' | 'md' | 'lg';
  fullWidth?: boolean;
  className?: string;
}

export const ToggleButtonGroup = ({
  value,
  onChange,
  options,
  size = 'md',
  fullWidth = false,
  className,
}: ToggleButtonGroupProps) => {
  const sizeClasses = {
    sm: 'px-3 py-1.5 text-xs',
    md: 'px-4 py-2 text-sm',
    lg: 'px-5 py-2.5 text-base',
  };

  return (
    <div className={cn(
      'inline-flex rounded-lg bg-muted p-1',
      fullWidth && 'w-full',
      className
    )}>
      {options.map((option, index) => (
        <button
          key={option.value}
          type="button"
          onClick={() => !option.disabled && onChange(option.value)}
          disabled={option.disabled}
          aria-pressed={value === option.value}
          className={cn(
            'relative flex items-center justify-center font-medium transition-all',
            sizeClasses[size],
            index === 0 && 'rounded-l-md',
            index === options.length - 1 && 'rounded-r-md',
            value === option.value
              ? 'bg-accent text-foreground shadow-sm'
              : 'text-muted-foreground hover:text-foreground',
            option.disabled && 'cursor-not-allowed opacity-50',
            fullWidth && 'flex-1'
          )}
        >
          {option.icon && (
            <span className="mr-2">{option.icon}</span>
          )}
          {option.label}
        </button>
      ))}
    </div>
  );
};

interface ToggleSwitchProps extends Omit<SwitchProps, 'onToggle'> {
  enabledText?: string;
  disabledText?: string;
  onToggle?: (enabled: boolean) => void;
}

export const ToggleSwitch = forwardRef<HTMLInputElement, ToggleSwitchProps>(
  (
    {
      enabledText = 'Enabled',
      disabledText = 'Disabled',
      onToggle,
      checked,
      onChange,
      ...props
    },
    ref
  ) => {
    const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
      onChange?.(e);
      onToggle?.(e.target.checked);
    };

    return (
      <div className="flex items-center space-x-3">
        <span className={cn(
          'text-sm',
          !checked ? 'text-foreground font-medium' : 'text-muted-foreground'
        )}>
          {disabledText}
        </span>

        <Switch
          ref={ref}
          checked={checked}
          onChange={handleChange}
          {...props}
        />

        <span className={cn(
          'text-sm',
          checked ? 'text-foreground font-medium' : 'text-muted-foreground'
        )}>
          {enabledText}
        </span>
      </div>
    );
  }
);

ToggleSwitch.displayName = 'ToggleSwitch';
