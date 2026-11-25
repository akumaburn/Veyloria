import { useState, useEffect, useRef } from 'react';
import { cn } from '@/lib/utils';
import { useDiceRoller, useDiceRolled } from '@/hooks/useRoleplay';
import { type DiceRollResult } from '@/graphql/types';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Switch } from '@/components/ui/Switch';
import { Select } from '@/components/ui/Select';
import { Dices, Send } from 'lucide-react';
import DiceResultDisplay from './DiceResultDisplay';

interface DiceRollerProps {
  narratorMode?: boolean;
  conversationId: string;
  characters?: Array<{ id: string; name: string }>;
}

const PRESET_EXPRESSIONS = [
  { value: '1d20', label: '1d20' },
  { value: '2d6', label: '2d6' },
  { value: '1d20+5', label: '1d20+5' },
  { value: '4d6', label: '4d6' },
  { value: '1d100', label: '1d100' },
  { value: '2d10+3', label: '2d10+3' },
  { value: '1d12', label: '1d12' },
  { value: '3d8', label: '3d8' },
];

export default function DiceRoller({ conversationId, characters = [], narratorMode = false }: DiceRollerProps) {
  const { rolls, lastRoll, roll, loading, refetch } = useDiceRoller(conversationId);
  const { roll: diceEvent } = useDiceRolled(conversationId);
  const [expression, setExpression] = useState('1d20');
  const [purpose, setPurpose] = useState('');
  const [characterId, setCharacterId] = useState('');
  const [isHidden, setIsHidden] = useState(false);

  // The subscription fires for OUR OWN rolls too, and the mutation already
  // refetches DiceHistory — refetching here as well fires two overlapping
  // network requests per roll. Skip events matching the roll we just made so
  // the history refresh only happens for OTHER users'/AI rolls. Both the
  // mutation response (lastRoll) and the in-flight ref are consulted because
  // the subscription event can arrive before the mutation promise resolves.
  const lastRollIdRef = useRef<string | null>(null);
  useEffect(() => {
    if (!diceEvent) return;
    const ownRollId = lastRoll?.rollId ?? lastRollIdRef.current;
    if (diceEvent.rollId && diceEvent.rollId === ownRollId) return;
    refetch();
  }, [diceEvent, refetch, lastRoll]);

  const handleRoll = async () => {
    if (!expression.trim()) return;
    const result = await roll(
      expression.trim(),
      narratorMode ? characterId || undefined : undefined,
      purpose.trim() || undefined,
      narratorMode && isHidden || undefined
    );
    // roll resolves to an Apollo FetchResult; the rolled dice sit in data.
    lastRollIdRef.current = result?.data?.rollDice?.rollId ?? null;
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleRoll();
    }
  };

  const characterOptions = [
    { value: '', label: 'No character' },
    ...characters.map((c) => ({ value: c.id, label: c.name })),
  ];

  return (
    <div className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4">
      <div className="flex items-center gap-2">
        <Dices size={20} className="text-warning" />
        <h3 className="text-sm font-semibold text-foreground">Dice Roller</h3>
      </div>

      {/* Expression input and roll button */}
      <div className="flex items-end gap-2">
        <div className="min-w-0 flex-1">
          <Input
            label="Dice Expression"
            value={expression}
            onChange={(e) => setExpression(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="e.g. 2d6+3"
            size="sm"
          />
        </div>
        <Button
          variant="primary"
          size="sm"
          onClick={handleRoll}
          isLoading={loading}
          leftIcon={<Send size={14} />}
          className="h-9 shrink-0"
        >
          Roll
        </Button>
      </div>

      {/* Quick presets */}
      <div className="grid grid-cols-4 gap-1.5 sm:flex sm:flex-wrap" role="group" aria-label="Dice presets">
        {PRESET_EXPRESSIONS.map((preset) => (
          <button
            key={preset.value}
            type="button"
            aria-pressed={expression === preset.value}
            onClick={() => setExpression(preset.value)}
            className={cn(
              'h-10 rounded-md px-2 text-xs font-mono transition-colors sm:h-auto sm:px-2.5 sm:py-1',
              expression === preset.value
                ? 'bg-warning/15 text-warning ring-1 ring-warning/40'
                : 'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground'
            )}
          >
            {preset.label}
          </button>
        ))}
      </div>

      {/* Purpose text */}
      <Input
        label="Purpose (optional)"
        value={purpose}
        onChange={(e) => setPurpose(e.target.value)}
        placeholder="e.g. Perception check"
        size="sm"
      />

      {/* Character selector */}
      {narratorMode && characters.length > 0 && (
        <Select
          label="Rolling as"
          options={characterOptions}
          value={characterId}
          onChange={(e) => setCharacterId(e.target.value)}
          size="sm"
        />
      )}

      {/* Hidden roll toggle */}
      {narratorMode && <Switch
        label="Hidden Roll"
        description="Only you will see the result"
        checked={isHidden}
        onChange={(e) => setIsHidden(e.target.checked)}
        size="sm"
      />}

      {/* Last roll result */}
      {lastRoll && (
        <div className="mt-2">
          <DiceResultDisplay roll={lastRoll} animate />
        </div>
      )}

      {/* Recent roll history */}
      {rolls.length > 0 && (
        <div className="mt-2 border-t border-border/60 pt-3">
          <h4 className="mb-2 text-xs font-medium text-muted-foreground uppercase tracking-wider">
            Recent Rolls
          </h4>
          <div className="flex flex-col gap-2 max-h-48 overflow-y-auto">
            {rolls.slice(0, 5).map((r: DiceRollResult) => (
              <DiceResultDisplay key={r.rollId} roll={r} animate={false} />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
