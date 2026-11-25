import React, { useState, useRef, useEffect } from 'react';
import {
  Download,
  Upload,
  FileJson,
  AlertCircle,
  Loader2,
  FileText,
  Archive,
  Trash2,
} from 'lucide-react';
import { useMutation, useLazyQuery } from '@apollo/client';
import {
  EXPORT_DATA_QUERY,
  IMPORT_DATA_MUTATION,
  DELETE_ALL_DATA_MUTATION
} from '../graphql/operations';
import { Modal } from './ui/Modal';
import { cn, normalizeTimestamp } from '../lib/utils';
import { toast } from 'react-hot-toast';

interface ExportOptions {
  conversations: boolean;
  messages: boolean;
  characters: boolean;
  settings: boolean;
}

interface ImportResult {
  conversations: number;
  messages: number;
  characters: number;
  settings: number;
  errors: string[];
}

export const DataExportImport: React.FC = () => {
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [exportOptions, setExportOptions] = useState<ExportOptions>({
    conversations: true,
    messages: true,
    characters: true,
    settings: true
  });

  const [importFile, setImportFile] = useState<File | null>(null);
  const [importPreview, setImportPreview] = useState<{
    conversations: number;
    messages: number;
    characters: number;
    settings: number;
    exportDate?: string;
    version?: string;
  } | null>(null);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [deleteConfirmText, setDeleteConfirmText] = useState('');

  // Export data query — Apollo 3.14 deprecated useLazyQuery's onCompleted/onError
  // callbacks. We derive side effects from data/error via useEffect instead.
  const [exportData, { data: exportResult, error: exportError, loading: exportLoading }] =
    useLazyQuery(EXPORT_DATA_QUERY, {
      fetchPolicy: 'no-cache',
    });

  useEffect(() => {
    if (exportResult?.exportData) {
      downloadJSON(exportResult.exportData, `chatllm-export-${Date.now()}.json`);
      toast.success('Data exported successfully');
    }
  }, [exportResult]);

  useEffect(() => {
    if (exportError) {
      console.error('Export failed:', exportError);
      toast.error('Failed to export data');
    }
  }, [exportError]);

  // Import data mutation
  const [importData, { loading: importLoading }] = useMutation(IMPORT_DATA_MUTATION, {
    onCompleted: (data) => {
      if (data.importData.success) {
        const result = data.importData.result as ImportResult;
        toast.success(`Import complete: ${String(result.conversations)} conversations, ${String(result.messages)} messages, ${String(result.characters)} characters`);
        setImportFile(null);
        setImportPreview(null);
      } else {
        toast.error(`Import failed: ${  data.importData.message}`);
      }
    },
    onError: (error) => {
      console.error('Import failed:', error);
      toast.error('Failed to import data');
    }
  });

  // Delete all data mutation
  const [deleteAllData, { loading: deleteLoading }] = useMutation(DELETE_ALL_DATA_MUTATION, {
    onCompleted: () => {
      toast.success('All data deleted successfully');
      setShowDeleteConfirm(false);
      setDeleteConfirmText('');
    },
    onError: (error) => {
      console.error('Delete failed:', error);
      toast.error('Failed to delete data');
    }
  });

  const downloadJSON = (data: unknown, filename: string) => {
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  const handleExport = () => {
    void exportData({
      variables: { filter: exportOptions }
    });
  };

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    if (file.type !== 'application/json') {
      toast.error('Please select a JSON file');
      return;
    }

    if (file.size > 50 * 1024 * 1024) { // 50MB limit
      toast.error('File size must be less than 50MB');
      return;
    }

    setImportFile(file);

    // Read and preview file
    const reader = new FileReader();
    reader.onload = (event) => {
      try {
        const data = JSON.parse(event.target?.result as string) as {
          conversations?: unknown[];
          messages?: unknown[];
          characters?: unknown[];
          settings?: unknown;
          exportDate?: string;
          version?: string;
        };
        setImportPreview({
          conversations: data.conversations?.length || 0,
          messages: data.messages?.length || 0,
          characters: data.characters?.length || 0,
          settings: data.settings ? 1 : 0,
          exportDate: data.exportDate,
          version: data.version
        });
      } catch {
        toast.error('Invalid JSON file');
        setImportFile(null);
      }
    };
    reader.readAsText(file);
  };

  const handleImport = () => {
    if (!importFile) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      try {
        const data = JSON.parse(event.target?.result as string) as unknown;
        void importData({
          variables: { data: JSON.stringify(data) }
        });
      } catch {
        toast.error('Failed to parse import file');
      }
    };
    reader.readAsText(importFile);
  };

  const handleDeleteAll = () => {
    if (deleteConfirmText === 'DELETE ALL DATA') {
      void deleteAllData({
        variables: { dataType: 'all' }
      });
    }
  };

  const exportItems = [
    { key: 'conversations', label: 'Conversations' },
    { key: 'messages', label: 'Messages' },
    { key: 'characters', label: 'Characters' },
    { key: 'settings', label: 'Settings & Preferences' }
  ];

  return (
    <div className="space-y-6">
      {/* Export Section */}
      <div className="rounded-2xl border border-border bg-card p-4 sm:p-6">
        <div className="flex items-center gap-3 mb-4">
          <div className={cn(
            'flex h-10 w-10 shrink-0 items-center justify-center rounded-xl',
            'bg-primary/10 text-primary'
          )}>
            <Download size={20} />
          </div>
          <div className="min-w-0">
            <h3 className="text-lg font-semibold text-foreground">Export Data</h3>
            <p className="text-sm text-muted-foreground">
              Download your data as a JSON file for backup or migration
            </p>
          </div>
        </div>

        <div className="space-y-1 mb-6 sm:space-y-3">
          {exportItems.map((item) => (
            <label key={item.key} className="flex min-h-10 items-center gap-3 cursor-pointer sm:min-h-0">
              <input
                type="checkbox"
                checked={exportOptions[item.key as keyof ExportOptions]}
                onChange={(e) => setExportOptions(prev => ({
                  ...prev,
                  [item.key]: e.target.checked
                }))}
                className="h-5 w-5 shrink-0 rounded border-border bg-muted text-primary focus:ring-primary/50 focus:ring-offset-0 sm:h-4 sm:w-4"
              />
              <span className="text-sm text-foreground">{item.label}</span>
            </label>
          ))}
        </div>

        <button
          onClick={handleExport}
          disabled={exportLoading || (!exportOptions.conversations && !exportOptions.messages && !exportOptions.characters && !exportOptions.settings)}
          className={cn(
            'inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium sm:min-h-0 sm:w-auto',
            'bg-primary text-primary-foreground hover:bg-primary-hover',
            'disabled:opacity-50 disabled:cursor-not-allowed transition-colors'
          )}
        >
          {exportLoading ? (
            <>
              <Loader2 className="animate-spin" size={16} />
              Exporting...
            </>
          ) : (
            <>
              <FileJson size={16} />
              Export Selected Data
            </>
          )}
        </button>
      </div>

      {/* Import Section */}
      <div className="rounded-2xl border border-border bg-card p-4 sm:p-6">
        <div className="flex items-center gap-3 mb-4">
          <div className={cn(
            'flex h-10 w-10 shrink-0 items-center justify-center rounded-xl',
            'bg-secondary/10 text-secondary'
          )}>
            <Upload size={20} />
          </div>
          <div className="min-w-0">
            <h3 className="text-lg font-semibold text-foreground">Import Data</h3>
            <p className="text-sm text-muted-foreground">
              Import data from a previously exported JSON file
            </p>
          </div>
        </div>

        <div className={cn(
          'rounded-xl border-2 border-dashed p-4 transition-colors',
          importFile ? 'border-primary bg-primary/5' : 'border-border hover:border-border-hover'
        )}>
          <input
            ref={fileInputRef}
            type="file"
            accept=".json,application/json"
            onChange={handleFileSelect}
            className="hidden"
          />

          {!importFile ? (
            <div className="text-center">
              <Archive className="mx-auto text-muted-foreground" size={28} />
              <p className="mt-2 text-sm text-foreground">
                Click to select a JSON file or drag and drop
              </p>
              <button
                onClick={() => fileInputRef.current?.click()}
                className={cn(
                  'mt-3 inline-flex min-h-11 items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium sm:min-h-0',
                  'border border-border bg-muted text-foreground',
                  'hover:bg-accent transition-colors'
                )}
              >
                Select File
              </button>
            </div>
          ) : (
            <div>
              <div className="flex items-center justify-between gap-3 mb-4">
                <div className="flex min-w-0 items-center gap-3">
                  <FileText className="shrink-0 text-muted-foreground" size={32} />
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-foreground break-all">
                      {importFile.name}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {(importFile.size / 1024).toFixed(2)} KB
                    </p>
                  </div>
                </div>
                <button
                  onClick={() => {
                    setImportFile(null);
                    setImportPreview(null);
                  }}
                  className="-mr-2 min-h-10 shrink-0 rounded-lg px-2 text-sm text-destructive hover:underline transition-colors sm:mr-0 sm:min-h-0 sm:px-0"
                >
                  Remove
                </button>
              </div>

              {importPreview && (
                <div className="rounded-xl bg-muted/50 border border-border p-4 mb-4">
                  <h4 className="text-sm font-medium text-foreground mb-2">
                    Import Preview
                  </h4>
                  <dl className="grid grid-cols-2 gap-2 text-sm">
                    <dt className="text-muted-foreground">Conversations:</dt>
                    <dd className="font-medium text-foreground">{importPreview.conversations}</dd>
                    <dt className="text-muted-foreground">Messages:</dt>
                    <dd className="font-medium text-foreground">{importPreview.messages}</dd>
                    <dt className="text-muted-foreground">Characters:</dt>
                    <dd className="font-medium text-foreground">{importPreview.characters}</dd>
                    {importPreview.exportDate && (
                      <>
                        <dt className="text-muted-foreground">Export Date:</dt>
                        <dd className="font-medium text-foreground">
                          {new Date(normalizeTimestamp(importPreview.exportDate)).toLocaleDateString()}
                        </dd>
                      </>
                    )}
                  </dl>
                </div>
              )}

              <div className={cn(
                'rounded-xl border border-warning/50 bg-warning/10 p-4 mb-4',
                'flex items-start gap-3'
              )}>
                <AlertCircle className="text-warning shrink-0 mt-0.5" size={18} />
                <div>
                  <h4 className="text-sm font-medium text-warning">Warning</h4>
                  <p className="mt-1 text-sm text-warning">
                    Importing will merge data with existing content. Duplicate items will be skipped.
                  </p>
                </div>
              </div>

              <button
                onClick={handleImport}
                disabled={importLoading}
                className={cn(
                  'w-full inline-flex justify-center items-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium max-sm:min-h-11',
                  'bg-success text-success-foreground hover:bg-success/90',
                  'disabled:opacity-50 transition-colors'
                )}
              >
                {importLoading ? (
                  <>
                    <Loader2 className="animate-spin" size={16} />
                    Importing...
                  </>
                ) : (
                  <>
                    <Upload size={16} />
                    Import Data
                  </>
                )}
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Danger Zone */}
      <div className="rounded-2xl border border-destructive/30 bg-destructive/5 p-4 sm:p-6">
        <div className="flex items-center gap-3 mb-4">
          <div className={cn(
            'flex h-10 w-10 shrink-0 items-center justify-center rounded-xl',
            'bg-destructive/10 text-destructive'
          )}>
            <AlertCircle size={20} />
          </div>
          <div className="min-w-0">
            <h3 className="text-lg font-semibold text-destructive">Danger Zone</h3>
            <p className="text-sm text-destructive">
              These actions are permanent and cannot be undone
            </p>
          </div>
        </div>

        <button
          onClick={() => setShowDeleteConfirm(true)}
          className={cn(
            'inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium sm:min-h-0 sm:w-auto',
            'border border-destructive/50 bg-destructive/10 text-destructive',
            'hover:bg-destructive/20 transition-colors'
          )}
        >
          <Trash2 size={16} />
          Delete All Data
        </button>
      </div>

      {/* Delete Confirmation Modal */}
      <Modal
        isOpen={showDeleteConfirm}
        onClose={() => {
          setShowDeleteConfirm(false);
          setDeleteConfirmText('');
        }}
        title="Delete All Data"
        size="sm"
      >
        <div className="py-4">
          <div className="flex items-center justify-center mb-4">
            <div className={cn(
              'h-12 w-12 rounded-full flex items-center justify-center',
              'bg-destructive/10'
            )}>
              <AlertCircle className="text-destructive" size={24} />
            </div>
          </div>

          <p className="text-sm text-muted-foreground mb-4">
            This action will permanently delete:
          </p>

          <ul className="text-sm text-foreground mb-6 space-y-1">
            <li>• All conversations and messages</li>
            <li>• All custom characters</li>
            <li>• All settings and preferences</li>
            <li>• All user data</li>
          </ul>

          <p className="text-sm font-medium text-foreground mb-2">
            Type <span className="font-mono bg-destructive/10 px-1.5 py-0.5 rounded text-destructive">DELETE ALL DATA</span> to confirm:
          </p>

          <input
            type="text"
            value={deleteConfirmText}
            onChange={(e) => setDeleteConfirmText(e.target.value)}
            className={cn(
              'w-full rounded-xl border border-border bg-muted/50 px-3 py-2.5 text-sm text-foreground',
              'focus:border-destructive focus:outline-none focus:ring-2 focus:ring-destructive/20',
              'placeholder-muted-foreground'
            )}
            placeholder="Type confirmation text"
          />

          <div className="mt-6 flex gap-3">
            <button
              onClick={() => {
                setShowDeleteConfirm(false);
                setDeleteConfirmText('');
              }}
              className={cn(
                'flex-1 rounded-xl px-4 py-2.5 text-sm font-medium max-sm:min-h-11',
                'border border-border bg-muted text-foreground',
                'hover:bg-accent transition-colors'
              )}
            >
              Cancel
            </button>

            <button
              onClick={handleDeleteAll}
              disabled={deleteConfirmText !== 'DELETE ALL DATA' || deleteLoading}
              className={cn(
                'flex-1 inline-flex justify-center items-center gap-2 rounded-xl px-4 py-2.5 text-sm font-medium max-sm:min-h-11',
                'bg-destructive text-destructive-foreground hover:bg-destructive/90',
                'disabled:opacity-50 disabled:cursor-not-allowed transition-colors'
              )}
            >
              {deleteLoading ? (
                <>
                  <Loader2 className="animate-spin" size={16} />
                  Deleting...
                </>
              ) : (
                'Delete Everything'
              )}
            </button>
          </div>
        </div>
      </Modal>
    </div>
  );
};
