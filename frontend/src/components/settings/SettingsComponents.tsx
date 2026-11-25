/**
 * Settings UI Components
 *
 * Reusable components for the settings interface.
 * Uses semantic color tokens for consistency.
 * Uses shared badgeStyles from utils to eliminate DRY violations.
 */

import { createContext, useContext, useId, useState, forwardRef, ReactNode } from 'react';
import { cn, badgeStyles } from '@/lib/utils';
import { Info, AlertTriangle, LucideIcon, ChevronDown, Check } from 'lucide-react';
import * as SelectPrimitive from '@radix-ui/react-select';

const SettingLabelContext = createContext<{ labelId: string; describedBy?: string; disabled?: boolean } | undefined>(undefined);

// =============================================================================
// Setting Section - Groups related settings with a header
// =============================================================================
interface SettingSectionProps {
  title: string;
  description?: string;
  icon?: LucideIcon;
  children: ReactNode;
  className?: string;
  badge?: string;
  badgeVariant?: 'default' | 'warning' | 'success' | 'destructive';
}

export const SettingSection = ({
  title,
  description,
  icon: Icon,
  children,
  className,
  badge,
  badgeVariant = 'default',
}: SettingSectionProps) => {
  // Uses shared badgeStyles from utils (DRY)
  return (
    <div className={cn('rounded-2xl border border-border bg-card overflow-hidden', className)}>
      <div className="flex items-center gap-3 border-b border-border px-4 py-4 sm:px-5">
        {Icon && (
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary/10">
            <Icon size={18} className="text-primary" />
          </div>
        )}
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <h3 className="text-sm font-semibold text-foreground">{title}</h3>
            {badge && (
              <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium', badgeStyles[badgeVariant])}>
                {badge}
              </span>
            )}
          </div>
          {description && (
            <p className="mt-0.5 text-xs text-muted-foreground">{description}</p>
          )}
        </div>
      </div>
      <div className="divide-y divide-border/50">{children}</div>
    </div>
  );
};

// =============================================================================
// Setting Row - Individual setting with label, description, and control
// =============================================================================
interface SettingRowProps {
  label: string;
  description?: string;
  warning?: string;
  info?: string;
  badge?: string;
  badgeVariant?: 'default' | 'warning' | 'success' | 'destructive';
  disabled?: boolean;
  children: ReactNode;
  className?: string;
  vertical?: boolean;
}

export const SettingRow = ({
  label,
  description,
  warning,
  info,
  badge,
  badgeVariant = 'default',
  disabled,
  children,
  className,
  vertical = false,
}: SettingRowProps) => {
  const labelId = useId();
  return (
    <SettingLabelContext.Provider value={{ labelId, disabled, describedBy: [description && `${labelId}-description`, warning && `${labelId}-warning`, info && `${labelId}-info`].filter(Boolean).join(' ') || undefined }}>
      <div
        className={cn(
          'px-4 py-4 sm:px-5',
          vertical ? 'space-y-3' : 'flex flex-wrap items-start justify-between gap-4',
          disabled && 'opacity-50',
          className
        )}
      >
        {/*
          A wide control wraps under the label instead of squeezing it.
          Without a minimum, a 220px select leaves the label a few characters wide.
          Below 640px every control except a switch takes the full row.
        */}
        <div className={cn('min-w-44 flex-1', vertical && 'mb-2')}>
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <label id={labelId} htmlFor={`${labelId}-control`} className="text-sm font-medium text-foreground">{label}</label>
            {info && (
              <span id={`${labelId}-info`} title={info} aria-label={info} className="cursor-help text-muted-foreground hover:text-foreground transition-colors">
                <Info size={14} />
              </span>
            )}
            {badge && (
              <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium', badgeStyles[badgeVariant])}>
                {badge}
              </span>
            )}
          </div>
          {description && (
            <p id={`${labelId}-description`} className="mt-1 text-xs text-muted-foreground">{description}</p>
          )}
          {warning && (
            <div className="mt-2 flex items-start gap-1.5">
              <AlertTriangle size={12} className="mt-0.5 text-warning shrink-0" />
              <p id={`${labelId}-warning`} className="text-xs text-warning">{warning}</p>
            </div>
          )}
        </div>
        {children != null && (
          <div
            className={cn(
              'max-w-full shrink-0',
              vertical ? 'w-full' : 'max-sm:w-full max-sm:has-[[role=switch]]:w-auto'
            )}
          >
            {children}
          </div>
        )}
      </div>
    </SettingLabelContext.Provider>
  );
};

