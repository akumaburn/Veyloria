import { useState } from 'react';
import { Hand, X, Send, AlertTriangle } from 'lucide-react';
import { cn } from '@/lib/utils';

interface InterjectionModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (reason: string, priority: number) => void;
  isLoading?: boolean;
}

export const InterjectionModal = ({
  isOpen,
  onClose,
  onSubmit,
  isLoading = false
}: InterjectionModalProps) => {
  const [reason, setReason] = useState('');
  const [priority, setPriority] = useState(0);

  if (!isOpen) return null;

  const handleSubmit = () => {
    onSubmit(reason, priority);
    setReason('');
    setPriority(0);
  };

  const priorities = [
    { value: 0, label: 'Normal', description: 'Standard priority' },
    { value: 1, label: 'Important', description: 'Higher priority' },
    { value: 2, label: 'Urgent', description: 'Highest priority', icon: AlertTriangle },
  ];

  return (
    <div className={cn(
      'fixed inset-0 z-50 flex items-center justify-center p-4',
      'bg-black/50 backdrop-blur-sm'
    )}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="interjection-modal-title"
        className={cn(
          'flex w-full max-w-md max-h-[calc(100dvh-2rem)] flex-col overflow-hidden',
          'rounded-2xl border border-border bg-background',
          'shadow-2xl animate-fade-up'
        )}
      >
        {/* Header */}
        <div className="flex shrink-0 items-center justify-between gap-3 border-b border-border p-4">
          <div className="flex min-w-0 items-center gap-3">
            <div className={cn(
              'flex h-10 w-10 shrink-0 items-center justify-center rounded-xl',
              'bg-gradient-to-br from-warning to-warning/80 shadow-lg'
            )}>
              <Hand size={20} className="text-warning-foreground" />
            </div>
            <div className="min-w-0">
              <h2 id="interjection-modal-title" className="text-lg font-semibold text-foreground">Request Interjection</h2>
              <p className="text-xs text-muted-foreground">Ask to speak out of turn</p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className={cn(
              'inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-lg text-muted-foreground',
              'hover:bg-accent hover:text-foreground',
              'transition-colors duration-150'
            )}
          >
            <X size={20} />
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-4 space-y-4">
          {/* Priority selection */}
          <div>
            <label className="block text-sm font-medium text-foreground mb-2">
              Priority Level
            </label>
            <div className="flex gap-2">
              {priorities.map((p) => (
                <button
                  key={p.value}
                  type="button"
                  onClick={() => setPriority(p.value)}
                  aria-pressed={priority === p.value}
                  className={cn(
                    'flex-1 flex min-h-11 flex-col items-center justify-center gap-1 rounded-xl border p-2 sm:p-3',
                    'transition-all duration-150',
                    priority === p.value
                      ? 'border-primary bg-primary/10'
                      : 'border-border bg-muted/30 hover:bg-accent/50'
                  )}
                >
                  {p.icon && <p.icon size={16} className={priority === p.value ? 'text-primary' : 'text-muted-foreground'} />}
                  <span className="text-sm font-medium">{p.label}</span>
                  <span className="text-xs text-muted-foreground max-sm:hidden">{p.description}</span>
                </button>
              ))}
            </div>
          </div>

          {/* Reason input */}
          <div>
            <label className="block text-sm font-medium text-foreground mb-1.5">
              Reason (optional)
            </label>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Why do you want to interject?"
              rows={3}
              className={cn(
                'w-full rounded-xl border border-border bg-muted/50',
                'px-4 py-2.5 text-sm text-foreground placeholder-muted-foreground',
                'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
                'resize-none transition-all duration-150'
              )}
            />
          </div>

          {/* Info text */}
          <p className="text-xs text-muted-foreground">
            Requesting an interjection will pause the current conversation flow and allow you to speak
            before the next character responds. Other participants will see your request.
          </p>
        </div>

        {/* Footer */}
        <div className="flex shrink-0 justify-end gap-2 border-t border-border p-4 bg-background-secondary/50">
          <button
            type="button"
            onClick={onClose}
            className={cn(
              'rounded-lg px-4 py-2 text-sm font-medium max-sm:min-h-11 max-sm:flex-1',
              'bg-muted text-muted-foreground',
              'hover:bg-accent hover:text-foreground',
              'transition-colors duration-150'
            )}
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={isLoading}
            className={cn(
              'flex items-center justify-center gap-2 rounded-lg px-4 py-2 text-sm font-medium max-sm:min-h-11 max-sm:flex-1',
              'bg-primary text-primary-foreground',
              'hover:bg-primary-hover',
              'disabled:opacity-50 disabled:cursor-not-allowed',
              'transition-colors duration-150'
            )}
          >
            <Send size={16} />
            {isLoading ? 'Requesting...' : 'Request'}
          </button>
        </div>
      </div>
    </div>
  );
};
