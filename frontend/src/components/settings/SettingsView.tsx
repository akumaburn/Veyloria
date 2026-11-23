import { useState, useEffect } from 'react';
import {
  Sliders, Palette, Shield, Bell,
  Keyboard, Database, Save, RotateCcw, Download, Upload,
  Check, AlertCircle, Bot, Volume2
} from 'lucide-react';
import { LLMSettings } from './LLMSettings';
import { UISettings } from './UISettings';
import { PrivacySettings } from './PrivacySettings';
import { NotificationSettings } from './NotificationSettings';
import { KeyboardSettings } from './KeyboardSettings';
import { DataManagement } from './DataManagement';
import { CharacterSettings } from './CharacterSettings';
import { TTSSettings } from './TTSSettings';
import { useSettingsStore } from '@/stores/settingsStore';
import { cn } from '@/lib/utils';

type SettingsTab = 'llm' | 'characters' | 'tts' | 'ui' | 'privacy' | 'notifications' | 'keyboard' | 'data';

export const SettingsView = () => {
  const [activeTab, setActiveTab] = useState<SettingsTab>('llm');
  const [saveSuccess, setSaveSuccess] = useState(false);
  const {
    hasUnsavedChanges,
    isLoading,
    error,
    saveSettings,
    resetSettings,
    fetchSettings,
    exportSettings,
    importSettings,
    clearError
  } = useSettingsStore();

  useEffect(() => {
    fetchSettings();
  }, [fetchSettings]);

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
      setSaveSuccess(true);
      setTimeout(() => setSaveSuccess(false), 2000);
    } catch (error) {
      console.error('Failed to save settings:', error);
    }
  };

  const handleReset = async () => {
    if (confirm('Are you sure you want to reset all settings to defaults?')) {
      try {
        await resetSettings();
      } catch (error) {
        console.error('Failed to reset settings:', error);
      }
    }
  };

  const handleExport = () => {
    const settingsData = exportSettings();
    const blob = new Blob([settingsData], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `chatllm-settings-${new Date().toISOString().split('T')[0]}.json`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
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

  const tabs = [
    { id: 'llm' as const, label: 'LLM', icon: Sliders, description: 'Model parameters' },
    { id: 'characters' as const, label: 'Characters', icon: Bot, description: 'AI personalities' },
    { id: 'tts' as const, label: 'Text-to-Speech', icon: Volume2, description: 'Voice playback' },
    { id: 'ui' as const, label: 'Appearance', icon: Palette, description: 'Theme & display' },
    { id: 'privacy' as const, label: 'Privacy', icon: Shield, description: 'Data & security' },
    { id: 'notifications' as const, label: 'Notifications', icon: Bell, description: 'Alerts & sounds' },
    { id: 'keyboard' as const, label: 'Keyboard', icon: Keyboard, description: 'Shortcuts' },
    { id: 'data' as const, label: 'Data', icon: Database, description: 'Import & export' },
  ];

  const renderContent = () => {
    switch (activeTab) {
      case 'llm':
        return <LLMSettings />;
      case 'characters':
        return <CharacterSettings />;
      case 'tts':
        return <TTSSettings />;
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
    <div className="flex h-full min-h-0 min-w-0 flex-col bg-background xl:flex-row">
      {/* Sidebar */}
      <div className="shrink-0 border-b border-border bg-card xl:w-60 xl:border-b-0 xl:border-r flex flex-col">
        {/* Tab Navigation */}
        {/*
          A scrolling strip hid the last categories on narrow screens. A grid
          keeps every category visible; the desktop layout stays a sidebar.
        */}
        <nav aria-label="Settings categories" className="grid grid-cols-2 gap-1 p-2 sm:grid-cols-4 xl:block xl:flex-1 xl:space-y-1 xl:overflow-y-auto xl:p-4">
          {tabs.map((tab) => {
            const Icon = tab.icon;
            const isActive = activeTab === tab.id;
            return (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                aria-current={isActive ? 'page' : undefined}
                className={cn(
                  'flex w-full items-center gap-2 whitespace-nowrap rounded-xl px-3 py-2 transition-colors xl:gap-3 xl:py-3',
                  isActive
                    ? 'bg-primary/10 text-primary shadow-sm'
                    : 'text-muted-foreground hover:bg-accent hover:text-foreground'
                )}
              >
                <div className={cn(
                  'hidden h-9 w-9 items-center justify-center rounded-lg transition-colors xl:flex',
                  isActive ? 'bg-primary/20' : 'bg-muted'
                )}>
                  <Icon size={18} />
                </div>
                <div className="flex-1 text-left">
                  <div className="text-sm font-medium">{tab.label}</div>
                  <div className={cn(
                    'hidden text-xs xl:block',
                    isActive ? 'text-primary' : 'text-muted-foreground'
                  )}>
                    {tab.description}
                  </div>
                </div>
              </button>
            );
          })}
        </nav>

        {/* Sidebar Footer */}
        <div className="flex gap-2 border-t border-border px-2 py-1 xl:block xl:space-y-2 xl:p-4">
          <button
            onClick={handleExport}
            className={cn(
              'w-full flex items-center gap-3 px-3 py-2.5 text-sm font-medium rounded-xl',
              'text-muted-foreground hover:bg-accent hover:text-foreground',
              'transition-colors duration-150'
            )}
          >
            <Download size={18} />
            <span className="sm:hidden">Export</span>
            <span className="hidden sm:inline">Export Settings</span>
          </button>

          <button
            onClick={handleImport}
            className={cn(
              'w-full flex items-center gap-3 px-3 py-2.5 text-sm font-medium rounded-xl',
              'text-muted-foreground hover:bg-accent hover:text-foreground',
              'transition-colors duration-150'
            )}
          >
            <Upload size={18} />
            <span className="sm:hidden">Import</span>
            <span className="hidden sm:inline">Import Settings</span>
          </button>
        </div>
      </div>

      {/* Content */}
      <div className="min-w-0 flex-1 flex flex-col min-h-0">
        {/* Main Content Area */}
        <div className="flex-1 overflow-y-auto custom-scrollbar">
          <div className="p-4 sm:p-6 max-w-4xl mx-auto">
            {/* Error Message */}
            {error && (
              <div className="mb-6 p-4 bg-destructive/10 border border-destructive/30 rounded-xl flex items-start gap-3 animate-fade-up">
                <AlertCircle size={20} className="text-destructive shrink-0 mt-0.5" />
                <div className="flex-1">
                  <p className="text-sm text-destructive font-medium">Error</p>
                  <p className="text-sm text-destructive mt-0.5">{error}</p>
                </div>
                <button
                  onClick={clearError}
                  aria-label="Dismiss error"
                  className="text-destructive hover:bg-destructive/10 transition-colors"
                >
                  &times;
                </button>
              </div>
            )}

            {/* Success Message */}
            {saveSuccess && (
              <div className="mb-6 p-4 bg-success/10 border border-success/30 rounded-xl flex items-center gap-3 animate-fade-up">
                <Check size={20} className="text-success" />
                <p className="text-sm text-success font-medium">Settings saved successfully!</p>
              </div>
            )}

            {/* Tab Content */}
            {renderContent()}
          </div>
        </div>

        {/* Footer Actions */}
        {hasUnsavedChanges && (
          <div className="shrink-0 bg-card/95 backdrop-blur-sm border-t border-border px-6 py-4 animate-slide-up">
            <div className="flex flex-wrap items-center justify-between gap-3 max-w-4xl mx-auto">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <div className="h-2 w-2 rounded-full bg-warning animate-pulse" />
                You have unsaved changes
              </div>

              <div className="flex gap-3">
                <button
                  onClick={handleReset}
                  disabled={isLoading}
                  className={cn(
                    'px-4 py-2.5 text-sm font-medium rounded-xl flex items-center gap-2',
                    'text-muted-foreground hover:bg-accent hover:text-foreground',
                    'border border-border',
                    'transition-all duration-150 disabled:opacity-50'
                  )}
                >
                  <RotateCcw size={16} />
                  Reset
                </button>

                <button
                  onClick={handleSave}
                  disabled={isLoading}
                  className={cn(
                    'px-5 py-2.5 text-sm font-medium rounded-xl flex items-center gap-2',
                    'bg-primary text-primary-foreground hover:bg-primary-hover',
                    'shadow-lg shadow-primary/25 hover:shadow-primary/40',
                    'transition-all duration-150 disabled:opacity-50'
                  )}
                >
                  {isLoading ? (
                    <>
                      <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" />
                      Saving...
                    </>
                  ) : (
                    <>
                      <Save size={16} />
                      Save Changes
                    </>
                  )}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