// =============================================================================
// Toggle Switch - Semantic color version
// =============================================================================
interface SettingToggleProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  size?: 'sm' | 'md' | 'lg';
}

export const SettingToggle = forwardRef<HTMLButtonElement, SettingToggleProps>(
  ({ checked, onChange, disabled, size = 'md' }, ref) => {
    const { labelId, describedBy, disabled: rowDisabled } = useContext(SettingLabelContext) ?? {};
    disabled = disabled || rowDisabled;
    const sizeStyles = {
      sm: { track: 'h-5 w-9', thumb: 'h-3.5 w-3.5', translate: 'translate-x-4' },
      md: { track: 'h-6 w-11', thumb: 'h-4 w-4', translate: 'translate-x-5' },
      lg: { track: 'h-7 w-13', thumb: 'h-5 w-5', translate: 'translate-x-6' },
    };

    const styles = sizeStyles[size];

    return (
      <button
        ref={ref}
        type="button"
        role="switch"
        id={labelId ? `${labelId}-control` : undefined}
        aria-labelledby={labelId}
        aria-describedby={describedBy}
        aria-checked={checked}
        disabled={disabled}
        onClick={() => !disabled && onChange(!checked)}
        className={cn(
          'relative inline-flex shrink-0 rounded-full transition-all duration-200',
          'before:absolute before:-inset-x-1 before:-inset-y-2.5',
          styles.track,
          checked
            ? 'bg-primary shadow-lg shadow-primary/25'
            : 'bg-muted hover:bg-muted-foreground/20',
          disabled && 'cursor-not-allowed opacity-50'
        )}
      >
        <span
          className={cn(
            'absolute left-0.5 top-0.5 rounded-full shadow-md transition-transform duration-200',
            checked ? 'bg-primary-foreground' : 'bg-muted-foreground',
            styles.thumb,
            checked && styles.translate
          )}
        />
      </button>
    );
  }
);
SettingToggle.displayName = 'SettingToggle';

// =============================================================================
// Slider - With value display
// =============================================================================
interface SettingSliderProps {
  value: number;
  onChange: (value: number) => void;
  min: number;
  max: number;
  step?: number;
  disabled?: boolean;
  showValue?: boolean;
  unit?: string;
  formatValue?: (value: number) => string;
  className?: string;
}

