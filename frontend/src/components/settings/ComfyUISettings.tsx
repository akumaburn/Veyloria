/**
 * ComfyUI Settings Component
 *
 * Read-only status and asset view for the ComfyUI image-generation server.
 * The server itself is configured on the backend (COMFYUI_ENABLED,
 * COMFYUI_HOST, COMFYUI_PORT, etc. in application.properties); this panel
 * shows live connection state, the peer's model/LoRA inventory, and the
 * workflow families the backend can render.
 */

import { useCallback, useEffect, useState } from 'react';
import { Server, Cpu, Sparkles, Workflow, RefreshCw, AlertTriangle } from 'lucide-react';
import { apolloClient } from '@/lib/apollo-client';
import { COMFYUI_STATUS_QUERY, COMFYUI_WORKFLOWS_QUERY } from '@/graphql/roleplay-operations';
import type { ComfyUIStatus, ComfyUIWorkflow } from '@/graphql/types';
import { cn } from '@/lib/utils';
import { SettingSection, SettingRow } from './SettingsComponents';

const listClass =
  'max-h-44 overflow-y-auto custom-scrollbar rounded-xl border border-border bg-muted/40 p-2 font-mono text-xs text-muted-foreground space-y-1';

export const ComfyUISettings = () => {
  const [status, setStatus] = useState<ComfyUIStatus | null>(null);
  const [workflows, setWorkflows] = useState<ComfyUIWorkflow[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (silent = false) => {
    if (!silent) setIsLoading(true);
    setIsRefreshing(true);
    setError(null);
    try {
      const [statusData, workflowData] = await Promise.all([
        apolloClient.query({ query: COMFYUI_STATUS_QUERY, fetchPolicy: 'network-only' }),
        apolloClient.query({ query: COMFYUI_WORKFLOWS_QUERY, fetchPolicy: 'network-only' }),
      ]);
      setStatus(statusData.data.comfyuiStatus);
      setWorkflows(workflowData.data.comfyuiWorkflows);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load ComfyUI status');
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const models = status?.availableModels ?? [];
  const loras = status?.availableLoras ?? [];

  return (
    <div className="space-y-6">
      {/* Status Banner */}
      {status && !status.connected && (
        <div className="p-4 bg-destructive/10 border border-destructive/30 rounded-xl">
          <div className="flex items-center gap-3">
            <Server size={20} className="shrink-0 text-destructive" />
            <div className="min-w-0">
              <p className="text-sm font-medium text-destructive">ComfyUI Server Unreachable</p>
              <p className="text-sm text-destructive mt-0.5">
                The backend cannot reach the ComfyUI server. Check COMFYUI_HOST / COMFYUI_PORT on the
                backend and that the server is running.
              </p>
            </div>
          </div>
        </div>
      )}

      {/* Connection */}
      <SettingSection
        title="Connection"
        description="Live state of the ComfyUI render server"
        icon={Server}
        badge={status ? (status.connected ? 'connected' : 'offline') : undefined}
        badgeVariant={status ? (status.connected ? 'success' : 'destructive') : 'default'}
      >
        <SettingRow
          label="Queue"
          description="Renders currently queued on the server"
          disabled={!status?.connected}
        >
          <span className="text-sm font-medium tabular-nums">{status?.queueSize ?? '—'}</span>
        </SettingRow>
        <SettingRow label="Refresh" description="Re-query the server for status and asset lists">
          <button
            type="button"
            onClick={() => void load(true)}
            disabled={isRefreshing}
            className={cn(
              'flex h-10 items-center gap-2 rounded-xl border border-border bg-muted px-4 text-sm font-medium',
              'text-foreground transition-colors hover:bg-accent disabled:opacity-50'
            )}
          >
            <RefreshCw size={14} className={cn(isRefreshing && 'animate-spin')} />
            <span>Refresh</span>
          </button>
        </SettingRow>
      </SettingSection>

      {/* Checkpoints */}
      <SettingSection
        title="Checkpoints"
        description="Model files visible to the ComfyUI server"
        icon={Cpu}
        badge={isLoading ? undefined : `${models.length}`}
      >
        {models.length === 0 ? (
          <SettingRow label="Inventory" disabled={!isLoading}>
            <span className="text-sm text-muted-foreground">
              {isLoading ? 'Loading…' : 'None reported by the server'}
            </span>
          </SettingRow>
        ) : (
          <div className="px-4 py-4 sm:px-5">
            <div className={listClass}>
              {models.map((name) => (
                <div key={name} className="break-all">
                  {name}
                </div>
              ))}
            </div>
          </div>
        )}
      </SettingSection>

      {/* LoRAs */}
      <SettingSection
        title="LoRAs"
        description="LoRA adapters visible to the ComfyUI server"
        icon={Sparkles}
        badge={isLoading ? undefined : `${loras.length}`}
      >
        {loras.length === 0 ? (
          <SettingRow label="Inventory" disabled={!isLoading}>
            <span className="text-sm text-muted-foreground">
              {isLoading ? 'Loading…' : 'None reported by the server'}
            </span>
          </SettingRow>
        ) : (
          <div className="px-4 py-4 sm:px-5">
            <div className={listClass}>
              {loras.map((name) => (
                <div key={name} className="break-all">
                  {name}
                </div>
              ))}
            </div>
          </div>
        )}
      </SettingSection>

      {/* Workflows */}
      <SettingSection
        title="Workflows"
        description="Image workflow families the backend can render"
        icon={Workflow}
        badge={isLoading ? undefined : `${workflows.length}`}
      >
        {workflows.length === 0 ? (
          <SettingRow label="Families" disabled={!isLoading}>
            <span className="text-sm text-muted-foreground">
              {isLoading ? 'Loading…' : 'None configured'}
            </span>
          </SettingRow>
        ) : (
          workflows.map((workflow) => (
            <SettingRow
              key={workflow.workflowId}
              label={workflow.name}
              description={workflow.description ?? undefined}
              badge={workflow.isBuiltIn ? 'built-in' : 'custom'}
              badgeVariant={workflow.isBuiltIn ? 'default' : 'success'}
            >
              <span className="rounded-lg bg-muted px-2.5 py-1 font-mono text-xs text-muted-foreground">
                {workflow.triggerType}
              </span>
            </SettingRow>
          ))
        )}
      </SettingSection>

      {error && (
        <div className="flex items-start gap-3 rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3">
          <AlertTriangle size={18} className="mt-0.5 shrink-0 text-destructive" />
          <p className="text-sm text-destructive">{error}</p>
        </div>
      )}
    </div>
  );
};
