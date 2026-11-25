import { useState } from 'react';
import { useLazyQuery, useMutation } from '@apollo/client';
import {
  Database, Download, Upload, Trash2, HardDrive,
  FileJson, FileText, FileDown, AlertTriangle,
  MessageSquare, Users, Loader2
} from 'lucide-react';
import { cn, toTimestamp } from '@/lib/utils';
import { apolloClient } from '@/lib/apollo-client';
import { useConversationStore } from '@/stores/conversationStore';
import { useCharacterStore } from '@/stores/characterStore';
import {
  EXPORT_DATA_QUERY,
  EXPORT_FORMATTED_DATA_QUERY,
  IMPORT_DATA_MUTATION,
  DELETE_ALL_DATA_MUTATION,
} from '@/graphql/operations';
import { toast } from 'react-hot-toast';
import {
  SettingSection,
  SettingRadioCards,
  SettingNotice,
  SettingActionButton,
} from './SettingsComponents';

interface DataStats {
  conversations: number;
  messages: number;
  characters: number;
  attachments: number;
  totalSize: string;
  oldestData: string;
  lastBackup: string;
}

export const DataManagement = () => {
  const { conversations } = useConversationStore();
  const { characters } = useCharacterStore();

  const [isExporting, setIsExporting] = useState(false);
  const [isImporting, setIsImporting] = useState(false);
  const [exportProgress, setExportProgress] = useState(0);
  const [selectedExportItems, setSelectedExportItems] = useState({
    conversations: true,
    characters: true,
    settings: true,
    attachments: false,
  });
  const [selectedFormat, setSelectedFormat] = useState('json');
  const [showDeleteConfirm, setShowDeleteConfirm] = useState<string | null>(null);

  // GraphQL export queries
  const [exportData] = useLazyQuery(EXPORT_DATA_QUERY, {
    fetchPolicy: 'no-cache',
  });

  const [exportFormattedData] = useLazyQuery(EXPORT_FORMATTED_DATA_QUERY, {
    fetchPolicy: 'no-cache',
  });

  // GraphQL import mutation
  const [importData] = useMutation(IMPORT_DATA_MUTATION);

  // GraphQL delete all data mutation
  const [deleteAllData] = useMutation(DELETE_ALL_DATA_MUTATION);

  // Calculate data stats from local store (messages count comes from conversations)
  const totalMessages = conversations.reduce((acc, conv) => acc + (conv.messageCount || 0), 0);

  const dataStats: DataStats = {
    conversations: conversations.length,
    messages: totalMessages,
    characters: characters.length,
    attachments: 0, // Not tracked locally
    totalSize: '-', // Would need server query for accurate size
    oldestData: conversations.length > 0
      ? new Date(Math.min(...conversations.map(c => toTimestamp(c.createdAt)))).toLocaleDateString()
      : '-',
    lastBackup: '-', // Would need server tracking
  };

  const exportFormats = [
    { value: 'json', label: 'JSON', description: 'Complete structured backup', icon: FileJson },
    { value: 'csv', label: 'CSV', description: 'Spreadsheet-compatible tables', icon: FileText },
    { value: 'markdown', label: 'Markdown', description: 'Human-readable document', icon: FileDown },
    { value: 'txt', label: 'Plain Text', description: 'Simple text format', icon: FileText },
  ];

  const handleExport = async (format: string) => {
    setIsExporting(true);
    setExportProgress(0);

    let interval: ReturnType<typeof setInterval> | null = null;
    let blobUrl: string | null = null;
    try {
      // Start progress animation
      interval = setInterval(() => {
        setExportProgress((prev) => {
          if (prev >= 90) {
            if (interval !== null) clearInterval(interval);
            return 90;
          }
          return prev + 10;
        });
      }, 200);

      const filter = {
        conversations: selectedExportItems.conversations,
        messages: selectedExportItems.conversations, // Messages come with conversations
        characters: selectedExportItems.characters,
        settings: selectedExportItems.settings,
      };

      let content: string;
      let mimeType: string;

      if (format === 'json') {
        // Use structured query for JSON
        const { data } = await exportData({ variables: { filter } });
        if (interval !== null) { clearInterval(interval); interval = null; }
        setExportProgress(100);
        if (!data?.exportData) throw new Error('No data returned from server');
        content = JSON.stringify(data.exportData, null, 2);
        mimeType = 'application/json';
      } else {
        // Use server-side formatting for CSV, Markdown, TXT
        const { data } = await exportFormattedData({ variables: { format, filter } });
        if (interval !== null) { clearInterval(interval); interval = null; }
        setExportProgress(100);
        if (!data?.exportFormattedData) throw new Error('No data returned from server');
        content = data.exportFormattedData;
        mimeType = 'text/plain';
      }

      // Build the blob URL
      const blob = new Blob([content], { type: mimeType });
      blobUrl = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = blobUrl;
      a.download = `chatllm-export-${Date.now()}.${format}`;
      a.click();

      toast.success('Data exported successfully');
    } catch (error) {
      console.error('Export failed:', error);
      toast.error('Failed to export data');
    } finally {
      if (interval !== null) clearInterval(interval);
      if (blobUrl !== null) URL.revokeObjectURL(blobUrl);
      setIsExporting(false);
      setExportProgress(0);
    }
  };

  const handleImport = () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json';
    input.onchange = async (e) => {
      const file = (e.target as HTMLInputElement).files?.[0];
      if (!file) return;

      setIsImporting(true);

      try {
        const text = await file.text();
        // Validate JSON
        JSON.parse(text);

        // Call GraphQL import mutation
        const { data } = await importData({
          variables: { data: text },
        });

        if (data?.importData?.success) {
          const result = data.importData.result;
          toast.success(
            `Import complete: ${result.conversations || 0} conversations, ${result.messages || 0} messages, ${result.characters || 0} characters`
          );
          // Refresh stores
          useConversationStore.getState().fetchConversations();
          useCharacterStore.getState().fetchCharacters();
        } else {
          toast.error(`Import failed: ${  data?.importData?.message || 'Unknown error'}`);
        }
      } catch (error) {
        console.error('Import failed:', error);
        toast.error('Failed to import data. Please check the file format.');
      } finally {
        setIsImporting(false);
      }
    };
    input.click();
  };

  const handleDeleteData = async (dataType: string) => {
    setShowDeleteConfirm(null);

    try {
      if (dataType === 'cache') {
        // Clear cache locally
        localStorage.removeItem('apollo-cache-persist');
        await apolloClient.cache.reset();
        toast.success('Cache cleared successfully');
      } else if (dataType === 'attachments') {
        // Delete attachments via the server
        const { data } = await deleteAllData({
          variables: { dataType },
        });
        if (data?.deleteAllData) {
          toast.success('All attachments deleted successfully');
        } else {
          throw new Error('Failed to delete attachments');
        }
      } else {
        // Use GraphQL mutation to delete data on server
        const { data } = await deleteAllData({
          variables: { dataType },
        });

        if (data?.deleteAllData) {
          toast.success(`All ${dataType} deleted successfully`);
          // Refresh stores to reflect the deletion
          if (dataType === 'conversations') {
            useConversationStore.getState().fetchConversations();
          } else if (dataType === 'characters') {
            useCharacterStore.getState().fetchCharacters();
          }
        } else {
          toast.error(`Failed to delete ${dataType}`);
        }
      }
    } catch (error) {
      console.error('Delete failed:', error);
      toast.error(`Failed to delete ${dataType}`);
    }
  };

  const handleExportCategory = async (category: string) => {
    try {
      // Build filter based on category
      const filter = {
        conversations: category === 'conversations',
        messages: category === 'conversations', // Messages come with conversations
        characters: category === 'characters',
        settings: category === 'settings',
      };

      // Fetch from server via GraphQL
      const { data } = await exportData({
        variables: { filter },
      });

      if (!data?.exportData) {
        throw new Error('No data returned from server');
      }

      const blob = new Blob([JSON.stringify(data.exportData, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `chatllm-${category}-${Date.now()}.json`;
      a.click();
      URL.revokeObjectURL(url);

      toast.success(`${category} exported successfully`);
    } catch (error) {
      console.error('Export failed:', error);
      toast.error(`Failed to export ${category}`);
    }
  };

  const handleClearCache = () => {
    localStorage.removeItem('apollo-cache-persist');
    apolloClient.cache.reset();
  };

  const dataCategories = [
    {
      id: 'conversations',
      label: 'Conversations',
      icon: MessageSquare,
      count: dataStats.conversations,
      size: `${dataStats.messages} messages`,
      bgClass: 'bg-primary/10',
      textClass: 'text-primary',
    },
    {
      id: 'characters',
      label: 'Characters',
      icon: Users,
      count: dataStats.characters,
      size: '-',
      bgClass: 'bg-secondary/10',
      textClass: 'text-secondary',
    },
    {
      id: 'attachments',
      label: 'Attachments',
      icon: FileDown,
      count: dataStats.attachments,
      size: '-',
      bgClass: 'bg-success/10',
      textClass: 'text-success',
    },
    {
      id: 'cache',
      label: 'Cache',
      icon: HardDrive,
      count: null,
      size: '-',
      bgClass: 'bg-muted',
      textClass: 'text-muted-foreground',
    },
  ];

  return (
    <div className="space-y-6">
      {/* Data Overview */}
      <SettingSection title="Data Overview" description="Summary of your stored data" icon={Database}>
        <div className="p-4 sm:p-5">
          <div className="grid grid-cols-2 gap-3 sm:gap-4 md:grid-cols-4">
            <div className="min-w-0 rounded-xl bg-muted/30 border border-border p-3 sm:p-4">
              <p className="text-xs text-muted-foreground">Total Size</p>
              <p className="text-lg font-semibold text-foreground mt-1 break-words sm:text-xl">{dataStats.totalSize}</p>
            </div>
            <div className="min-w-0 rounded-xl bg-muted/30 border border-border p-3 sm:p-4">
              <p className="text-xs text-muted-foreground">Messages</p>
              <p className="text-lg font-semibold text-foreground mt-1 break-words sm:text-xl">
                {dataStats.messages.toLocaleString()}
              </p>
            </div>
            <div className="min-w-0 rounded-xl bg-muted/30 border border-border p-3 sm:p-4">
              <p className="text-xs text-muted-foreground">Oldest Data</p>
              <p className="text-lg font-semibold text-foreground mt-1 break-words sm:text-xl">{dataStats.oldestData}</p>
            </div>
            <div className="min-w-0 rounded-xl bg-muted/30 border border-border p-3 sm:p-4">
              <p className="text-xs text-muted-foreground">Last Backup</p>
              <p className="text-lg font-semibold text-foreground mt-1 break-words sm:text-xl">{dataStats.lastBackup}</p>
            </div>
          </div>
        </div>
      </SettingSection>

      {/* Export Data */}
      <SettingSection
        title="Export Data"
        description="Download your data in various formats"
        icon={Download}
      >
        <div className="p-4 space-y-4 sm:p-5">
          {/* Export Options */}
          <div>
            <p className="text-xs text-muted-foreground mb-3">Select data to export:</p>
            <div className="grid grid-cols-2 gap-2">
              {Object.entries(selectedExportItems).map(([key, value]) => (
                <label
                  key={key}
                  className={cn(
                    'flex items-center gap-3 rounded-xl border p-3 cursor-pointer transition-all',
                    value
                      ? 'border-primary/50 bg-primary/10'
                      : 'border-border bg-muted/30 hover:bg-muted/50'
                  )}
                >
                  <input
                    type="checkbox"
                    checked={value}
                    onChange={(e) =>
                      setSelectedExportItems({
                        ...selectedExportItems,
                        [key]: e.target.checked,
                      })
                    }
                    className="h-4 w-4 rounded border-border bg-muted text-primary focus:ring-primary focus:ring-offset-0"
                  />
                  <span className={cn('text-sm capitalize', value ? 'text-foreground' : 'text-muted-foreground')}>
                    {key}
                  </span>
                </label>
              ))}
            </div>
          </div>

          {/* Export Format */}
          <div>
            <p className="text-xs text-muted-foreground mb-3">Export format:</p>
            <SettingRadioCards
              value={selectedFormat}
              onChange={(v) => setSelectedFormat(v as string)}
              options={exportFormats}
              columns={3}
            />
          </div>

          {/* Export Button */}
          <div className="flex justify-end">
            <SettingActionButton
              onClick={() => handleExport(selectedFormat)}
              variant="primary"
              disabled={isExporting}
              icon={isExporting ? undefined : Download}
              loading={isExporting}
              className="w-full sm:w-auto"
            >
              {isExporting ? `Exporting... ${exportProgress}%` : 'Export Data'}
            </SettingActionButton>
          </div>

          {/* Export Progress */}
          {isExporting && (
            <div className="rounded-xl bg-muted/50 border border-border p-4">
              <div className="flex items-center justify-between mb-2">
                <span className="text-sm text-foreground">Exporting...</span>
                <span className="text-sm text-muted-foreground">{exportProgress}%</span>
              </div>
              <div className="h-2 rounded-full bg-muted overflow-hidden">
                <div
                  className="h-full bg-gradient-to-r from-primary to-primary-hover transition-all duration-300"
                  style={{ width: `${exportProgress}%` }}
                />
              </div>
            </div>
          )}
        </div>
      </SettingSection>

      {/* Import Data */}
      <SettingSection
        title="Import Data"
        description="Restore data from a backup file"
        icon={Upload}
      >
        <div className="p-4 sm:p-5">
          <div
            className={cn(
              'rounded-xl border-2 border-dashed p-6 text-center transition-colors sm:p-8',
              isImporting ? 'border-primary bg-primary/5' : 'border-border hover:border-border-hover'
            )}
          >
            {isImporting ? (
              <div className="flex flex-col items-center gap-3">
                <Loader2 className="h-8 w-8 animate-spin text-primary" />
                <p className="text-sm text-foreground">Importing data...</p>
              </div>
            ) : (
              <>
                <Upload className="mx-auto mb-3 text-muted-foreground" size={32} />
                <p className="text-sm text-foreground mb-1">Drop your backup file here</p>
                <p className="text-xs text-muted-foreground mb-4">
                  Supports JSON files exported from Veyloria
                </p>
                <SettingActionButton onClick={handleImport} variant="default">
                  Browse Files
                </SettingActionButton>
              </>
            )}
          </div>
        </div>
      </SettingSection>

      {/* Manage Data */}
      <SettingSection title="Manage Data" description="View and manage your stored data" icon={Database}>
        <div className="divide-y divide-border/50">
          {dataCategories.map((category) => {
            const Icon = category.icon;
            return (
              <div key={category.id} className="flex items-center justify-between gap-3 px-4 py-4 sm:px-5">
                <div className="flex min-w-0 items-center gap-3">
                  <div className={cn('flex h-10 w-10 shrink-0 items-center justify-center rounded-xl', category.bgClass)}>
                    <Icon size={18} className={category.textClass} />
                  </div>
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-foreground">
                      {category.label}
                      {category.count !== null && (
                        <span className="ml-2 text-xs text-muted-foreground">({category.count})</span>
                      )}
                    </p>
                    <p className="text-xs text-muted-foreground">{category.size}</p>
                  </div>
                </div>

                <div className="flex shrink-0 items-center gap-1">
                  {category.id !== 'cache' && (
                    <button
                      type="button"
                      onClick={() => handleExportCategory(category.id)}
                      className="flex h-10 w-10 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground transition-colors sm:h-8 sm:w-8"
                      title={`Export ${category.label.toLowerCase()}`}
                      aria-label={`Export ${category.label.toLowerCase()}`}
                    >
                      <Download size={16} />
                    </button>
                  )}
                  {category.id !== 'cache' ? (
                    <button
                      type="button"
                      onClick={() => setShowDeleteConfirm(category.id)}
                      className="flex h-10 w-10 items-center justify-center rounded-lg text-muted-foreground hover:bg-destructive/10 hover:text-destructive transition-colors sm:h-8 sm:w-8"
                      title={`Delete ${category.label.toLowerCase()}`}
                      aria-label={`Delete ${category.label.toLowerCase()}`}
                    >
                      <Trash2 size={16} />
                    </button>
                  ) : (
                    <SettingActionButton
                      onClick={handleClearCache}
                      variant="ghost"
                      size="sm"
                    >
                      Clear
                    </SettingActionButton>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </SettingSection>

      {/* Danger Zone */}
      <SettingNotice variant="destructive" title="Danger Zone" icon={AlertTriangle}>
        <p>
          Deleting data is permanent and cannot be undone. Make sure to export your data before
          proceeding with any deletion.
        </p>
      </SettingNotice>

      {/* Delete Confirmation Modal */}
      {showDeleteConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div
            className="absolute inset-0 bg-black/60 backdrop-blur-sm"
            onClick={() => setShowDeleteConfirm(null)}
          />
          <div className="relative z-10 max-h-[calc(100dvh-2rem)] w-full max-w-md overflow-y-auto overscroll-contain rounded-2xl border border-border bg-card p-4 shadow-2xl animate-scale-in sm:p-6">
            <div className="flex items-start gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-destructive/10">
                <AlertTriangle className="text-destructive" size={20} />
              </div>
              <div className="min-w-0 flex-1">
                <h3 className="text-lg font-semibold text-foreground">Delete Data</h3>
                <p className="text-sm text-muted-foreground mt-2">
                  Are you sure you want to delete all <strong>{showDeleteConfirm}</strong> data?
                  This action cannot be undone.
                </p>
                <div className="mt-6 flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
                  <SettingActionButton
                    onClick={() => setShowDeleteConfirm(null)}
                    variant="default"
                    className="w-full sm:w-auto"
                  >
                    Cancel
                  </SettingActionButton>
                  <SettingActionButton
                    onClick={() => handleDeleteData(showDeleteConfirm)}
                    variant="destructive"
                    icon={Trash2}
                    className="w-full sm:w-auto"
                  >
                    Delete
                  </SettingActionButton>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
