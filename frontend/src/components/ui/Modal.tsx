import { ReactNode, useRef } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { X, Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';

interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  title?: string;
  description?: string;
  size?: 'sm' | 'md' | 'lg' | 'xl' | 'full';
  closeOnOverlayClick?: boolean;
  closeOnEscape?: boolean;
  showCloseButton?: boolean;
  className?: string;
  children: ReactNode;
  footer?: ReactNode;
}

export const Modal = ({
  isOpen, onClose, title, description, size = 'md',
  closeOnOverlayClick = true, closeOnEscape = true,
  showCloseButton = true, className, children, footer,
}: ModalProps) => {
  const previousFocus = useRef<HTMLElement | null>(null);
  const sizeClasses = {
    sm: 'max-w-md', md: 'max-w-lg', lg: 'max-w-2xl',
    xl: 'max-w-4xl', full: 'max-w-[calc(100vw-2rem)]',
  };

  return (
    <Dialog.Root open={isOpen} onOpenChange={(open) => { if (!open) onClose(); }}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm animate-fade-in" />
        <Dialog.Content
          className={cn(
            'fixed left-1/2 top-1/2 z-50 flex max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-2xl border border-border bg-card shadow-2xl',
            sizeClasses[size], className
          )}
          onOpenAutoFocus={() => { previousFocus.current = document.activeElement as HTMLElement; }}
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            if (previousFocus.current?.isConnected) previousFocus.current.focus();
          }}
          onEscapeKeyDown={(event) => { if (!closeOnEscape) event.preventDefault(); }}
          onPointerDownOutside={(event) => { if (!closeOnOverlayClick) event.preventDefault(); }}
          onInteractOutside={(event) => { if (!closeOnOverlayClick) event.preventDefault(); }}
          {...(!description ? { 'aria-describedby': undefined } : {})}
        >
          <div className={cn('flex shrink-0 items-start justify-between gap-4 border-b border-border px-4 py-4 sm:px-6', !title && !showCloseButton && 'sr-only')}>
            <div className="min-w-0">
              <Dialog.Title className={cn('text-lg font-semibold text-foreground', !title && 'sr-only')}>
                {title || 'Dialog'}
              </Dialog.Title>
              {description && <Dialog.Description className="mt-1 text-sm text-muted-foreground">{description}</Dialog.Description>}
            </div>
            {showCloseButton && (
              <Dialog.Close className="shrink-0 rounded-lg p-2 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground" aria-label="Close modal">
                <X size={20} />
              </Dialog.Close>
            )}
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 py-4 sm:px-6 custom-scrollbar">{children}</div>
          {footer && <div className="shrink-0 border-t border-border px-4 py-4 sm:px-6">{footer}</div>}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
};

interface ConfirmModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void | Promise<void>;
  title: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
  variant?: 'danger' | 'warning' | 'info';
  isLoading?: boolean;
}

export const ConfirmModal = ({
  isOpen,
  onClose,
  onConfirm,
  title,
  message,
  confirmText = 'Confirm',
  cancelText = 'Cancel',
  variant = 'info',
  isLoading = false,
}: ConfirmModalProps) => {
  const handleConfirm = async () => {
    await onConfirm();
    onClose();
  };

  const variantClasses = {
    danger: 'bg-destructive text-destructive-foreground hover:bg-destructive-hover',
    warning: 'bg-warning text-warning-foreground hover:bg-warning/90',
    info: 'bg-primary text-primary-foreground hover:bg-primary-hover',
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={title}
      size="sm"
      footer={
        <div className="flex justify-end gap-3">
          <button
            onClick={onClose}
            disabled={isLoading}
            className={cn(
              'rounded-xl px-4 py-2.5 text-sm font-medium',
              'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground',
              'disabled:opacity-50 transition-colors duration-150'
            )}
          >
            {cancelText}
          </button>
          <button
            onClick={() => void handleConfirm()}
            disabled={isLoading}
            className={cn(
              'inline-flex items-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium',
              'disabled:opacity-50 transition-colors duration-150',
              variantClasses[variant]
            )}
          >
            {isLoading && <Loader2 size={16} className="animate-spin" />}
            {isLoading ? 'Loading...' : confirmText}
          </button>
        </div>
      }
    >
      <p className="text-foreground">{message}</p>
    </Modal>
  );
};
