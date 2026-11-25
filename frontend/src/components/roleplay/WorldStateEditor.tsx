import { useState, useCallback } from 'react';
import { cn } from '@/lib/utils';
import { useWorldState, useUpdateWorldState } from '@/hooks/useRoleplay';
import { Pencil, Check, X, Loader2 } from 'lucide-react';
import { toast } from 'react-hot-toast';

interface WorldStateEditorProps {
  conversationId: string;
}

const ICON_BUTTON =
  'inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-md transition-colors sm:h-7 sm:w-7';

const TIME_OPTIONS = [
  'dawn', 'morning', 'midday', 'afternoon', 'dusk', 'evening', 'night', 'midnight',
] as const;

interface EditableFieldProps {
  label: string;
  value: string;
  fieldKey: string;
  type?: 'text' | 'number' | 'select';
  options?: readonly string[];
  onSave: (key: string, value: string | number) => Promise<void>;
  saving: boolean;
}

function EditableField({ label, value, fieldKey, type = 'text', options, onSave, saving }: EditableFieldProps) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(value);

  const handleEdit = () => {
    setDraft(value);
    setEditing(true);
  };

  const handleCancel = () => {
    setEditing(false);
    setDraft(value);
  };

  const handleSave = async () => {
    const parsed = type === 'number' ? parseInt(draft, 10) : draft;
    await onSave(fieldKey, parsed);
    setEditing(false);
  };

  if (editing) {
    return (
      <div className="flex items-center gap-1.5 sm:gap-2">
        <label className="w-20 flex-shrink-0 text-xs text-muted-foreground sm:w-24">{label}</label>
        {type === 'select' && options ? (
          <select
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            className={cn(
              'min-w-0 flex-1 rounded-lg border border-border bg-muted/50 px-2 py-1.5',
              'text-xs text-foreground',
              'focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary/30'
            )}
          >
            {options.map((opt) => (
              <option key={opt} value={opt}>
                {opt}
              </option>
            ))}
          </select>
        ) : (
          <input
            type={type}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            className={cn(
              'min-w-0 flex-1 rounded-lg border border-border bg-muted/50 px-2 py-1.5',
              'text-xs text-foreground',
              'focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary/30'
            )}
            autoFocus
            onKeyDown={(e) => {
              if (e.key === 'Enter') void handleSave();
              if (e.key === 'Escape') handleCancel();
            }}
          />
        )}
        <button
          type="button"
          onClick={() => void handleSave()}
          disabled={saving}
          aria-label={`Save ${label}`}
          title="Save"
          className={cn(ICON_BUTTON, 'text-success hover:bg-success/10')}
        >
          {saving ? <Loader2 size={14} className="animate-spin" /> : <Check size={14} />}
        </button>
        <button
          type="button"
          onClick={handleCancel}
          aria-label={`Cancel editing ${label}`}
          title="Cancel"
          className={cn(ICON_BUTTON, 'text-muted-foreground hover:bg-muted')}
        >
          <X size={14} />
        </button>
      </div>
    );
  }

  return (
    <div className="group flex items-center gap-2">
      <label className="w-24 flex-shrink-0 text-xs text-muted-foreground">{label}</label>
      <span className="min-w-0 flex-1 text-xs font-medium text-foreground capitalize truncate">
        {value || 'Unset'}
      </span>
      <button
        type="button"
        onClick={handleEdit}
        aria-label={`Edit ${label}`}
        title={`Edit ${label}`}
        className={cn(
          ICON_BUTTON,
          'text-muted-foreground hover:bg-muted lg:opacity-0 lg:group-hover:opacity-100 lg:focus-visible:opacity-100',
        )}
      >
        <Pencil size={12} />
      </button>
    </div>
  );
}

export default function WorldStateEditor({ conversationId }: WorldStateEditorProps) {
  const { worldState } = useWorldState(conversationId);
  const { updateWorldState, loading: saving } = useUpdateWorldState();

  const handleSave = useCallback(async (key: string, value: string | number) => {
    try {
      await updateWorldState(conversationId, { [key]: value });
    } catch {
      toast.error(`Failed to update ${key}`);
    }
  }, [conversationId, updateWorldState]);

  if (!worldState) return null;

  return (
    <div className="space-y-4">
      <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        Edit World State
      </h4>
      <div className="space-y-2.5 rounded-lg border border-border/50 bg-card/30 p-3">
        <EditableField
          label="Time of Day"
          value={worldState.timeOfDay || ''}
          fieldKey="timeOfDay"
          type="select"
          options={TIME_OPTIONS}
          onSave={handleSave}
          saving={saving}
        />
        <EditableField
          label="Day Number"
          value={String(worldState.dayNumber ?? 1)}
          fieldKey="dayNumber"
          type="number"
          onSave={handleSave}
          saving={saving}
        />
        <EditableField
          label="Weather"
          value={worldState.weather || ''}
          fieldKey="weather"
          onSave={handleSave}
          saving={saving}
        />
        <EditableField
          label="Mood"
          value={worldState.mood || ''}
          fieldKey="mood"
          onSave={handleSave}
          saving={saving}
        />
        <EditableField
          label="Location"
          value={worldState.currentLocation || ''}
          fieldKey="currentLocation"
          onSave={handleSave}
          saving={saving}
        />
        <EditableField
          label="Scene"
          value={worldState.currentScene || ''}
          fieldKey="currentScene"
          onSave={handleSave}
          saving={saving}
        />
      </div>
    </div>
  );
}
