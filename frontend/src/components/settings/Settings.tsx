import { useState, useEffect } from 'react';
import {
  Sliders, Palette, Shield, Bell,
  Keyboard, Database, Save, RotateCcw, Download, Upload,
  AlertTriangle, X
} from 'lucide-react';
import { toast } from 'react-hot-toast';
import { Modal } from '@/components/ui/Modal';
import { LLMSettings } from './LLMSettings';
import { UISettings } from './UISettings';
import { PrivacySettings } from './PrivacySettings';
import { NotificationSettings } from './NotificationSettings';
import { KeyboardSettings } from './KeyboardSettings';
import { DataManagement } from './DataManagement';
import { useSettingsStore } from '@/stores/settingsStore';
import { cn } from '@/lib/utils';

interface SettingsProps {
  isOpen: boolean;
  onClose: () => void;
}

type SettingsTab = 'llm' | 'ui' | 'privacy' | 'notifications' | 'keyboard' | 'data';

export const Settings = ({ isOpen, onClose }: SettingsProps) => {
  const [activeTab, setActiveTab] = useState<SettingsTab>('llm');
  const {
    hasUnsavedChanges,
    isLoading,
    error,
    saveSettings,
    resetSettings,
    exportSettings,
    importSettings,
    clearError,
  } = useSettingsStore();

  const [showResetConfirm, setShowResetConfirm] = useState(false);
  const [showDiscardConfirm, setShowDiscardConfirm] = useState(false);

  useEffect(() => {
    if (error) {
      const timer = setTimeout(clearError, 5000);
      return () => clearTimeout(timer);
    }
    return undefined;
  }, [error, clearError]);

  const handleSave = async () => {
    try {
      await saveSettings();
      toast.success('Settings saved');
    } catch (error) {
      console.error('Failed to save settings:', error);
      toast.error('Failed to save settings');
    }
  };

  const handleReset = async () => {
    try {
      await resetSettings();
      setShowResetConfirm(false);
      toast.success('Settings reset to defaults');
    } catch (error) {
      console.error('Failed to reset settings:', error);
      toast.error('Failed to reset settings');
    }
  };

  const handleExport = () => {
    const data = exportSettings();
    const blob = new Blob([data], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `chatllm-settings-${new Date().toISOString().split('T')[0]}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleImport = () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json';
    input.onchange = async (e) => {
      const file = (e.target as HTMLInputElement).files?.[0];
      if (!file) return;

      try {
        const text = await file.text();
        await importSettings(text);
      } catch (error) {
        console.error('Failed to import settings:', error);
      }
    };
    input.click();
  };

  const handleClose = () => {
    if (hasUnsavedChanges) {
      setShowDiscardConfirm(true);
    } else {
      onClose();
    }
  };

  const tabs = [
    { id: 'llm' as const, label: 'LLM', icon: Sliders, description: 'Model parameters' },
    { id: 'ui' as const, label: 'Appearance', icon: Palette, description: 'Theme & display' },
    { id: 'privacy' as const, label: 'Privacy', icon: Shield, description: 'Data & security' },
    { id: 'notifications' as const, label: 'Notifications', icon: Bell, description: 'Alerts & sounds' },
    { id: 'keyboard' as const, label: 'Keyboard', icon: Keyboard, description: 'Shortcuts' },
    { id: 'data' as const, label: 'Data', icon: Database, description: 'Import & export' },
  ];

  const renderTabContent = () => {
    switch (activeTab) {
      case 'llm':
        return <LLMSettings />;
      case 'ui':
        return <UISettings />;
      case 'privacy':
        return <PrivacySettings />;
      case 'notifications':
        return <NotificationSettings />;
      case 'keyboard':
        return <KeyboardSettings />;
      case 'data':
        return <DataManagement />;
      default:
        return null;
    }
  };

  return (
    <>
      <Modal
        isOpen={isOpen}
        onClose={handleClose}
        title="Settings"
        size="xl"
        className="h-[80vh]"
        footer={
          <div className="flex items-center justify-between">
            <div className="flex gap-2">
              <button
                onClick={handleExport}
                className={cn(
                  'flex items-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium',
                  'bg-muted text-foreground hover:bg-accent border border-border',
                  'transition-colors'
                )}
              >
                <Download size={16} />
                <span>Export</span>
              </button>
              <button
                onClick={handleImport}
                className={cn(
                  'flex items-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium',
                  'bg-muted text-foreground hover:bg-accent border border-border',
                  'transition-colors'
                )}
              >
                <Upload size={16} />
                <span>Import</span>
              </button>
            </div>

            <div className="flex items-center gap-3">
              {hasUnsavedChanges && (
                <div className="flex items-center gap-2 text-sm text-warning">
                  <div className="h-2 w-2 rounded-full bg-warning animate-pulse" />
                  Unsaved changes
                </div>
              )}

              <button
                onClick={() => setShowResetConfirm(true)}
                className={cn(
                  'flex items-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium',
                  'bg-muted text-foreground hover:bg-accent border border-border',
                  'transition-colors'
                )}
              >
                <RotateCcw size={16} />
                <span>Reset</span>
              </button>

              <button
                onClick={handleClose}
                className={cn(
                  'rounded-xl px-4 py-2.5 text-sm font-medium',
                  'bg-muted text-foreground hover:bg-accent border border-border',
                  'transition-colors'
                )}
              >
                Cancel
              </button>

              <button
                onClick={() => void handleSave()}
                disabled={!hasUnsavedChanges || isLoading}
                className={cn(
                  'flex items-center gap-2 rounded-xl px-5 py-2.5 text-sm font-medium',
                  'bg-primary text-primary-foreground hover:bg-primary-hover',
                  'shadow-lg shadow-primary/25 hover:shadow-primary/40',
                  'transition-all disabled:opacity-50 disabled:shadow-none'
                )}
              >
                {isLoading ? (
                  <>
                    <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" />
                    <span>Saving...</span>
                  </>
                ) : (
                  <>
                    <Save size={16} />
                    <span>Save Changes</span>
                  </>
                )}
              </button>
            </div>
          </div>
        }
      >
        <div className="flex h-full gap-6">
          {/* Sidebar */}
          <nav className="w-52 shrink-0">
            <ul className="space-y-1">
              {tabs.map((tab) => {
                const Icon = tab.icon;
                const isActive = activeTab === tab.id;
                return (
                  <li key={tab.id}>
                    <button
                      onClick={() => setActiveTab(tab.id)}
                      className={cn(
                        'flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left transition-all',
                        isActive
                          ? 'bg-primary/10 text-primary'
                          : 'text-muted-foreground hover:bg-accent hover:text-foreground'
                      )}
                    >
                      <div
                        className={cn(
                          'flex h-9 w-9 items-center justify-center rounded-lg',
                          isActive ? 'bg-primary/20' : 'bg-muted'
                        )}
                      >
                        <Icon size={18} />
                      </div>
                      <div>
                        <span className="text-sm font-medium">{tab.label}</span>
                        <p
                          className={cn(
                            'text-xs',
                            isActive ? 'text-primary' : 'text-muted-foreground'
                          )}
                        >
                          {tab.description}
                        </p>
                      </div>
                    </button>
                  </li>
                );
              })}
            </ul>
          </nav>

          {/* Content */}
          <div className="flex-1 overflow-y-auto custom-scrollbar pr-2">
            {error && (
              <div className="mb-4 flex items-start gap-3 rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3">
                <AlertTriangle size={18} className="shrink-0 mt-0.5 text-destructive" />
                <div className="flex-1">
                  <p className="text-sm text-destructive font-medium">Error</p>
                  <p className="text-sm text-destructive">{error}</p>
                </div>
                <button
                  onClick={clearError}
                  className="text-destructive hover:bg-destructive/10 transition-colors"
                >
                  <X size={16} />
                </button>
              </div>
            )}

            {renderTabContent()}
          </div>
        </div>
      </Modal>

      {/* Reset confirmation modal */}
      <Modal
        isOpen={showResetConfirm}
        onClose={() => setShowResetConfirm(false)}
        title="Reset All Settings"
        size="sm"
        footer={
          <div className="flex justify-end gap-3">
            <button
              onClick={() => setShowResetConfirm(false)}
              className={cn(
                'rounded-xl px-4 py-2.5 text-sm font-medium',
                'bg-muted text-foreground hover:bg-accent border border-border',
                'transition-colors'
              )}
            >
              Cancel
            </button>
            <button
              onClick={() => void handleReset()}
              className={cn(
                'flex items-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium',
                'bg-destructive text-destructive-foreground hover:bg-destructive/90',
                'transition-colors'
              )}
            >
              <RotateCcw size={16} />
              Reset All Settings
            </button>
          </div>
        }
      >
        <div className="flex items-start gap-3">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-destructive/10">
            <AlertTriangle className="text-destructive" size={20} />
          </div>
          <p className="text-sm text-muted-foreground">
            Are you sure you want to reset all settings to their default values? This action cannot
            be undone.
          </p>
        </div>
      </Modal>

      {/* Discard changes confirmation modal */}
      <Modal
        isOpen={showDiscardConfirm}
        onClose={() => setShowDiscardConfirm(false)}
        title="Unsaved Changes"
        size="sm"
        footer={
          <div className="flex justify-end gap-3">
            <button
              onClick={() => setShowDiscardConfirm(false)}
              className={cn(
                'rounded-xl px-4 py-2.5 text-sm font-medium',
                'bg-muted text-foreground hover:bg-accent border border-border',
                'transition-colors'
              )}
            >
              Keep Editing
            </button>
            <button
              onClick={() => {
                setShowDiscardConfirm(false);
                onClose();
              }}
              className={cn(
                'rounded-xl px-4 py-2.5 text-sm font-medium',
                'bg-destructive text-destructive-foreground hover:bg-destructive/90',
                'transition-colors'
              )}
            >
              Discard Changes
            </button>
          </div>
        }
      >
        <div className="flex items-start gap-3">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-warning/10">
            <AlertTriangle className="text-warning" size={20} />
          </div>
          <p className="text-sm text-muted-foreground">
            You have unsaved changes. Are you sure you want to close without saving?
          </p>
        </div>
      </Modal>
    </>
  );
};
