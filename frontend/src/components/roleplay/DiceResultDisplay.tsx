import { useEffect, useState } from 'react';
import { cn } from '@/lib/utils';
import { type DiceRollResult } from '@/graphql/types';
import { EyeOff, Target } from 'lucide-react';

interface DiceResultDisplayProps {
  roll: DiceRollResult;
  animate?: boolean;
}

/**
 * Parse a dice expression to extract the modifier portion.
 * E.g. "2d6+3" -> { diceCount: 2, dieSides: 6, modifier: 3 }
 * E.g. "1d20-2" -> { diceCount: 1, dieSides: 20, modifier: -2 }
 */
function parseExpression(expression: string) {
  const match = expression.match(/^(\d+)d(\d+)([+-]\d+)?$/i);
  if (!match) return { diceCount: 0, dieSides: 0, modifier: 0 };
  return {
    diceCount: parseInt(match[1], 10),
    dieSides: parseInt(match[2], 10),
    modifier: match[3] ? parseInt(match[3], 10) : 0,
  };
}

/**
 * Get visual style for a die face based on its value relative to max.
 */
function getDieFaceStyle(value: number, max: number): string {
  if (value === max) return 'bg-success/15 text-success ring-success/40';
  if (value === 1) return 'bg-destructive/15 text-destructive ring-destructive/40';
  return 'bg-accent/60 text-foreground ring-border/40';
}

export default function DiceResultDisplay({ roll, animate = true }: DiceResultDisplayProps) {
  const [isVisible, setIsVisible] = useState(!animate);
  const [isRolling, setIsRolling] = useState(animate);

  const parsed = parseExpression(roll.expression);
  const modifier = roll.modifier ?? parsed.modifier;
  const dieResults = roll.results ?? [];
  const diceSum = dieResults.reduce((sum, v) => sum + v, 0);

  useEffect(() => {
    if (!animate) return;

    // Brief delay before showing, then animate in
    const showTimer = setTimeout(() => setIsVisible(true), 50);
    const rollTimer = setTimeout(() => setIsRolling(false), 600);

    return () => {
      clearTimeout(showTimer);
      clearTimeout(rollTimer);
    };
  }, [animate]);

  return (
    <div
      className={cn(
        'rounded-lg bg-muted/50 p-3 transition-all duration-300',
        animate && !isVisible && 'translate-y-2 opacity-0',
        animate && isVisible && 'translate-y-0 opacity-100'
      )}
    >
      {/* Header: expression + purpose */}
      <div className="mb-2 flex flex-wrap items-center justify-between gap-x-2 gap-y-1">
        <div className="flex items-center gap-2">
          <span className="font-mono text-xs font-bold text-warning">
            {roll.expression}
          </span>
          {roll.isHidden && (
            <span className="inline-flex items-center gap-1 rounded bg-accent px-1.5 py-0.5 text-xs text-muted-foreground">
              <EyeOff size={10} />
              Hidden
            </span>
          )}
        </div>
        {roll.purpose && (
          <span className="flex min-w-0 items-center gap-1 break-words text-xs text-muted-foreground">
            <Target size={10} className="shrink-0" />
            {roll.purpose}
          </span>
        )}
      </div>

      {/* Die faces */}
      {dieResults.length > 0 && (
        <div className="mb-2 flex flex-wrap gap-1.5">
          {dieResults.map((value, index) => (
            <div
              key={index}
              className={cn(
                'inline-flex h-8 w-8 items-center justify-center rounded-md',
                'font-mono text-sm font-bold ring-1 transition-all',
                getDieFaceStyle(value, parsed.dieSides),
                isRolling && 'animate-wiggle'
              )}
              style={isRolling ? { animationDelay: `${index * 80}ms` } : undefined}
            >
              {value}
            </div>
          ))}
        </div>
      )}

      {/* Total with modifier breakdown */}
      <div className="flex items-baseline gap-2">
        <span className="text-2xl font-bold text-foreground">
          {roll.total}
        </span>
        {modifier !== 0 && (
          <span className="text-xs text-muted-foreground">
            ({diceSum} {modifier > 0 ? '+' : ''}{modifier})
          </span>
        )}
      </div>
    </div>
  );
}
