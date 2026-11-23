import { useState } from 'react';
import { useQuery } from '@apollo/client';
import { useSettingsStore } from '@/stores/settingsStore';
import {
  Sliders, Zap, Settings2, Cpu, MessageSquare,
  Plus, Trash2, Copy, Sparkles, Layers, Globe
} from 'lucide-react';
import { cn } from '@/lib/utils';
import {
  SettingSection,
  SettingRow,
  SettingToggle,
  SettingSlider,
  SettingSelect,
  SettingInput,
  SettingEmptyState,
  SettingActionButton,
} from './SettingsComponents';
import { LLM_CONFIG_QUERY } from '@/graphql/operations';

export const LLMSettings = () => {
  const {
    llmSettings,
    updateLLMSettings,
    presets,
    activePresetId,
    createPreset,
    deletePreset,
    applyPreset,
  } = useSettingsStore();

  const { data: llmConfigData } = useQuery(LLM_CONFIG_QUERY, { fetchPolicy: 'cache-and-network' });
  const providerType = llmConfigData?.llmConfig?.providerType ?? 'local';
  const isExternal = providerType === 'external';

  const [showPresetModal, setShowPresetModal] = useState(false);
  const [presetName, setPresetName] = useState('');
  const [presetDescription, setPresetDescription] = useState('');

  const handleSavePreset = async () => {
    if (!presetName.trim()) return;
    await createPreset(presetName, presetDescription);
    setShowPresetModal(false);
    setPresetName('');
    setPresetDescription('');
  };

  const promptTemplates = [
    { value: 'chatml', label: 'ChatML' },
    { value: 'alpaca', label: 'Alpaca' },
    { value: 'vicuna', label: 'Vicuna' },
    { value: 'llama2', label: 'Llama 2' },
    { value: 'llama3', label: 'Llama 3' },
    { value: 'mistral', label: 'Mistral' },
    { value: 'phi', label: 'Phi' },
    { value: 'zephyr', label: 'Zephyr' },
    { value: 'deepseek', label: 'DeepSeek' },
    { value: 'command-r', label: 'Command-R' },
    { value: 'custom', label: 'Custom' },
  ];

  const contextSizes = [
    { value: 512, label: '512' },
    { value: 1024, label: '1K' },
    { value: 2048, label: '2K' },
    { value: 4096, label: '4K' },
    { value: 8192, label: '8K' },
    { value: 16384, label: '16K' },
    { value: 32768, label: '32K' },
  ];

  const mirostatOptions = [
    { value: 0, label: 'Disabled' },
    { value: 1, label: 'Mirostat 1' },
    { value: 2, label: 'Mirostat 2' },
  ];

  return (
    <div className="space-y-6">
      {/* Presets Section */}
      <SettingSection
        title="Presets"
        description="Save and load parameter configurations"
        icon={Sparkles}
      >
        <div className="p-5">
          <div className="flex items-center justify-between mb-4">
            <p className="text-sm text-muted-foreground">
              {presets.length} saved preset{presets.length !== 1 ? 's' : ''}
            </p>
            <SettingActionButton
              onClick={() => setShowPresetModal(true)}
              variant="primary"
              size="sm"
              icon={Plus}
            >
              Save Current
            </SettingActionButton>
          </div>

          {presets.length === 0 ? (
            <SettingEmptyState
              icon={Layers}
              title="No presets saved"
              description="Save your current LLM settings as a preset to quickly switch between configurations."
            />
          ) : (
            <div className="space-y-2">
              {presets.map((preset) => (
                <div
                  key={preset.id}
                  className={cn(
                    'flex items-center justify-between rounded-xl border p-3 transition-all duration-150',
                    activePresetId === preset.id
                      ? 'border-primary/50 bg-primary/10'
                      : 'border-border bg-muted/30 hover:bg-muted/50'
                  )}
                >
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <h4 className="text-sm font-medium text-foreground truncate">
                        {preset.name}
                      </h4>
                      {activePresetId === preset.id && (
                        <span className="shrink-0 rounded-full bg-primary/20 px-2 py-0.5 text-xs text-primary">
                          Active
                        </span>
                      )}
                    </div>
                    {preset.description && (
                      <p className="text-xs text-muted-foreground mt-0.5 truncate">
                        {preset.description}
                      </p>
                    )}
                  </div>
                  <div className="flex items-center gap-1 ml-3">
                    <button
                      onClick={() => applyPreset(preset.id)}
                      className={cn(
                        'rounded-lg p-2 text-muted-foreground',
                        'hover:bg-accent hover:text-foreground',
                        'transition-colors duration-150'
                      )}
                      title="Apply preset"
                    >
                      <Copy size={16} />
                    </button>
                    {!preset.isDefault && (
                      <button
                        onClick={() => deletePreset(preset.id)}
                        className={cn(
                          'rounded-lg p-2 text-muted-foreground',
                          'hover:bg-destructive/10 hover:text-destructive',
                          'transition-colors duration-150'
                        )}
                        title="Delete preset"
                      >
                        <Trash2 size={16} />
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </SettingSection>

      {/* External Provider Info (only shown in external mode) */}
      {isExternal && (
        <SettingSection
          title="External Provider"
          description="Connected to an external OpenAI-compatible API"
          icon={Globe}
          badge="External"
          badgeVariant="success"
        >
          <div className="px-5 py-3 bg-primary/5 border-b border-border">
            <p className="text-xs text-muted-foreground">
              This instance is configured to use an external LLM provider. Server-level settings
              like GPU layers, threads, and prompt templates are managed by the external service.
            </p>
          </div>
          <SettingRow label="Base URL" description="External API endpoint." disabled>
            <span className="text-sm text-foreground font-mono">
              {llmConfigData?.llmConfig?.externalBaseUrl || 'Not configured'}
            </span>
          </SettingRow>
          <SettingRow label="Model" description="Model used for generation." disabled>
            <span className="text-sm text-foreground font-mono">
              {llmConfigData?.llmConfig?.externalModel || 'Not configured'}
            </span>
          </SettingRow>
        </SettingSection>
      )}

      {/* Generation Parameters */}
      <SettingSection
        title="Generation Parameters"
        description="Control the creativity and randomness of responses"
        icon={Sliders}
      >
        <SettingRow
          label="Temperature"
          description="Controls randomness. Lower values are more focused and deterministic."
          vertical
        >
          <SettingSlider
            value={llmSettings.temperature}
            onChange={(v) => updateLLMSettings({ temperature: v })}
            min={0}
            max={2}
            step={0.01}
          />
        </SettingRow>

        <SettingRow
          label="Max Tokens"
          description="Maximum number of tokens to generate in a response."
        >
          <SettingInput
            value={llmSettings.maxTokens}
            onChange={(v) => updateLLMSettings({ maxTokens: v as number })}
            type="number"
            min={-1}
            max={1024000}
            className="w-28"
          />
        </SettingRow>

        <SettingRow
          label="Top P (Nucleus Sampling)"
          description="Consider tokens with cumulative probability. Lower values are more focused."
          vertical
        >
          <SettingSlider
            value={llmSettings.topP}
            onChange={(v) => updateLLMSettings({ topP: v })}
            min={0}
            max={1}
            step={0.01}
          />
        </SettingRow>

        <SettingRow
          label="Top K"
          description="Consider only the top K most likely tokens."
        >
          <SettingInput
            value={llmSettings.topK}
            onChange={(v) => updateLLMSettings({ topK: v as number })}
            type="number"
            min={1}
            max={100}
            className="w-28"
          />
        </SettingRow>
      </SettingSection>

      {/* Penalty Parameters */}
      <SettingSection
        title="Penalty Parameters"
        description="Control repetition and token frequency"
        icon={Zap}
      >
        <SettingRow
          label="Repeat Penalty"
          description="Penalize repeated tokens to reduce repetition."
          vertical
        >
          <SettingSlider
            value={llmSettings.repeatPenalty}
            onChange={(v) => updateLLMSettings({ repeatPenalty: v })}
            min={1}
            max={2}
            step={0.01}
          />
        </SettingRow>

        <SettingRow
          label="Presence Penalty"
          description="Penalize tokens based on their presence in the text so far."
          vertical
        >
          <SettingSlider
            value={llmSettings.presencePenalty}
            onChange={(v) => updateLLMSettings({ presencePenalty: v })}
            min={-2}
            max={2}
            step={0.01}
          />
        </SettingRow>

        <SettingRow
          label="Frequency Penalty"
          description="Penalize tokens based on their frequency in the text so far."
          vertical
        >
          <SettingSlider
            value={llmSettings.frequencyPenalty}
            onChange={(v) => updateLLMSettings({ frequencyPenalty: v })}
            min={-2}
            max={2}
            step={0.01}
          />
        </SettingRow>
      </SettingSection>

      {/* Advanced Sampling */}
      <SettingSection
        title="Advanced Sampling"
        description="Fine-tune sampling behavior"
        icon={Settings2}
        badge="Advanced"
        badgeVariant="warning"
      >
        <SettingRow
          label="Min P"
          description="Minimum probability threshold for token selection."
          vertical
        >
          <SettingSlider
            value={llmSettings.minP}
            onChange={(v) => updateLLMSettings({ minP: v })}
            min={0}
            max={1}
            step={0.01}
          />
        </SettingRow>

        <SettingRow
          label="TFS Z (Tail Free Sampling)"
          description="Parameter for tail-free sampling algorithm."
          vertical
        >
          <SettingSlider
            value={llmSettings.tfsZ}
            onChange={(v) => updateLLMSettings({ tfsZ: v })}
            min={0.01}
            max={2}
            step={0.01}
          />
        </SettingRow>

        <SettingRow
          label="Typical P"
          description="Locally typical sampling parameter."
          vertical
        >
          <SettingSlider
            value={llmSettings.typicalP}
            onChange={(v) => updateLLMSettings({ typicalP: v })}
            min={0}
            max={1}
            step={0.01}
          />
        </SettingRow>

        <SettingRow label="Mirostat" description="Mirostat sampling algorithm for perplexity control.">
          <SettingSelect
            value={llmSettings.mirostat}
            onChange={(v) => updateLLMSettings({ mirostat: v as number })}
            options={mirostatOptions}
            className="w-40"
          />
        </SettingRow>

        {llmSettings.mirostat > 0 && (
          <>
            <SettingRow
              label="Mirostat Tau"
              description="Target entropy (perplexity) for Mirostat."
              vertical
            >
              <SettingSlider
                value={llmSettings.mirostatTau}
                onChange={(v) => updateLLMSettings({ mirostatTau: v })}
                min={0}
                max={10}
                step={0.1}
              />
            </SettingRow>

            <SettingRow
              label="Mirostat Eta"
              description="Learning rate for Mirostat algorithm."
              vertical
            >
              <SettingSlider
                value={llmSettings.mirostatEta}
                onChange={(v) => updateLLMSettings({ mirostatEta: v })}
                min={0}
                max={1}
                step={0.01}
              />
            </SettingRow>
          </>
        )}
      </SettingSection>

      {/* Context & Performance - Server Configuration (Read-Only, hidden in external mode) */}
      {!isExternal && (
        <SettingSection
          title="Server Configuration"
          description="Hardware and context settings configured at server startup"
          icon={Cpu}
          badge="Read-Only"
          badgeVariant="default"
        >
          <div className="px-5 py-3 bg-muted/30 border-b border-border">
            <p className="text-xs text-muted-foreground">
              These settings are configured when the LLM server starts and cannot be changed per-user.
              Contact your administrator to modify these values.
            </p>
          </div>

          <SettingRow
            label="Context Size"
            description="Maximum context window size in tokens."
            disabled
          >
            <SettingSelect
              value={llmSettings.nCtx}
              onChange={() => {}}
              options={contextSizes}
              className="w-40"
              disabled
            />
          </SettingRow>

          <SettingRow
            label="Batch Size"
            description="Number of tokens to process in parallel."
            disabled
          >
            <SettingInput
              value={llmSettings.nBatch}
              onChange={() => {}}
              type="number"
              className="w-28"
              disabled
            />
          </SettingRow>

          <SettingRow
            label="CPU Threads"
            description="Number of CPU threads used for inference."
            disabled
          >
            <SettingInput
              value={llmSettings.nThreads}
              onChange={() => {}}
              type="number"
              className="w-28"
              disabled
            />
          </SettingRow>

          <SettingRow
            label="GPU Layers"
            description="Number of layers offloaded to GPU."
            disabled
          >
            <SettingInput
              value={llmSettings.nGpuLayers}
              onChange={() => {}}
              type="number"
              className="w-28"
              disabled
            />
          </SettingRow>

          <SettingRow
            label="Main GPU"
            description="Primary GPU device index for inference."
            disabled
          >
            <SettingInput
              value={llmSettings.mainGpu}
              onChange={() => {}}
              type="number"
              className="w-28"
              disabled
            />
          </SettingRow>
        </SettingSection>
      )}

      {/* Chat Configuration */}
      <SettingSection
        title="Chat Configuration"
        description="Configure chat format and streaming behavior"
        icon={MessageSquare}
      >
        {/* Prompt Template - hidden in external mode (external APIs handle templating) */}
        {!isExternal && (
          <SettingRow label="Prompt Template" description="Chat template format for the model.">
            <SettingSelect
              value={llmSettings.promptTemplate}
              onChange={(v) => updateLLMSettings({ promptTemplate: v as string })}
              options={promptTemplates}
              className="w-40"
            />
          </SettingRow>
        )}

        <SettingRow
          label="Stream Responses"
          description="Stream responses token by token for a more interactive experience."
        >
          <SettingToggle
            checked={llmSettings.stream}
            onChange={(v) => updateLLMSettings({ stream: v })}
          />
        </SettingRow>

        <SettingRow
          label="Seed"
          description="Random seed for reproducibility. Use -1 for random."
          info="Set a specific seed to get reproducible outputs with the same prompt."
        >
          <SettingInput
            value={llmSettings.seed ?? -1}
            onChange={(v) => updateLLMSettings({ seed: v as number })}
            type="number"
            min={-1}
            max={2147483647}
            className="w-36"
          />
        </SettingRow>
      </SettingSection>

      {/* Save Preset Modal */}
      {showPresetModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div
            className="absolute inset-0 bg-black/60 backdrop-blur-sm"
            onClick={() => setShowPresetModal(false)}
          />
          <div
            className={cn(
              'relative z-10 w-full max-w-md rounded-2xl border border-border bg-card p-6 shadow-2xl',
              'animate-scale-in'
            )}
          >
            <h3 className="mb-4 text-lg font-semibold text-foreground">Save Preset</h3>
            <div className="space-y-4">
              <div>
                <label className="text-sm font-medium text-foreground">Name</label>
                <input
                  type="text"
                  value={presetName}
                  onChange={(e) => setPresetName(e.target.value)}
                  className={cn(
                    'mt-1.5 w-full rounded-xl border border-border bg-muted/50 px-3 py-2.5 text-sm text-foreground',
                    'placeholder:text-muted-foreground',
                    'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
                    'transition-all duration-150'
                  )}
                  placeholder="My Custom Preset"
                  autoFocus
                />
              </div>
              <div>
                <label className="text-sm font-medium text-foreground">Description (optional)</label>
                <textarea
                  value={presetDescription}
                  onChange={(e) => setPresetDescription(e.target.value)}
                  className={cn(
                    'mt-1.5 w-full rounded-xl border border-border bg-muted/50 px-3 py-2.5 text-sm text-foreground',
                    'placeholder:text-muted-foreground',
                    'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20',
                    'transition-all duration-150 resize-none'
                  )}
                  rows={3}
                  placeholder="Description of this preset..."
                />
              </div>
            </div>
            <div className="mt-6 flex justify-end gap-3">
              <SettingActionButton onClick={() => setShowPresetModal(false)} variant="default">
                Cancel
              </SettingActionButton>
              <SettingActionButton
                onClick={() => void handleSavePreset()}
                variant="primary"
                disabled={!presetName.trim()}
              >
                Save Preset
              </SettingActionButton>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
