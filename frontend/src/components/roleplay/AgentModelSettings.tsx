import { useEffect, useState } from 'react';
import { cn } from '@/lib/utils';
import { useAgentModelConfig, useUpdateAgentModelConfig } from '@/hooks/useRoleplay';
import { type AgentModelConfig } from '@/graphql/types';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Card, CardHeader } from '@/components/ui/Card';
import {
  Save,
  Cpu,
  Highlighter,
  Sparkles,
  BookOpen,
  Calculator,
  ShieldCheck,
  UserPlus,
} from 'lucide-react';

interface AgentModelSettingsProps {
  conversationId: string;
}

interface AgentField {
  key: keyof Omit<AgentModelConfig, 'conversationId'>;
  label: string;
  description: string;
  icon: React.ReactNode;
}

const AGENT_FIELDS: AgentField[] = [
  {
    key: 'defaultModel',
    label: 'Default Model',
    description: 'Fallback model used by all agents unless overridden below',
    icon: <Cpu size={16} className="text-muted-foreground" />,
  },
  {
    key: 'highlighterModel',
    label: 'Highlighter',
    description: 'Extracts key facts and context from conversation history',
    icon: <Highlighter size={16} className="text-warning" />,
  },
  {
    key: 'museModel',
    label: 'Muse',
    description: 'Generates creative story content and character dialogue',
    icon: <Sparkles size={16} className="text-primary" />,
  },
  {
    key: 'narratorModel',
    label: 'Narrator',
    description: 'Composes the final narration from agent outputs',
    icon: <BookOpen size={16} className="text-primary" />,
  },
  {
    key: 'accountantModel',
    label: 'Accountant',
    description: 'Tracks stats, inventory, and world state changes',
    icon: <Calculator size={16} className="text-success" />,
  },
  {
    key: 'skepticModel',
    label: 'Skeptic',
    description: 'Quality-checks output for consistency and character fidelity',
    icon: <ShieldCheck size={16} className="text-destructive" />,
  },
  {
    key: 'casterModel',
    label: 'Casting Director',
    description: 'Generates new character proposals when the story introduces NPCs',
    icon: <UserPlus size={16} className="text-primary" />,
  },
];

export default function AgentModelSettings({ conversationId }: AgentModelSettingsProps) {
  const { config, loading: configLoading } = useAgentModelConfig(conversationId);
  const { updateAgentModelConfig, loading: saveLoading } = useUpdateAgentModelConfig();

  const [models, setModels] = useState<Record<string, string>>({
    defaultModel: '',
    highlighterModel: '',
    museModel: '',
    narratorModel: '',
    accountantModel: '',
    skepticModel: '',
    casterModel: '',
  });
  const [isDirty, setIsDirty] = useState(false);
  const [saveMessage, setSaveMessage] = useState<string | null>(null);

  // Sync local state with fetched config
  useEffect(() => {
    if (config) {
      setModels({
        defaultModel: config.defaultModel ?? '',
        highlighterModel: config.highlighterModel ?? '',
        museModel: config.museModel ?? '',
        narratorModel: config.narratorModel ?? '',
        accountantModel: config.accountantModel ?? '',
        skepticModel: config.skepticModel ?? '',
        casterModel: config.casterModel ?? '',
      });
      setIsDirty(false);
    }
  }, [config]);

  const handleChange = (key: string, value: string) => {
    setModels((prev) => ({ ...prev, [key]: value }));
    setIsDirty(true);
    setSaveMessage(null);
  };

  const handleSave = async () => {
    try {
      await updateAgentModelConfig(conversationId, {
        defaultModel: models.defaultModel || undefined,
        highlighterModel: models.highlighterModel || undefined,
        museModel: models.museModel || undefined,
        narratorModel: models.narratorModel || undefined,
        accountantModel: models.accountantModel || undefined,
        skepticModel: models.skepticModel || undefined,
        casterModel: models.casterModel || undefined,
      });
      setIsDirty(false);
      setSaveMessage('Configuration saved successfully');
      setTimeout(() => setSaveMessage(null), 3000);
    } catch {
      setSaveMessage('Failed to save configuration');
    }
  };

  if (configLoading) {
    return (
      <div className="flex items-center justify-center rounded-xl border border-border bg-card p-8">
        <div className="h-5 w-5 animate-spin rounded-full border-2 border-border border-t-foreground" />
        <span className="ml-3 text-sm text-muted-foreground">Loading agent configuration...</span>
      </div>
    );
  }

  return (
    <Card variant="default" padding="lg" className="bg-card border-border/60 p-4 sm:p-6">
      <CardHeader
        title="Agent Model Configuration"
        description="Assign specific LLM models to each pipeline agent. Leave blank to use the default model."
      />

      <div className="mt-5 flex flex-col gap-4">
        {AGENT_FIELDS.map((field) => {
          const isDefault = field.key === 'defaultModel';
          return (
            <div
              key={field.key}
              className={cn(
                'rounded-lg p-3 transition-colors',
                isDefault
                  ? 'bg-muted/80 ring-1 ring-border'
                  : 'bg-muted/40 hover:bg-muted/60'
              )}
            >
              <div className="mb-2 flex items-start gap-2">
                <span className="mt-0.5 shrink-0">{field.icon}</span>
                <div className="min-w-0">
                  <span className="text-sm font-medium text-foreground">
                    {field.label}
                  </span>
                  <p className="text-xs text-muted-foreground">{field.description}</p>
                </div>
              </div>
              <Input
                value={models[field.key] ?? ''}
                onChange={(e) => handleChange(field.key, e.target.value)}
                placeholder={
                  isDefault
                    ? 'e.g. /models/mistral-7b.gguf'
                    : `Uses default (${models.defaultModel || 'not set'})`
                }
                size="sm"
              />
            </div>
          );
        })}
      </div>

      {/* Save button and status */}
      <div className="mt-5 flex flex-wrap items-center gap-3">
        <Button
          variant="primary"
          size="sm"
          className="h-10 sm:h-8"
          onClick={handleSave}
          isLoading={saveLoading}
          disabled={!isDirty}
          leftIcon={<Save size={14} />}
        >
          Save Configuration
        </Button>
        {saveMessage && (
          <span
            className={cn(
              'text-xs',
              saveMessage.includes('success') ? 'text-success' : 'text-destructive'
            )}
          >
            {saveMessage}
          </span>
        )}
      </div>
    </Card>
  );
}
