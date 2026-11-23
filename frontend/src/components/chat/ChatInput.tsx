import { forwardRef, useRef, useEffect, useCallback, useState } from 'react';
import { Send, Paperclip, StopCircle, X, AlertCircle, Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';

// Maximum message length in characters
const MAX_MESSAGE_LENGTH = 32768;

export interface ChatInputProps {
  value: string;
  onChange: (value: string) => void;
  onSend: () => void;
  onStop?: () => void;
  isGenerating?: boolean;
  isDisabled?: boolean;
  placeholder?: string;
  attachments?: File[];
  onAttachmentsChange?: (files: File[]) => void;
  onFileSelect?: (files: File[]) => void;
  acceptedFileTypes?: string;
  showCharacterCount?: boolean;
  className?: string;
  /** Group chat specific */
  isPaused?: boolean;
  currentSpeakerName?: string;
  processingLabel?: string;
}

export const ChatInput = forwardRef<HTMLTextAreaElement, ChatInputProps>(
  (
    {
      value,
      onChange,
      onSend,
      onStop,
      isGenerating = false,
      isDisabled = false,
      placeholder = 'Message…',
      attachments = [],
      onAttachmentsChange,
      onFileSelect,
      acceptedFileTypes = 'image/*,.pdf,.txt,.md,.json',
      showCharacterCount = true,
      className,
      isPaused = false,
      currentSpeakerName,
      processingLabel,
    },
    ref
  ) => {
    const textareaRef = useRef<HTMLTextAreaElement | null>(null);
    const fileInputRef = useRef<HTMLInputElement>(null);
    const [isFocused, setIsFocused] = useState(false);

    // Combine refs
    const combinedRef = useCallback(
      (node: HTMLTextAreaElement | null) => {
        textareaRef.current = node;
        if (ref) {
          if (typeof ref === 'function') {
            ref(node);
          } else {
            ref.current = node;
          }
        }
      },
      [ref]
    );

    // Auto-resize textarea
    useEffect(() => {
      const textarea = textareaRef.current;
      if (!textarea) return;

      const resize = () => {
        textarea.style.height = 'auto';
        textarea.style.height = `${Math.min(textarea.scrollHeight, 200)}px`;
      };
      resize();

      // Reflow existing text when the viewport or surrounding panels change width.
      if (typeof ResizeObserver === 'undefined') return;
      let previousWidth = textarea.clientWidth;
      const observer = new ResizeObserver(() => {
        if (textarea.clientWidth === previousWidth) return;
        previousWidth = textarea.clientWidth;
        resize();
      });
      observer.observe(textarea);
      return () => observer.disconnect();
    }, [value]);

    // Focus textarea when not generating
    useEffect(() => {
      if (textareaRef.current && !isGenerating && !isDisabled) {
        textareaRef.current.focus();
      }
    }, [isGenerating, isDisabled]);

    const handleInputChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      const newValue = e.target.value;
      if (newValue.length <= MAX_MESSAGE_LENGTH) {
        onChange(newValue);
      }
    };

    const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
        e.preventDefault();
        if (!isGenerating && !isDisabled && !isPaused && (value.trim() || attachments.length > 0)) {
          onSend();
        }
      }
    };

    const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
      const files = Array.from(e.target.files || []);
      if (files.length > 0) {
        onFileSelect?.(files);
      }
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
    };

    const removeAttachment = (index: number) => {
      const newAttachments = attachments.filter((_, i) => i !== index);
      onAttachmentsChange?.(newAttachments);
    };

    // Message length calculations
    const messageLength = value.length;
    const isNearLimit = messageLength > MAX_MESSAGE_LENGTH * 0.9;
    const isAtLimit = messageLength >= MAX_MESSAGE_LENGTH;
    const canSend = (value.trim() || attachments.length > 0) && !isGenerating && !isDisabled && !isPaused;

    // Dynamic placeholder
    const dynamicPlaceholder = isGenerating
      ? processingLabel || (currentSpeakerName
        ? `${currentSpeakerName} is responding...`
        : 'AI is responding...')
      : isPaused
        ? 'Conversation paused...'
        : placeholder;

    return (
      <div className={cn('chat-input-container', className)}>
        {/* Attachments preview */}
        {attachments.length > 0 && (
          <div className="chat-input-attachments">
            {attachments.map((file, index) => (
              <div key={index} className="chat-input-attachment">
                <Paperclip size={14} className="text-muted-foreground flex-shrink-0" />
                <span className="truncate max-w-[120px]">{file.name}</span>
                <button
                  onClick={() => removeAttachment(index)}
                  className="chat-input-attachment-remove"
                  aria-label={`Remove ${file.name}`}
                >
                  <X size={14} />
                </button>
              </div>
            ))}
          </div>
        )}

        {/* Main input area */}
        <div
          className={cn(
            'chat-input-wrapper',
            isFocused && 'chat-input-wrapper-focused',
            isGenerating && 'chat-input-wrapper-generating',
            isDisabled && 'chat-input-wrapper-disabled'
          )}
        >
          {/* Textarea container */}
          <div className="chat-input-textarea-container">
            <textarea
              ref={combinedRef}
              value={value}
              onChange={handleInputChange}
              onKeyDown={handleKeyDown}
              onFocus={() => setIsFocused(true)}
              onBlur={() => setIsFocused(false)}
              placeholder={dynamicPlaceholder}
              disabled={isGenerating || isDisabled || isPaused}
              className="chat-input-textarea"
              rows={1}
              maxLength={MAX_MESSAGE_LENGTH}
              aria-label="Message input"
            />
          </div>

          {/* Action buttons */}
          <div className="chat-input-actions">
            {/* Attachment button */}
            <button
              onClick={() => fileInputRef.current?.click()}
              disabled={isGenerating || isDisabled || !onFileSelect}
              className="chat-input-action-btn"
              aria-label="Attach file"
              title="Attach file"
            >
              <Paperclip size={20} />
            </button>

            <input
              ref={fileInputRef}
              type="file"
              multiple
              onChange={handleFileSelect}
              className="hidden"
              accept={acceptedFileTypes}
            />

            {/* Send/Stop button */}
            {isGenerating ? (
              <button
                onClick={onStop}
                className="chat-input-stop-btn"
                aria-label="Stop generation"
                title="Stop generation"
              >
                <StopCircle size={20} />
              </button>
            ) : (
              <button
                onClick={onSend}
                disabled={!canSend}
                className={cn(
                  'chat-input-send-btn',
                  canSend && 'chat-input-send-btn-active'
                )}
                aria-label="Send message"
                title="Send message"
              >
                <Send size={20} />
              </button>
            )}
          </div>
        </div>

        {/* Footer info */}
        <div className="chat-input-footer">
          <div className="chat-input-hints">
            <kbd className="chat-input-kbd">Enter</kbd>
            <span>to send</span>
            <span className="text-border">|</span>
            <kbd className="chat-input-kbd">Shift + Enter</kbd>
            <span>for new line</span>
            {isGenerating && (
              <>
                <span className="text-border">|</span>
                <span className="chat-input-status">
                  <Loader2 size={12} className="animate-spin" />
                  {processingLabel || (currentSpeakerName ? `${currentSpeakerName} is typing...` : 'AI is typing...')}
                </span>
              </>
            )}
          </div>

          {/* Character counter */}
          {showCharacterCount && messageLength > 0 && (
            <div
              className={cn(
                'chat-input-counter',
                isAtLimit && 'chat-input-counter-limit',
                isNearLimit && !isAtLimit && 'chat-input-counter-warning'
              )}
            >
              {isNearLimit && <AlertCircle size={12} />}
              <span>
                {messageLength.toLocaleString()} / {MAX_MESSAGE_LENGTH.toLocaleString()}
              </span>
            </div>
          )}
        </div>
      </div>
    );
  }
);

ChatInput.displayName = 'ChatInput';

/**
 * Minimal variant for embedding in tight spaces
 */
export interface ChatInputMinimalProps {
  value: string;
  onChange: (value: string) => void;
  onSend: () => void;
  isGenerating?: boolean;
  placeholder?: string;
  className?: string;
}

export const ChatInputMinimal = ({
  value,
  onChange,
  onSend,
  isGenerating = false,
  placeholder = 'Type a message...',
  className,
}: ChatInputMinimalProps) => {
  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing && value.trim() && !isGenerating) {
      e.preventDefault();
      onSend();
    }
  };

  return (
    <div className={cn('chat-input-minimal', className)}>
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        disabled={isGenerating}
        className="chat-input-minimal-input"
        aria-label="Message input"
      />
      <button
        onClick={onSend}
        disabled={!value.trim() || isGenerating}
        aria-label="Send message"
        className={cn(
          'chat-input-minimal-btn',
          value.trim() && !isGenerating && 'chat-input-minimal-btn-active'
        )}
      >
        {isGenerating ? <Loader2 size={18} className="animate-spin" /> : <Send size={18} />}
      </button>
    </div>
  );
};

export default ChatInput;
