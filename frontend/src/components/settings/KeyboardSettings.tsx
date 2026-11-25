import { useState, useEffect, useCallback } from 'react';
import { useSettingsStore } from '@/stores/settingsStore';
import {
  Keyboard, RotateCcw, Info, AlertTriangle,
  Plus, Search, PanelLeft, Type, Send, Edit, Trash,
  RefreshCw, Square, Moon, Settings, Download
} from 'lucide-react';
import { cn } from '@/lib/utils';
import {
  SettingSection,
  SettingNotice,
  SettingActionButton,
  SettingKeybind,
} from './SettingsComponents';

export const KeyboardSettings = () => {
  const { keyboardShortcuts, updateKeyboardShortcuts, saveSettings } = useSettingsStore();
  const [recording, setRecording] = useState<string | null>(null);
  const [conflicts, setConflicts] = useState<Record<string, string[]>>({});

  const shortcutGroups = [
    {
      title: 'Navigation',
      icon: PanelLeft,
      shortcuts: [
        { key: 'newConversation', label: 'New Conversation', icon: Plus, description: 'Start a new conversation' },
        { key: 'searchConversations', label: 'Search Conversations', icon: Search, description: 'Open conversation search' },
        { key: 'toggleSidebar', label: 'Toggle Sidebar', icon: PanelLeft, description: 'Show/hide the sidebar' },
      ],
    },
    {
      title: 'Message Actions',
      icon: Send,
      shortcuts: [
        { key: 'focusInput', label: 'Focus Input', icon: Type, description: 'Focus the message input field' },
        { key: 'sendMessage', label: 'Send Message', icon: Send, description: 'Send the current message' },
        { key: 'editLastMessage', label: 'Edit Last Message', icon: Edit, description: 'Edit your last message' },
        { key: 'deleteLastMessage', label: 'Delete Last Message', icon: Trash, description: 'Delete your last message' },
      ],
    },
    {
      title: 'AI Actions',
      icon: RefreshCw,
      shortcuts: [
        { key: 'regenerateResponse', label: 'Regenerate Response', icon: RefreshCw, description: 'Regenerate the last AI response' },
        { key: 'stopGeneration', label: 'Stop Generation', icon: Square, description: 'Stop current AI generation' },
      ],
    },
    {
      title: 'Application',
      icon: Settings,
      shortcuts: [
        { key: 'toggleTheme', label: 'Toggle Theme', icon: Moon, description: 'Switch between light and dark theme' },
        { key: 'toggleSettings', label: 'Open Settings', icon: Settings, description: 'Open settings panel' },
        { key: 'exportChat', label: 'Export Chat', icon: Download, description: 'Export current conversation' },
      ],
    },
  ];

  const defaultShortcuts: Record<string, string> = {
    newConversation: 'Ctrl+N',
    searchConversations: 'Ctrl+K',
    toggleSidebar: 'Ctrl+B',
    focusInput: 'Ctrl+L',
    sendMessage: 'Enter',
    editLastMessage: 'Up',
    deleteLastMessage: 'Ctrl+D',
    regenerateResponse: 'Ctrl+R',
    stopGeneration: 'Escape',
    toggleTheme: 'Ctrl+Shift+T',
    toggleSettings: 'Ctrl+,',
    exportChat: 'Ctrl+E',
  };

  useEffect(() => {
    const shortcutValues = Object.values(keyboardShortcuts);
    const conflictMap: Record<string, string[]> = {};

    shortcutValues.forEach((shortcut, index) => {
      const keys = Object.keys(keyboardShortcuts);
      const conflictingKeys = keys.filter(
        (key, i) => i !== index && keyboardShortcuts[key as keyof typeof keyboardShortcuts] === shortcut
      );

      if (conflictingKeys.length > 0) {
        if (!conflictMap[shortcut]) {
          conflictMap[shortcut] = [keys[index]];
        }
        conflictMap[shortcut].push(...conflictingKeys);
      }
    });

    setConflicts(conflictMap);
  }, [keyboardShortcuts]);

  const startRecording = (key: string) => {
    setRecording(key);
  };

  const stopRecording = () => {
    setRecording(null);
  };

  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (!recording) return;

      e.preventDefault();
      e.stopPropagation();

      if (e.key === 'Escape') {
        stopRecording();
        return;
      }

      const keys: string[] = [];
      if (e.ctrlKey || e.metaKey) keys.push('Ctrl');
      if (e.altKey) keys.push('Alt');
      if (e.shiftKey) keys.push('Shift');

      if (e.key && !['Control', 'Alt', 'Shift', 'Meta'].includes(e.key)) {
        keys.push(e.key.length === 1 ? e.key.toUpperCase() : e.key);
      }

      if (keys.length > 0 && keys.some((k) => !['Ctrl', 'Alt', 'Shift'].includes(k))) {
        const shortcut = keys.join('+');
        updateKeyboardShortcuts({ [recording]: shortcut });
        stopRecording();
      }
    },
    [recording, updateKeyboardShortcuts]
  );

  useEffect(() => {
    if (recording) {
      window.addEventListener('keydown', handleKeyDown);
      return () => window.removeEventListener('keydown', handleKeyDown);
    }
    return undefined;
  }, [recording, handleKeyDown]);

  const resetShortcut = (key: string) => {
    if (defaultShortcuts[key]) {
      updateKeyboardShortcuts({ [key]: defaultShortcuts[key] });
    }
  };

  const resetAllShortcuts = async () => {
    // Update all shortcuts at once
    await updateKeyboardShortcuts(defaultShortcuts);
    // Save to persist the changes
    await saveSettings();
  };

  const getShortcutDisplay = (shortcut: string) => {
    const isMac = navigator.platform.toUpperCase().indexOf('MAC') >= 0;
    if (isMac) {
      return shortcut.replace('Ctrl', '\u2318').replace('Alt', '\u2325').replace('Shift', '\u21E7');
    }
    return shortcut;
  };

  const hasConflict = (key: string) => {
    const shortcut = keyboardShortcuts[key as keyof typeof keyboardShortcuts];
    return Object.keys(conflicts).includes(shortcut) && conflicts[shortcut].length > 1;
  };

  return (
    <div className="space-y-6">
      {/* Instructions */}
      <SettingNotice variant="info" title="Customize Keyboard Shortcuts" icon={Info}>
        <p>
          Click on any shortcut to record a new key combination. Press Escape to cancel recording.
          Conflicts will be highlighted if multiple actions share the same shortcut.
        </p>
      </SettingNotice>

      {/* Conflict Warning */}
      {Object.keys(conflicts).length > 0 && (
        <SettingNotice variant="warning" title="Shortcut Conflicts Detected" icon={AlertTriangle}>
          <p>
            Some shortcuts are assigned to multiple actions. This may cause unexpected behavior.
            Update conflicting shortcuts to resolve.
          </p>
        </SettingNotice>
      )}

      {/* Shortcut Groups */}
      {shortcutGroups.map((group) => {
        const GroupIcon = group.icon;
        return (
          <SettingSection
            key={group.title}
            title={group.title}
            icon={GroupIcon}
          >
            <div className="divide-y divide-border/50">
              {group.shortcuts.map((shortcut) => {
                const Icon = shortcut.icon;
                const currentShortcut = keyboardShortcuts[shortcut.key as keyof typeof keyboardShortcuts];
                const isRecording = recording === shortcut.key;
                const hasConflictFlag = hasConflict(shortcut.key);

                return (
                  <div
                    key={shortcut.key}
                    className={cn(
                      'flex flex-wrap items-center justify-between gap-3 px-4 py-4 sm:px-5',
                      isRecording && 'bg-primary/5'
                    )}
                  >
                    <div className="flex min-w-44 flex-1 items-center gap-3">
                      <div
                        className={cn(
                          'flex h-9 w-9 shrink-0 items-center justify-center rounded-lg',
                          hasConflictFlag
                            ? 'bg-warning/10'
                            : isRecording
                            ? 'bg-primary/20'
                            : 'bg-muted'
                        )}
                      >
                        <Icon
                          size={18}
                          className={cn(
                            hasConflictFlag
                              ? 'text-warning'
                              : isRecording
                              ? 'text-primary'
                              : 'text-muted-foreground'
                          )}
                        />
                      </div>
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <p className="text-sm font-medium text-foreground">{shortcut.label}</p>
                          {hasConflictFlag && (
                            <AlertTriangle size={12} className="text-warning" />
                          )}
                        </div>
                        <p className="text-xs text-muted-foreground">{shortcut.description}</p>
                      </div>
                    </div>

                    <div className="flex items-center gap-2">
                      {isRecording ? (
                        <div className="flex items-center gap-2">
                          <span className="text-sm text-primary animate-pulse">Recording...</span>
                          <SettingActionButton
                            onClick={stopRecording}
                            variant="ghost"
                            size="sm"
                          >
                            Cancel
                          </SettingActionButton>
                        </div>
                      ) : (
                        <>
                          <button
                            type="button"
                            onClick={() => startRecording(shortcut.key)}
                            aria-label={`Change shortcut for ${shortcut.label}`}
                            className={cn(
                              'rounded-lg px-3 py-2 font-mono text-sm transition-all',
                              hasConflictFlag
                                ? 'border border-warning/50 bg-warning/10 text-warning hover:bg-warning/20'
                                : 'bg-muted text-foreground hover:bg-accent'
                            )}
                          >
                            <SettingKeybind keys={getShortcutDisplay(currentShortcut)} />
                          </button>
                          <button
                            type="button"
                            onClick={() => resetShortcut(shortcut.key)}
                            className="flex h-10 w-10 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground transition-colors sm:h-[1.875rem] sm:w-[1.875rem]"
                            title={`Reset ${shortcut.label} to default`}
                            aria-label={`Reset ${shortcut.label} to default`}
                          >
                            <RotateCcw size={14} />
                          </button>
                        </>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </SettingSection>
        );
      })}

      {/* Reset All */}
      <SettingSection
        title="Reset Shortcuts"
        description="Restore all keyboard shortcuts to their default values"
        icon={RotateCcw}
      >
        <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-4 sm:px-5">
          <div className="min-w-44 flex-1">
            <p className="text-sm font-medium text-foreground">Reset All Shortcuts</p>
            <p className="text-xs text-muted-foreground mt-0.5">
              This will reset all shortcuts to their default values
            </p>
          </div>
          <SettingActionButton
            onClick={resetAllShortcuts}
            variant="default"
            icon={RotateCcw}
          >
            Reset All
          </SettingActionButton>
        </div>
      </SettingSection>

      {/* Quick Reference */}
      <SettingSection
        title="Quick Reference"
        description="Modifier keys and special keys"
        icon={Keyboard}
      >
        <div className="p-4 sm:p-5">
          <div className="grid grid-cols-1 gap-6 text-xs min-[400px]:grid-cols-2">
            <div className="space-y-3">
              <p className="text-muted-foreground font-medium">Modifier Keys</p>
              <div className="space-y-2">
                <div className="flex items-center gap-3">
                  <SettingKeybind keys="Ctrl" />
                  <span className="text-muted-foreground">Control / Command</span>
                </div>
                <div className="flex items-center gap-3">
                  <SettingKeybind keys="Alt" />
                  <span className="text-muted-foreground">Alt / Option</span>
                </div>
                <div className="flex items-center gap-3">
                  <SettingKeybind keys="Shift" />
                  <span className="text-muted-foreground">Shift</span>
                </div>
              </div>
            </div>

            <div className="space-y-3">
              <p className="text-muted-foreground font-medium">Special Keys</p>
              <div className="space-y-2">
                <div className="flex items-center gap-3">
                  <SettingKeybind keys="Enter" />
                  <span className="text-muted-foreground">Return / Enter</span>
                </div>
                <div className="flex items-center gap-3">
                  <SettingKeybind keys="Escape" />
                  <span className="text-muted-foreground">Escape</span>
                </div>
                <div className="flex items-center gap-3">
                  <SettingKeybind keys="Tab" />
                  <span className="text-muted-foreground">Tab</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </SettingSection>
    </div>
  );
};