export const SettingSlider = ({
  value,
  onChange,
  min,
  max,
  step = 1,
  disabled,
  showValue = true,
  unit,
  formatValue,
  className,
}: SettingSliderProps) => {
  const { labelId, describedBy, disabled: rowDisabled } = useContext(SettingLabelContext) ?? {};
  disabled = disabled || rowDisabled;
  const displayValue = formatValue ? formatValue(value) : value;
  const percentage = ((value - min) / (max - min)) * 100;

  return (
    <div className={cn('w-full', className)}>
      {showValue && (
        <div className="mb-2 flex items-center justify-between text-sm">
          <span className="text-muted-foreground">Value</span>
          <span className="font-mono text-primary">
            {displayValue}
            {unit && <span className="text-muted-foreground ml-1">{unit}</span>}
          </span>
        </div>
      )}
      <div className="relative">
        <div className="pointer-events-none absolute inset-0 flex items-center">
          <div className="h-2 w-full rounded-full bg-muted">
            <div
              className="h-full rounded-full bg-gradient-to-r from-primary to-primary-hover transition-all"
              style={{ width: `${percentage}%` }}
            />
          </div>
        </div>
        <input
          id={labelId ? `${labelId}-control` : undefined}
        aria-labelledby={labelId}
        aria-describedby={describedBy}
          aria-valuetext={unit || formatValue ? `${displayValue}${unit ? ` ${unit}` : ''}` : undefined}
          type="range"
          min={min}
          max={max}
          step={step}
          value={value}
          onChange={(e) => onChange(parseFloat(e.target.value))}
          disabled={disabled}
          className={cn(
            'relative w-full h-10 sm:h-8 bg-transparent appearance-none cursor-pointer',
            '[&::-webkit-slider-thumb]:appearance-none',
            '[&::-webkit-slider-thumb]:w-5 [&::-webkit-slider-thumb]:h-5',
            'sm:[&::-webkit-slider-thumb]:w-4 sm:[&::-webkit-slider-thumb]:h-4',
            '[&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-white',
            '[&::-webkit-slider-thumb]:shadow-lg [&::-webkit-slider-thumb]:shadow-primary/30',
            '[&::-webkit-slider-thumb]:border-2 [&::-webkit-slider-thumb]:border-primary',
            '[&::-webkit-slider-thumb]:transition-transform [&::-webkit-slider-thumb]:hover:scale-110',
            '[&::-moz-range-thumb]:w-5 [&::-moz-range-thumb]:h-5',
            'sm:[&::-moz-range-thumb]:w-4 sm:[&::-moz-range-thumb]:h-4',
            '[&::-moz-range-thumb]:rounded-full [&::-moz-range-thumb]:bg-white',
            '[&::-moz-range-thumb]:border-2 [&::-moz-range-thumb]:border-primary',
            disabled && 'opacity-50 cursor-not-allowed'
          )}
        />
      </div>
    </div>
  );
};

// =============================================================================
// Select - Styled dropdown
// =============================================================================
interface SettingSelectOption {
  value: string | number;
  label: string;
  description?: string;
}

interface SettingSelectProps {
  value: string | number;
  onChange: (value: string | number) => void;
  options: SettingSelectOption[];
  disabled?: boolean;
  placeholder?: string;
  className?: string;
}

export const SettingSelect = ({
  value,
  onChange,
  options,
  disabled,
  placeholder,
  className,
}: SettingSelectProps) => {
  const { labelId, describedBy, disabled: rowDisabled } = useContext(SettingLabelContext) ?? {};
  disabled = disabled || rowDisabled;
  const isNumber = options.some(opt => typeof opt.value === 'number');
  const stringValue = String(value);

  return (
    <SelectPrimitive.Root
      value={stringValue}
      onValueChange={(val) => onChange(isNumber ? parseFloat(val) : val)}
      disabled={disabled}
    >
      <SelectPrimitive.Trigger
        id={labelId ? `${labelId}-control` : undefined}
        aria-labelledby={labelId}
        aria-describedby={describedBy}
        className={cn(
          'flex items-center justify-between gap-2 w-full min-w-0 rounded-xl border border-input bg-background px-3 py-2.5 text-sm text-foreground max-sm:min-h-11 max-sm:w-full',
          'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
          'transition-all duration-150',
          'data-[placeholder]:text-muted-foreground',
          disabled && 'opacity-50 cursor-not-allowed',
          className
        )}
      >
        <span className="min-w-0 truncate">
          <SelectPrimitive.Value placeholder={placeholder} />
        </span>
        <SelectPrimitive.Icon className="shrink-0">
          <ChevronDown size={16} className="text-muted-foreground" />
        </SelectPrimitive.Icon>
      </SelectPrimitive.Trigger>

      <SelectPrimitive.Portal>
        <SelectPrimitive.Content
          className={cn(
            'relative z-50 min-w-[max(8rem,var(--radix-select-trigger-width))] max-w-[calc(100vw-2rem)] overflow-hidden rounded-xl border border-border',
            'max-h-[var(--radix-select-content-available-height)]',
            'bg-popover text-popover-foreground shadow-lg',
            'data-[state=open]:animate-in data-[state=closed]:animate-out',
            'data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
            'data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95',
            'data-[side=bottom]:slide-in-from-top-2 data-[side=top]:slide-in-from-bottom-2'
          )}
          position="popper"
          sideOffset={4}
        >
          <SelectPrimitive.Viewport className="p-1">
            {options.map((option) => (
              <SelectPrimitive.Item
                key={option.value}
                value={String(option.value)}
                className={cn(
                  'relative flex w-full cursor-pointer select-none items-center rounded-lg py-2.5 pl-8 pr-3 text-sm outline-none sm:py-2',
                  'focus:bg-accent focus:text-accent-foreground',
                  'data-[disabled]:pointer-events-none data-[disabled]:opacity-50'
                )}
              >
                <span className="absolute left-2 flex h-4 w-4 items-center justify-center">
                  <SelectPrimitive.ItemIndicator>
                    <Check size={14} />
                  </SelectPrimitive.ItemIndicator>
                </span>
                <SelectPrimitive.ItemText>{option.label}</SelectPrimitive.ItemText>
              </SelectPrimitive.Item>
            ))}
          </SelectPrimitive.Viewport>
        </SelectPrimitive.Content>
      </SelectPrimitive.Portal>
    </SelectPrimitive.Root>
  );
};

