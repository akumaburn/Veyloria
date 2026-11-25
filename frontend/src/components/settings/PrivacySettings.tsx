import { useState } from 'react';
import { useSettingsStore } from '@/stores/settingsStore';
import { useConversationStore } from '@/stores/conversationStore';
import { useAuthStore } from '@/stores/authStore';
import { apolloClient } from '@/lib/apollo-client';
import { EXPORT_DATA_QUERY, DELETE_CONVERSATION_MUTATION, DELETE_ALL_DATA_MUTATION } from '@/graphql/operations';
import {
  Shield, Lock, Database, FileDown, Trash2, History, Eye, EyeOff, Loader2, AlertTriangle
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { toast } from 'react-hot-toast';
import {
  SettingSection,
  SettingRow,
  SettingToggle,
  SettingSelect,
  SettingRadioCards,
  SettingNotice,
  SettingStatus,
} from './SettingsComponents';

export const PrivacySettings = () => {
  const { privacySettings, updatePrivacySettings } = useSettingsStore();
  const { conversations, reset: resetConversations } = useConversationStore();
  const { logout } = useAuthStore();

  const [isExporting, setIsExporting] = useState(false);
  const [isClearingHistory, setIsClearingHistory] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [isDeletingAccount, setIsDeletingAccount] = useState(false);

  const exportFormats = [
    { value: 'json', label: 'JSON', description: 'Machine-readable' },
    { value: 'markdown', label: 'Markdown', description: 'Human-readable' },
    { value: 'txt', label: 'Plain Text', description: 'Simple format' },
  ];

  const retentionOptions = [
    { value: 7, label: '1 Week' },
    { value: 30, label: '30 Days' },
    { value: 90, label: '90 Days' },
    { value: 180, label: '6 Months' },
    { value: 365, label: '1 Year' },
    { value: -1, label: 'Forever' },
  ];

  const handleExportAllData = async () => {
    setIsExporting(true);
    try {
      // Fetch all data from server via GraphQL
      const { data } = await apolloClient.query({
        query: EXPORT_DATA_QUERY,
        variables: {
          filter: {
            conversations: true,
            messages: true,
            characters: true,
            settings: true,
          },
        },
        fetchPolicy: 'no-cache',
      });

      if (!data?.exportData) {
        throw new Error('No data returned from server');
      }

      // Create and download file
      const blob = new Blob([JSON.stringify(data.exportData, null, 2)], {
        type: 'application/json',
      });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `chatllm-full-export-${new Date().toISOString().split('T')[0]}.json`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(url);

      toast.success('Data exported successfully');
    } catch (error) {
      console.error('Export failed:', error);
      toast.error('Failed to export data');
    } finally {
      setIsExporting(false);
    }
  };

  const handleClearHistory = async () => {
    if (!confirm('Are you sure you want to clear all conversation history? This cannot be undone.')) {
      return;
    }

    setIsClearingHistory(true);
    try {
      // Delete all conversations via GraphQL
      const deletePromises = conversations.map(conv =>
        apolloClient.mutate({
          mutation: DELETE_CONVERSATION_MUTATION,
          variables: { id: conv.id },
        }).catch(() => {}) // Ignore individual failures
      );

      await Promise.all(deletePromises);

      // Reset local store
      resetConversations();

      toast.success('History cleared successfully');
    } catch (error) {
      console.error('Clear history failed:', error);
      toast.error('Failed to clear history');
    } finally {
      setIsClearingHistory(false);
    }
  };

  const handleDeleteAccount = async () => {
    setIsDeletingAccount(true);
    try {
      // Call backend to delete all user data
      await apolloClient.mutate({
        mutation: DELETE_ALL_DATA_MUTATION,
        variables: { dataType: 'all' },
      });

      // Clear local data and logout
      resetConversations();
      localStorage.clear();
      logout();

      toast.success('Account deleted');
    } catch (error) {
      console.error('Delete account failed:', error);
      toast.error('Failed to delete account. Please contact support.');
    } finally {
      setIsDeletingAccount(false);
      setShowDeleteConfirm(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Privacy Notice */}
      <SettingNotice variant="info" title="Your Privacy Matters" icon={Shield}>
        <p>
          Veyloria is committed to protecting your privacy. All settings here give you
          complete control over your data. We never share your personal information or
          message content with third parties.
        </p>
      </SettingNotice>

      {/* Data Collection */}
      <SettingSection
        title="Data Collection"
        description="Control what data is collected and stored"
        icon={Database}
      >
        <SettingRow
          label="Share Analytics"
          description="Help improve Veyloria by sharing anonymous usage statistics"
          warning="No personal data or message content is ever shared"
        >
          <SettingToggle
            checked={privacySettings.shareAnalytics}
            onChange={(v) => updatePrivacySettings({ shareAnalytics: v })}
          />
        </SettingRow>

        <SettingRow
          label="Save Chat History"
          description="Store conversation history on the server"
          warning="Ephemeral chat mode coming soon - history is currently always saved"
          badge="Coming Soon"
          badgeVariant="default"
          disabled
        >
          <SettingToggle
            checked={true}
            onChange={() => {}}
            disabled
          />
        </SettingRow>

        <SettingRow
          label="Data Retention Period"
          description="How long to keep your data before automatic deletion"
          disabled={!privacySettings.saveHistory}
        >
          <SettingSelect
            value={privacySettings.dataRetentionDays}
            onChange={(v) => updatePrivacySettings({ dataRetentionDays: v as number })}
            options={retentionOptions}
            disabled={!privacySettings.saveHistory}
            className="w-36"
          />
        </SettingRow>
      </SettingSection>

      {/* Security */}
      <SettingSection
        title="Security"
        description="Enhance the security of your data"
        icon={Lock}
      >
        <SettingRow
          label="End-to-End Encryption"
          description="Encrypt messages before sending to server"
          warning="This feature is not yet implemented"
          badge="Coming Soon"
          badgeVariant="default"
          disabled
        >
          <SettingToggle
            checked={false}
            onChange={() => {}}
            disabled
          />
        </SettingRow>

        <SettingRow
          label="Clear Data on Logout"
          description="Remove all local data when you sign out"
          warning="This will delete all cached data and settings"
        >
          <SettingToggle
            checked={privacySettings.clearOnLogout}
            onChange={(v) => updatePrivacySettings({ clearOnLogout: v })}
          />
        </SettingRow>
      </SettingSection>

      {/* Data Export */}
      <SettingSection
        title="Data Export"
        description="Configure how your data is exported"
        icon={FileDown}
      >
        <SettingRow
          label="Default Export Format"
          description="Choose the format for data exports"
          vertical
        >
          <SettingRadioCards
            value={privacySettings.exportFormat}
            onChange={(v) => updatePrivacySettings({ exportFormat: v as 'json' | 'markdown' | 'txt' })}
            options={exportFormats}
            columns={3}
          />
        </SettingRow>
      </SettingSection>

      {/* Data Management Actions */}
      <SettingSection
        title="Data Management"
        description="Manage your stored data"
        icon={Database}
      >
        <div className="p-4 space-y-3 sm:p-5">
          <button
            onClick={handleExportAllData}
            disabled={isExporting}
            className={cn(
              'flex items-center justify-between w-full rounded-xl border border-border bg-muted/30 px-4 py-3.5',
              'hover:bg-muted/50 transition-colors group',
              'disabled:opacity-50 disabled:cursor-not-allowed'
            )}
          >
            <div className="flex min-w-0 items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10">
                {isExporting ? (
                  <Loader2 size={18} className="text-primary animate-spin" />
                ) : (
                  <FileDown size={18} className="text-primary" />
                )}
              </div>
              <div className="text-left">
                <p className="text-sm font-medium text-foreground">
                  {isExporting ? 'Exporting...' : 'Export All Data'}
                </p>
                <p className="text-xs text-muted-foreground">Download all your data in your preferred format</p>
              </div>
            </div>
            <span className="ml-3 shrink-0 text-muted-foreground group-hover:text-foreground transition-colors" aria-hidden="true">&rarr;</span>
          </button>

          <button
            onClick={handleClearHistory}
            disabled={isClearingHistory}
            className={cn(
              'flex items-center justify-between w-full rounded-xl border border-border bg-muted/30 px-4 py-3.5',
              'hover:bg-muted/50 transition-colors group',
              'disabled:opacity-50 disabled:cursor-not-allowed'
            )}
          >
            <div className="flex min-w-0 items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-muted">
                {isClearingHistory ? (
                  <Loader2 size={18} className="text-muted-foreground animate-spin" />
                ) : (
                  <History size={18} className="text-muted-foreground" />
                )}
              </div>
              <div className="text-left">
                <p className="text-sm font-medium text-foreground">
                  {isClearingHistory ? 'Clearing...' : 'Clear History'}
                </p>
                <p className="text-xs text-muted-foreground">Remove all conversation history</p>
              </div>
            </div>
            <span className="ml-3 shrink-0 text-muted-foreground group-hover:text-foreground transition-colors" aria-hidden="true">&rarr;</span>
          </button>

          <button
            onClick={() => setShowDeleteConfirm(true)}
            className={cn(
              'flex items-center justify-between w-full rounded-xl border border-destructive/30 bg-destructive/5 px-4 py-3.5',
              'hover:bg-destructive/10 transition-colors group'
            )}
          >
            <div className="flex min-w-0 items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-destructive/10">
                <Trash2 size={18} className="text-destructive" />
              </div>
              <div className="text-left">
                <p className="text-sm font-medium text-destructive">Delete Account</p>
                <p className="text-xs text-destructive">Permanently delete your account and all data</p>
              </div>
            </div>
            <span className="ml-3 shrink-0 text-destructive transition-colors" aria-hidden="true">&rarr;</span>
          </button>
        </div>
      </SettingSection>

      {/* Delete Account Confirmation Modal */}
      {showDeleteConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div
            className="absolute inset-0 bg-black/60 backdrop-blur-sm"
            onClick={() => setShowDeleteConfirm(false)}
          />
          <div className="relative z-10 max-h-[calc(100dvh-2rem)] w-full max-w-md overflow-y-auto overscroll-contain rounded-2xl border border-border bg-card p-4 shadow-2xl sm:p-6">
            <div className="flex items-start gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-destructive/10">
                <AlertTriangle className="text-destructive" size={20} />
              </div>
              <div className="min-w-0 flex-1">
                <h3 className="text-lg font-semibold text-foreground">Delete Account</h3>
                <p className="text-sm text-muted-foreground mt-2">
                  Are you sure you want to delete your account? This will permanently remove:
                </p>
                <ul className="text-sm text-muted-foreground mt-2 ml-4 list-disc space-y-1">
                  <li>All conversations and messages</li>
                  <li>All characters you created</li>
                  <li>All settings and preferences</li>
                  <li>Your user profile</li>
                </ul>
                <p className="text-sm text-destructive mt-3 font-medium">
                  This action cannot be undone.
                </p>
                <div className="mt-6 flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
                  <button
                    type="button"
                    onClick={() => setShowDeleteConfirm(false)}
                    disabled={isDeletingAccount}
                    className={cn(
                      'px-4 py-2 rounded-xl text-sm font-medium max-sm:min-h-11',
                      'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground',
                      'transition-colors disabled:opacity-50'
                    )}
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    onClick={handleDeleteAccount}
                    disabled={isDeletingAccount}
                    className={cn(
                      'inline-flex items-center justify-center gap-2 px-4 py-2 rounded-xl text-sm font-medium max-sm:min-h-11',
                      'bg-destructive text-destructive-foreground hover:bg-destructive/90',
                      'transition-colors disabled:opacity-50'
                    )}
                  >
                    {isDeletingAccount ? (
                      <>
                        <Loader2 className="animate-spin" size={16} />
                        Deleting...
                      </>
                    ) : (
                      <>
                        <Trash2 size={16} />
                        Delete Account
                      </>
                    )}
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Security Status */}
      <SettingSection
        title="Security Status"
        description="Overview of your security settings"
        icon={Shield}
      >
        <div className="p-4 space-y-4 sm:p-5">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <EyeOff size={14} className="text-muted-foreground" />
              <span className="text-sm text-muted-foreground">Encryption</span>
            </div>
            <span className="text-xs text-muted-foreground">Coming Soon</span>
          </div>

          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <History size={14} className="text-muted-foreground" />
              <span className="text-sm text-muted-foreground">Data Retention</span>
            </div>
            <span className="text-sm text-foreground">
              {privacySettings.dataRetentionDays === -1
                ? 'Forever'
                : `${privacySettings.dataRetentionDays} days`}
            </span>
          </div>

          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Eye size={14} className="text-muted-foreground" />
              <span className="text-sm text-muted-foreground">Analytics</span>
            </div>
            <SettingStatus
              enabled={!privacySettings.shareAnalytics}
              enabledText="Not Sharing"
              disabledText="Sharing"
              showDot={false}
            />
          </div>

          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Lock size={14} className="text-muted-foreground" />
              <span className="text-sm text-muted-foreground">Clear on Logout</span>
            </div>
            <SettingStatus
              enabled={privacySettings.clearOnLogout}
              enabledText="Enabled"
              disabledText="Disabled"
              showDot={false}
            />
          </div>
        </div>
      </SettingSection>
    </div>
  );
};
