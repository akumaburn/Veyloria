import { forwardRef, useId, TextareaHTMLAttributes, useEffect, useRef, useState } from 'react';
import { cn } from '@/lib/utils';
import { Modal } from './Modal';
import ReactMarkdown from 'react-markdown';
import { AlertCircle, Maximize2 } from 'lucide-react';

export interface TextareaProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'size'> {
  label?: string;
  error?: string;
  helperText?: string;
  size?: 'sm' | 'md' | 'lg';
  variant?: 'default' | 'filled' | 'ghost';
  resize?: 'none' | 'vertical' | 'horizontal' | 'both' | 'auto';
  maxLength?: number;
  showCount?: boolean;
  autoResize?: boolean;
  minRows?: number;
  maxRows?: number;
  fullscreenEnabled?: boolean;
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(
  (
    {
      className,
      label,
      error,
      helperText,
      size = 'md',
      variant = 'default',
      resize = 'vertical',
      maxLength,
      showCount = false,
      autoResize = false,
      minRows = 3,
      maxRows = 10,
      fullscreenEnabled = false,
      disabled,
      value,
      defaultValue,
      onChange,
      ...props
    },
    ref,
  ) => {
    const [isFullscreen, setIsFullscreen] = useState(false);
    const [draft, setDraft] = useState(defaultValue ?? '');
    const textareaRef = useRef<HTMLTextAreaElement | null>(null);
    const actualValue = value ?? draft;
    const charCount = String(actualValue).length;
    const generatedId = useId();
    const fieldId = props.id || generatedId;
    const helperId = `${fieldId}-help`;
    const errorId = `${fieldId}-error`;
    const describedBy =
      [props['aria-describedby'], helperText ? helperId : undefined, error ? errorId : undefined]
        .filter(Boolean)
        .join(' ') || undefined;
    const sizeClasses = {
      sm: 'px-3 py-2 text-sm',
      md: 'px-3 py-2.5 text-sm',
      lg: 'px-4 py-3 text-base',
    };
    const variantClasses = {
      default: 'border border-input bg-background hover:border-muted-foreground',
      filled: 'border border-transparent bg-muted hover:bg-accent',
      ghost: 'border border-transparent bg-transparent hover:bg-accent/50',
    };
    const resizeClasses = {
      none: 'resize-none',
      vertical: 'resize-y',
      horizontal: 'resize-x',
      both: 'resize',
      auto: 'resize-none',
    };

    useEffect(() => {
      const textarea = textareaRef.current;
      if (!autoResize || !textarea || isFullscreen) return;
      const resizeToContent = () => {
        const style = getComputedStyle(textarea);
        const lineHeight = parseFloat(style.lineHeight) || 24;
        const padding = parseFloat(style.paddingTop) + parseFloat(style.paddingBottom);
        const borders = parseFloat(style.borderTopWidth) + parseFloat(style.borderBottomWidth);
        const maxHeight = Math.max(minRows, maxRows) * lineHeight + padding + borders;
        textarea.style.height = 'auto';
        textarea.style.height = `${Math.min(Math.max(textarea.scrollHeight + borders, minRows * lineHeight + padding + borders), maxHeight)}px`;
      };
      resizeToContent();
      if (typeof ResizeObserver === 'undefined') return;
      let previousWidth = textarea.clientWidth;
      const observer = new ResizeObserver(() => {
        if (textarea.clientWidth === previousWidth) return;
        previousWidth = textarea.clientWidth;
        resizeToContent();
      });
      observer.observe(textarea);
      return () => observer.disconnect();
    }, [actualValue, autoResize, minRows, maxRows, isFullscreen]);

    const textareaElement = (
      <textarea
        {...props}
        ref={(node) => {
          textareaRef.current = node;
          if (typeof ref === 'function') ref(node);
          else if (ref) ref.current = node;
        }}
        id={fieldId}
        disabled={disabled}
        value={actualValue}
        maxLength={maxLength}
        onChange={(event) => {
          if (value === undefined) setDraft(event.target.value);
          onChange?.(event);
        }}
        rows={props.rows ?? minRows}
        className={cn(
          'text-foreground placeholder:text-muted-foreground block min-h-11 w-full rounded-xl',
          'focus:border-primary focus:ring-primary/20 transition-colors focus:ring-2 focus:outline-none',
          'disabled:cursor-not-allowed disabled:opacity-50',
          sizeClasses[size],
          variantClasses[variant],
          autoResize ? 'resize-none' : resizeClasses[resize],
          fullscreenEnabled && !isFullscreen && 'pr-14',
          isFullscreen && 'min-h-[50dvh]',
          error && 'border-destructive focus:border-destructive focus:ring-destructive/20',
          className,
        )}
        aria-invalid={error ? true : props['aria-invalid']}
        aria-describedby={describedBy}
        aria-label={props['aria-label'] || (isFullscreen ? label || 'Text editor' : undefined)}
      />
    );
    const feedback = (error || helperText || showCount) && (
      <div className="mt-1.5 flex items-start justify-between gap-3">
        <div className="min-w-0 space-y-1">
          {helperText && (
            <p id={helperId} className="text-muted-foreground text-xs">
              {helperText}
            </p>
          )}
          {error && (
            <p
              id={errorId}
              role="alert"
              className="text-destructive flex items-start gap-1.5 text-xs"
            >
              <AlertCircle size={14} className="mt-0.5 shrink-0" aria-hidden="true" />
              <span>{error}</span>
            </p>
          )}
        </div>
        {showCount && (
          <span className="text-muted-foreground shrink-0 text-xs tabular-nums">
            {charCount}
            {maxLength !== undefined ? ` / ${maxLength}` : ''}
          </span>
        )}
      </div>
    );

    return (
      <div className="w-full">
        {label && (
          <label htmlFor={fieldId} className="text-foreground mb-1.5 block text-sm font-medium">
            {label}
            {props.required && (
              <span className="text-muted-foreground ml-1 text-xs font-normal">(required)</span>
            )}
          </label>
        )}
        <div className="relative">
          {!isFullscreen && textareaElement}
          {fullscreenEnabled && (
            <button
              type="button"
              onClick={() => setIsFullscreen(true)}
              disabled={disabled}
              aria-label="Expand text editor"
              className="text-muted-foreground hover:bg-accent hover:text-foreground absolute top-1 right-1 flex h-10 w-10 items-center justify-center rounded-lg disabled:opacity-50 sm:h-9 sm:w-9"
            >
              <Maximize2 size={16} />
            </button>
          )}
        </div>
        {!isFullscreen && feedback}
        <Modal
          isOpen={isFullscreen}
          onClose={() => setIsFullscreen(false)}
          title={label || 'Text editor'}
          size="xl"
        >
          {isFullscreen && textareaElement}
          {isFullscreen && feedback}
        </Modal>
      </div>
    );
  },
);

Textarea.displayName = 'Textarea';

interface MarkdownTextareaProps extends TextareaProps {
  preview?: boolean;
  onPreviewChange?: (preview: boolean) => void;
}

export const MarkdownTextarea = forwardRef<HTMLTextAreaElement, MarkdownTextareaProps>(
  ({ preview = false, onPreviewChange, ...props }, ref) => {
    const [showPreview, setShowPreview] = useState(preview);
    const [draft, setDraft] = useState(props.defaultValue ?? '');
    const generatedId = useId();
    const fieldId = props.id || generatedId;
    const actualValue = props.value ?? draft;

    const togglePreview = () => {
      const newValue = !showPreview;
      setShowPreview(newValue);
      onPreviewChange?.(newValue);
    };

    return (
      <div className="w-full">
        <div className="mb-2 flex items-center justify-between">
          {props.label && (
            <label htmlFor={fieldId} className="text-foreground text-sm font-medium">
              {props.label}
            </label>
          )}
          <button
            type="button"
            onClick={togglePreview}
            aria-pressed={showPreview}
            className="bg-muted text-muted-foreground hover:bg-accent min-h-10 rounded-lg px-3 py-1 text-xs sm:min-h-9"
          >
            {showPreview ? 'Edit' : 'Preview'}
          </button>
        </div>

        {showPreview ? (
          <div className="border-border bg-muted min-h-[120px] rounded-lg border p-3">
            <div className="prose prose-sm message-prose max-w-none">
              {String(actualValue) ? (
                <ReactMarkdown>{String(actualValue)}</ReactMarkdown>
              ) : (
                <p className="text-muted-foreground">Write some text to preview it here.</p>
              )}
            </div>
          </div>
        ) : (
          <Textarea
            ref={ref}
            {...props}
            id={fieldId}
            value={actualValue}
            onChange={(event) => {
              setDraft(event.target.value);
              props.onChange?.(event);
            }}
            label={undefined}
            helperText={props.helperText || 'Supports Markdown formatting'}
          />
        )}
      </div>
    );
  },
);

MarkdownTextarea.displayName = 'MarkdownTextarea';

interface CodeTextareaProps extends TextareaProps {
  language?: string;
  lineNumbers?: boolean;
  highlightActiveLine?: boolean;
}

export const CodeTextarea = forwardRef<HTMLTextAreaElement, CodeTextareaProps>(
  (
    { language = 'javascript', lineNumbers = true, highlightActiveLine = true, value, ...props },
    ref,
  ) => {
    const generatedId = useId();
    const fieldId = props.id || generatedId;
    const [activeLineNumber, setActiveLineNumber] = useState(1);
    const lines = typeof value === 'string' ? value.split('\n') : [];

    const handleClick = (e: React.MouseEvent<HTMLTextAreaElement>) => {
      const textarea = e.currentTarget;
      const cursorPosition = textarea.selectionStart;
      const textBeforeCursor = textarea.value.substring(0, cursorPosition);
      const lineNumber = textBeforeCursor.split('\n').length;
      setActiveLineNumber(lineNumber);
    };

    return (
      <div className="w-full">
        {props.label && (
          <div className="mb-1.5 flex items-center justify-between">
            <label htmlFor={fieldId} className="text-foreground text-sm font-medium">
              {props.label}
            </label>
            <span className="text-muted-foreground text-xs">{language}</span>
          </div>
        )}

        <div className="border-border bg-muted relative flex rounded-lg border">
          {lineNumbers && (
            <div className="border-border bg-muted/50 border-r px-2 py-2.5 select-none">
              {Array.from({ length: Math.max(lines.length, 10) }, (_, i) => (
                <div
                  key={i}
                  className={cn(
                    'text-muted-foreground/50 text-xs leading-[1.5rem]',
                    highlightActiveLine && activeLineNumber === i + 1 && 'text-primary',
                  )}
                >
                  {i + 1}
                </div>
              ))}
            </div>
          )}

          <Textarea
            ref={ref}
            value={value}
            onClick={handleClick}
            className="border-0 bg-transparent font-mono"
            resize="none"
            {...props}
            id={fieldId}
            label={undefined}
          />
        </div>
      </div>
    );
  },
);

CodeTextarea.displayName = 'CodeTextarea';
