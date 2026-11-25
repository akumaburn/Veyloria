import { useSettingsStore } from '@/stores/settingsStore';
import {
  Sun, Moon, Monitor, Palette, MessageSquare, Type, Zap, Layout, Eye
} from 'lucide-react';
import { cn } from '@/lib/utils';
import {
  SettingSection,
  SettingRow,
  SettingToggle,
  SettingSlider,
  SettingSelect,
  SettingRadioCards,
  SettingRadioButtons,
} from './SettingsComponents';

export const UISettings = () => {
  const { uiSettings, updateUISettings } = useSettingsStore();

  const themes = [
    { value: 'light', label: 'Light', icon: Sun },
    { value: 'dark', label: 'Dark', icon: Moon },
    { value: 'auto', label: 'System', icon: Monitor },
  ];

  const fontSizes = [
    { value: 'small', label: 'Small' },
    { value: 'medium', label: 'Medium' },
    { value: 'large', label: 'Large' },
  ];

  const messageLayouts = [
    { value: 'bubble', label: 'Bubble', description: 'Chat-style bubbles' },
    { value: 'standard', label: 'Standard', description: 'Linear layout' },
  ];

  const fontFamilies = [
    { value: 'Inter', label: 'Inter' },
    { value: 'system-ui', label: 'System' },
    { value: 'Roboto', label: 'Roboto' },
    { value: 'Open Sans', label: 'Open Sans' },
    { value: 'JetBrains Mono', label: 'JetBrains Mono' },
  ];

  const sidebarPositions = [
    { value: 'left', label: 'Left' },
    { value: 'right', label: 'Right' },
  ];

  return (
    <div className="space-y-6">
      {/* Appearance */}
      <SettingSection
        title="Appearance"
        description="Customize the look and feel"
        icon={Palette}
      >
        <SettingRow label="Theme" description="Choose your preferred color scheme" vertical>
          <SettingRadioCards
            value={uiSettings.theme}
            onChange={(v) => updateUISettings({ theme: v as 'light' | 'dark' | 'auto' })}
            options={themes}
            columns={3}
          />
        </SettingRow>

        <SettingRow label="Font Size" description="Adjust text size for better readability">
          <SettingRadioButtons
            value={uiSettings.fontSize}
            onChange={(v) => updateUISettings({ fontSize: v as 'small' | 'medium' | 'large' })}
            options={fontSizes}
          />
        </SettingRow>

        <SettingRow label="Font Family" description="Choose your preferred font">
          <SettingSelect
            value={uiSettings.fontFamily}
            onChange={(v) => updateUISettings({ fontFamily: v as string })}
            options={fontFamilies}
            className="w-44"
          />
        </SettingRow>

        <SettingRow
          label="Compact Mode"
          description="Reduce spacing for more content density"
        >
          <SettingToggle
            checked={uiSettings.compactMode}
            onChange={(v) => updateUISettings({ compactMode: v })}
          />
        </SettingRow>
      </SettingSection>

      {/* Message Display */}
      <SettingSection
        title="Message Display"
        description="Configure how messages are shown"
        icon={MessageSquare}
      >
        <SettingRow label="Message Layout" description="Choose how messages are displayed" vertical>
          <SettingRadioCards
            value={uiSettings.messageLayout}
            onChange={(v) => updateUISettings({ messageLayout: v as 'bubble' | 'standard' })}
            options={messageLayouts}
            columns={2}
          />
        </SettingRow>

        <SettingRow
          label="Show Timestamps"
          description="Display time for each message"
        >
          <SettingToggle
            checked={uiSettings.showTimestamps}
            onChange={(v) => updateUISettings({ showTimestamps: v })}
          />
        </SettingRow>

        <SettingRow
          label="Show Token Count"
          description="Display token usage for AI responses"
        >
          <SettingToggle
            checked={uiSettings.showTokenCount}
            onChange={(v) => updateUISettings({ showTokenCount: v })}
          />
        </SettingRow>

        <SettingRow
          label="Show Model Info"
          description="Display which AI model generated responses"
        >
          <SettingToggle
            checked={uiSettings.showModelInfo}
            onChange={(v) => updateUISettings({ showModelInfo: v })}
          />
        </SettingRow>

        <SettingRow
          label="Group Messages"
          description="Group consecutive messages from same sender"
        >
          <SettingToggle
            checked={uiSettings.messageGrouping}
            onChange={(v) => updateUISettings({ messageGrouping: v })}
          />
        </SettingRow>
      </SettingSection>

      {/* Editor & Input */}
      <SettingSection
        title="Editor & Input"
        description="Configure the message input experience"
        icon={Type}
      >
        <SettingRow
          label="Enter to Send"
          description="Send messages with Enter key (Shift+Enter for new line)"
        >
          <SettingToggle
            checked={uiSettings.enterToSend}
            onChange={(v) => updateUISettings({ enterToSend: v })}
          />
        </SettingRow>

        <SettingRow
          label="Syntax Highlighting"
          description="Highlight code blocks in messages"
        >
          <SettingToggle
            checked={uiSettings.syntaxHighlighting}
            onChange={(v) => updateUISettings({ syntaxHighlighting: v })}
          />
        </SettingRow>

        <SettingRow
          label="Markdown Rendering"
          description="Render markdown formatting in messages"
        >
          <SettingToggle
            checked={uiSettings.markdownRendering}
            onChange={(v) => updateUISettings({ markdownRendering: v })}
          />
        </SettingRow>

        <SettingRow
          label="Emoji Picker"
          description="Show emoji picker button in input"
        >
          <SettingToggle
            checked={uiSettings.emojiPicker}
            onChange={(v) => updateUISettings({ emojiPicker: v })}
          />
        </SettingRow>

        <SettingRow
          label="Spell Check"
          description="Enable spell checking in message input"
        >
          <SettingToggle
            checked={uiSettings.spellCheck}
            onChange={(v) => updateUISettings({ spellCheck: v })}
          />
        </SettingRow>
      </SettingSection>

      {/* Behavior */}
      <SettingSection
        title="Behavior"
        description="Configure automatic actions"
        icon={Zap}
      >
        <SettingRow
          label="Auto Scroll"
          description="Automatically scroll to new messages"
        >
          <SettingToggle
            checked={uiSettings.autoScroll}
            onChange={(v) => updateUISettings({ autoScroll: v })}
          />
        </SettingRow>

        <SettingRow
          label="Sound Effects"
          description="Play sounds for notifications and actions"
        >
          <SettingToggle
            checked={uiSettings.soundEnabled}
            onChange={(v) => updateUISettings({ soundEnabled: v })}
          />
        </SettingRow>

        <SettingRow
          label="Notifications"
          description="Show desktop notifications for new messages"
        >
          <SettingToggle
            checked={uiSettings.notificationsEnabled}
            onChange={(v) => updateUISettings({ notificationsEnabled: v })}
          />
        </SettingRow>

        <SettingRow
          label="Auto Save"
          description="Automatically save draft messages"
        >
          <SettingToggle
            checked={uiSettings.autoSave}
            onChange={(v) => updateUISettings({ autoSave: v })}
          />
        </SettingRow>

        <SettingRow
          label="Auto Save Interval"
          description="How often to save drafts"
          disabled={!uiSettings.autoSave}
          vertical
        >
          <SettingSlider
            value={uiSettings.autoSaveInterval}
            onChange={(v) => updateUISettings({ autoSaveInterval: v })}
            min={10}
            max={120}
            step={10}
            unit="seconds"
            disabled={!uiSettings.autoSave}
          />
        </SettingRow>
      </SettingSection>

      {/* Layout */}
      <SettingSection
        title="Layout"
        description="Configure interface layout"
        icon={Layout}
      >
        <SettingRow label="Sidebar Position" description="Choose sidebar placement">
          <SettingRadioButtons
            value={uiSettings.sidebarPosition}
            onChange={(v) => updateUISettings({ sidebarPosition: v as 'left' | 'right' })}
            options={sidebarPositions}
          />
        </SettingRow>

        <SettingRow
          label="Collapse Sidebar"
          description="Start with sidebar collapsed"
        >
          <SettingToggle
            checked={uiSettings.sidebarCollapsed}
            onChange={(v) => updateUISettings({ sidebarCollapsed: v })}
          />
        </SettingRow>
      </SettingSection>

      {/* Live Preview */}
      <SettingSection
        title="Live Preview"
        description="See how your settings look"
        icon={Eye}
      >
        <div className="p-4 sm:p-5">
          <div
            className={cn(
              'rounded-xl border border-border bg-background p-4',
              uiSettings.compactMode && 'p-2'
            )}
            style={{
              fontFamily: uiSettings.fontFamily,
              fontSize:
                uiSettings.fontSize === 'small'
                  ? '14px'
                  : uiSettings.fontSize === 'large'
                  ? '18px'
                  : '16px',
            }}
          >
            <div className={cn('space-y-3', uiSettings.compactMode && 'space-y-2')}>
              {/* AI Message */}
              <div
                className={cn(
                  'rounded-xl p-3',
                  uiSettings.messageLayout === 'bubble'
                    ? 'bg-muted/50 max-w-[75%]'
                    : 'border-l-2 border-muted-foreground/30 pl-3'
                )}
              >
                <p className="text-foreground">This is how AI messages will appear</p>
                {uiSettings.showTimestamps && (
                  <p className="text-xs text-muted-foreground mt-1.5">12:34 PM</p>
                )}
                {uiSettings.showTokenCount && (
                  <p className="text-xs text-muted-foreground">24 tokens</p>
                )}
              </div>

              {/* User Message */}
              <div
                className={cn(
                  'rounded-xl p-3 ml-auto',
                  uiSettings.messageLayout === 'bubble'
                    ? 'bg-primary max-w-[75%]'
                    : 'border-l-2 border-primary pl-3 text-right'
                )}
              >
                <p className={uiSettings.messageLayout === 'bubble' ? 'text-primary-foreground' : 'text-foreground'}>
                  Your messages look like this
                </p>
                {uiSettings.showTimestamps && (
                  <p
                    className={cn(
                      'text-xs mt-1.5',
                      uiSettings.messageLayout === 'bubble'
                        ? 'text-primary-foreground/70'
                        : 'text-muted-foreground'
                    )}
                  >
                    12:35 PM
                  </p>
                )}
              </div>
            </div>
          </div>
        </div>
      </SettingSection>
    </div>
  );
};