// =============================================================================
// Radio Cards - Visual option selection
// =============================================================================
interface RadioCardOption {
  value: string | number;
  label: string;
  description?: string;
  icon?: LucideIcon;
}

interface SettingRadioCardsProps {
  value: string | number;
  onChange: (value: string | number) => void;
  options: RadioCardOption[];
  disabled?: boolean;
  columns?: 2 | 3 | 4;
  className?: string;
}

export const SettingRadioCards = ({
  value,
  onChange,
  options,
  disabled,
  columns = 3,
  className,
}: SettingRadioCardsProps) => {
  const gridCols = {
    2: 'grid-cols-2',
    3: options.length <= 3 ? 'grid-cols-3' : 'grid-cols-2 sm:grid-cols-3',
    4: 'grid-cols-2 sm:grid-cols-4',
  };

  return (
    <div className={cn('grid gap-2', gridCols[columns], className)}>
      {options.map((option) => {
        const Icon = option.icon;
        const isSelected = value === option.value;

        return (
          <button
            key={option.value}
            aria-pressed={isSelected}
            type="button"
            onClick={() => !disabled && onChange(option.value)}
            disabled={disabled}
            className={cn(
              'flex min-w-0 flex-col items-center gap-2 rounded-xl border p-3 transition-all duration-150',
              isSelected
                ? 'border-primary/50 bg-primary/10 text-primary shadow-sm shadow-primary/10'
                : 'border-border bg-muted/30 text-muted-foreground hover:border-border-hover hover:bg-muted/50 hover:text-foreground',
              disabled && 'opacity-50 cursor-not-allowed'
            )}
          >
            {Icon && <Icon size={20} />}
            <span className="max-w-full text-xs font-medium text-center break-words">{option.label}</span>
            {option.description && (
              <span className="max-w-full text-xs text-muted-foreground text-center break-words">{option.description}</span>
            )}
          </button>
        );
      })}
    </div>
  );
};

// =============================================================================
// Radio Buttons - Inline button group
// =============================================================================
interface RadioButtonOption {
  value: string | number;
  label: string;
  icon?: LucideIcon;
}

interface SettingRadioButtonsProps {
  value: string | number;
  onChange: (value: string | number) => void;
  options: RadioButtonOption[];
  disabled?: boolean;
  fullWidth?: boolean;
  className?: string;
}

export const SettingRadioButtons = ({
  value,
  onChange,
  options,
  disabled,
  fullWidth = false,
  className,
}: SettingRadioButtonsProps) => {
  return (
    <div className={cn('inline-flex gap-1 p-1 rounded-xl bg-muted/50', fullWidth && 'w-full', className)}>
      {options.map((option) => {
        const Icon = option.icon;
        const isSelected = value === option.value;

        return (
          <button
            key={option.value}
            aria-pressed={isSelected}
            type="button"
            onClick={() => !disabled && onChange(option.value)}
            disabled={disabled}
            className={cn(
              'flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium transition-all duration-150',
              fullWidth && 'flex-1 justify-center',
              isSelected
                ? 'bg-primary text-primary-foreground shadow-lg shadow-primary/25'
                : 'text-muted-foreground hover:bg-muted hover:text-foreground',
              disabled && 'opacity-50 cursor-not-allowed'
            )}
          >
            {Icon && <Icon size={16} />}
            {option.label}
          </button>
        );
      })}
    </div>
  );
};

// =============================================================================
// Input - Text input field
// =============================================================================
interface SettingInputProps {
  value: string | number;
  onChange: (value: string | number) => void;
  type?: 'text' | 'number' | 'email' | 'password';
  placeholder?: string;
  disabled?: boolean;
  min?: number;
  max?: number;
  step?: number;
  className?: string;
}

export const SettingInput = ({
  value,
  onChange,
  type = 'text',
  placeholder,
  disabled,
  min,
  max,
  step,
  className,
}: SettingInputProps) => {
  const { labelId, describedBy, disabled: rowDisabled } = useContext(SettingLabelContext) ?? {};
  disabled = disabled || rowDisabled;
  const [draft, setDraft] = useState<string | null>(null);
  return (
    <input
          id={labelId ? `${labelId}-control` : undefined}
        aria-labelledby={labelId}
        aria-describedby={describedBy}
      type={type}
      value={type === 'number' ? draft ?? value : value}
      onChange={(e) => {
        if (type !== 'number') {
          onChange(e.target.value);
          return;
        }
        setDraft(e.target.value);
        if (Number.isFinite(e.target.valueAsNumber) && e.target.validity.valid) onChange(e.target.valueAsNumber);
      }}
      onBlur={(event) => {
        if (type !== 'number') return;
        if (event.target.value === '') setDraft(null);
        else if (event.target.reportValidity()) setDraft(null);
      }}
      placeholder={placeholder}
      disabled={disabled}
      min={min}
      max={max}
      step={step}
      className={cn(
        'w-full rounded-xl border border-input bg-background px-3 py-2.5 text-sm text-foreground max-sm:min-h-11 max-sm:w-full',
        'placeholder:text-muted-foreground',
        'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
        'transition-all duration-150',
        disabled && 'opacity-50 cursor-not-allowed',
        className
      )}
    />
  );
};

// =============================================================================
// Notice Box - Info/Warning/Success messages
// =============================================================================
interface SettingNoticeProps {
  variant: 'info' | 'warning' | 'success' | 'destructive';
  title?: string;
  children: ReactNode;
  icon?: LucideIcon;
  className?: string;
}

export const SettingNotice = ({
  variant,
  title,
  children,
  icon: CustomIcon,
  className,
}: SettingNoticeProps) => {
  const styles = {
    info: {
      bg: 'bg-primary/10 border-primary/30',
      title: 'text-primary',
      text: 'text-foreground/80',
    },
    warning: {
      bg: 'bg-warning/10 border-warning/30',
      title: 'text-warning',
      text: 'text-foreground/80',
    },
    success: {
      bg: 'bg-success/10 border-success/30',
      title: 'text-success',
      text: 'text-foreground/80',
    },
    destructive: {
      bg: 'bg-destructive/10 border-destructive/30',
      title: 'text-destructive',
      text: 'text-foreground/80',
    },
  };

  const variantStyles = styles[variant];
  const Icon = CustomIcon || (variant === 'warning' ? AlertTriangle : Info);

  return (
    <div className={cn('rounded-xl border p-4', variantStyles.bg, className)}>
      <div className="flex items-start gap-3">
        <Icon size={20} className={cn('mt-0.5 shrink-0', variantStyles.title)} />
        <div className="min-w-0 flex-1">
          {title && (
            <h4 className={cn('text-sm font-semibold', variantStyles.title)}>{title}</h4>
          )}
          <div className={cn('text-xs', variantStyles.text, title && 'mt-1')}>{children}</div>
        </div>
      </div>
    </div>
  );
};

// =============================================================================
// Action Button - Styled action buttons
// =============================================================================
interface SettingActionButtonProps {
  onClick: () => void;
  variant?: 'default' | 'primary' | 'destructive' | 'ghost';
  size?: 'sm' | 'md' | 'lg';
  disabled?: boolean;
  loading?: boolean;
  icon?: LucideIcon;
  children: ReactNode;
  className?: string;
}

export const SettingActionButton = ({
  onClick,
  variant = 'default',
  size = 'md',
  disabled,
  loading,
  icon: Icon,
  children,
  className,
}: SettingActionButtonProps) => {
  const variantStyles = {
    default: 'bg-muted text-foreground hover:bg-accent border border-border',
    primary: 'bg-primary text-primary-foreground hover:bg-primary-hover shadow-lg shadow-primary/25',
    destructive: 'bg-destructive/10 text-destructive border border-destructive/30 hover:bg-destructive/20',
    ghost: 'text-muted-foreground hover:bg-muted hover:text-foreground',
  };

  const sizeStyles = {
    sm: 'min-h-10 sm:min-h-0 px-3 py-1.5 text-xs gap-1.5',
    md: 'max-sm:min-h-11 px-4 py-2.5 text-sm gap-2',
    lg: 'px-5 py-3 text-base gap-2.5',
  };

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled || loading}
      className={cn(
        'inline-flex items-center justify-center font-medium rounded-xl transition-all duration-150',
        variantStyles[variant],
        sizeStyles[size],
        (disabled || loading) && 'opacity-50 cursor-not-allowed',
        className
      )}
    >
      {loading ? (
        <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" />
      ) : Icon ? (
        <Icon size={size === 'sm' ? 14 : size === 'md' ? 16 : 18} />
      ) : null}
      {children}
    </button>
  );
};

// =============================================================================
// Status Indicator - Shows on/off status
// =============================================================================
interface SettingStatusProps {
  enabled: boolean;
  enabledText?: string;
  disabledText?: string;
  showDot?: boolean;
  className?: string;
}

export const SettingStatus = ({
  enabled,
  enabledText = 'Enabled',
  disabledText = 'Disabled',
  showDot = true,
  className,
}: SettingStatusProps) => {
  return (
    <div className={cn('flex items-center gap-2 text-xs', className)}>
      {showDot && (
        <span
          className={cn(
            'h-2 w-2 rounded-full',
            enabled ? 'bg-success' : 'bg-muted-foreground'
          )}
        />
      )}
      <span className={enabled ? 'text-success' : 'text-muted-foreground'}>
        {enabled ? enabledText : disabledText}
      </span>
    </div>
  );
};

// =============================================================================
// Keyboard Shortcut Display
// =============================================================================
interface SettingKeybindProps {
  keys: string;
  className?: string;
}

export const SettingKeybind = ({ keys, className }: SettingKeybindProps) => {
  const isMac = typeof navigator !== 'undefined' && navigator.platform.toUpperCase().indexOf('MAC') >= 0;
  const displayKeys = isMac ? keys.replace('Ctrl', '\u2318') : keys;
  const keyParts = displayKeys.split('+');

  return (
    <div className={cn('inline-flex items-center gap-1', className)}>
      {keyParts.map((key, index) => (
        <kbd
          key={index}
          className="inline-flex h-6 min-w-[1.5rem] items-center justify-center rounded-md bg-muted px-2 font-mono text-xs text-foreground border border-border"
        >
          {key}
        </kbd>
      ))}
    </div>
  );
};

// =============================================================================
// Empty State
// =============================================================================
interface SettingEmptyStateProps {
  icon?: LucideIcon;
  title: string;
  description?: string;
  action?: ReactNode;
  className?: string;
}

export const SettingEmptyState = ({
  icon: Icon,
  title,
  description,
  action,
  className,
}: SettingEmptyStateProps) => {
  return (
    <div className={cn('flex flex-col items-center justify-center py-6 text-center', className)}>
      {Icon && (
        <div className="bg-primary/10 text-primary mb-3 flex h-10 w-10 items-center justify-center rounded-2xl">
          <Icon size={20} aria-hidden="true" />
        </div>
      )}
      <h3 className="text-foreground text-sm font-semibold">{title}</h3>
      {description && (
        <p className="text-muted-foreground mt-1 max-w-[320px] text-sm">{description}</p>
      )}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
};
