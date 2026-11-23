import { cn } from '@/lib/utils';
import {
  ALIGNMENT_ORDER,
  ALIGNMENTS,
  alignmentMeta,
  alignmentOf,
  signedAlignment,
  type Alignment,
} from './alignment';

const axisButton =
  'flex h-8 min-w-9 items-center justify-center rounded-lg border border-border bg-background px-2 text-xs font-medium tabular-nums text-foreground transition-colors hover:bg-accent disabled:opacity-40';

/**
 * Controlled alignment chart. The nine cells set representative anchor points;
 * the sliders allow exact values between them. Finder mode is presentational,
 * so the same control works in the roster review and the character editor.
 */
export function AlignmentPicker({
  lawfulness,
  goodness,
  onChange,
  disabled = false,
}: {
  lawfulness: number;
  goodness: number;
  onChange: (next: { lawfulness: number; goodness: number }) => void;
  disabled?: boolean;
}) {
  const alignment = alignmentOf(lawfulness, goodness);
  const meta = alignmentMeta(alignment);
  const clamp = (value: number) => Math.max(-100, Math.min(100, Math.round(value)));
  const set = (nextLawfulness: number, nextGoodness: number) =>
    onChange({
      lawfulness: clamp(nextLawfulness),
      goodness: clamp(nextGoodness),
    });
  const chartLeft = Math.min(98, Math.max(2, (100 - lawfulness) / 2));
  const chartTop = Math.min(98, Math.max(2, (100 - goodness) / 2));

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-[auto_1fr] gap-1">
        <div aria-hidden />
        <div className="text-muted-foreground text-2xs grid grid-cols-3 px-1 text-center font-medium">
          <span>← Lawful</span>
          <span>Order</span>
          <span>Chaotic →</span>
        </div>
        <div className="text-muted-foreground text-2xs grid grid-rows-3 px-1 font-medium">
          <span className="flex items-center">Good ↑</span>
          <span className="flex items-center">Morality</span>
          <span className="flex items-center">Evil ↓</span>
        </div>
        <div className="relative grid min-h-[15rem] grid-cols-3 grid-rows-3 gap-1">
          {ALIGNMENT_ORDER.map((option) => {
            const optionMeta = ALIGNMENTS[option];
            const active = alignment === option;
            return (
              <button
                key={option}
                type="button"
                aria-pressed={active}
                aria-label={`Set alignment to ${optionMeta.label}: ${optionMeta.summary}`}
                disabled={disabled}
                onClick={() => set(optionMeta.anchorLaw, optionMeta.anchorGood)}
                className={cn(
                  'relative flex min-w-0 flex-col items-start justify-center gap-0.5 rounded-lg border p-1.5 text-left transition-all duration-150 disabled:opacity-60 sm:p-2.5',
                  optionMeta.cell,
                  active && optionMeta.selected,
                )}
              >
                <span
                  className={cn(
                    'text-[10px] leading-tight font-semibold sm:text-xs',
                    optionMeta.text,
                  )}
                >
                  {optionMeta.label}
                </span>
                <span className="text-muted-foreground text-2xs hidden leading-tight sm:block">
                  {optionMeta.summary}
                </span>
              </button>
            );
          })}
          <span
            aria-hidden
            className={cn(
              'border-background ring-foreground/20 pointer-events-none absolute h-3.5 w-3.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 shadow-lg ring-2',
              meta.dot,
            )}
            style={{ left: `${chartLeft}%`, top: `${chartTop}%` }}
          />
        </div>
      </div>

      <div className="border-border bg-muted/20 space-y-3 rounded-xl border p-3">
        <AxisSlider
          label="Order"
          low="Chaotic"
          high="Lawful"
          value={lawfulness}
          disabled={disabled}
          onChange={(value) => set(value, goodness)}
        />
        <AxisSlider
          label="Morality"
          low="Evil"
          high="Good"
          value={goodness}
          disabled={disabled}
          onChange={(value) => set(lawfulness, value)}
        />
      </div>

      <div className="flex flex-wrap items-center justify-between gap-2">
        <span
          className={cn(
            'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold',
            meta.cell,
            meta.text,
          )}
        >
          <span className={cn('h-1.5 w-1.5 rounded-full', meta.dot)} aria-hidden />
          {meta.label}
        </span>
        <span className="text-muted-foreground text-xs tabular-nums">
          Order {signedAlignment(lawfulness)} · Morality {signedAlignment(goodness)}
        </span>
      </div>
      <p className="text-muted-foreground text-2xs leading-relaxed">
        Click a cell for its anchor points, then fine-tune an axis. Neutral bands end at ±33; a
        named band starts at ±34.
      </p>
    </div>
  );
}

function AxisSlider({
  label,
  low,
  high,
  value,
  disabled,
  onChange,
}: {
  label: string;
  low: string;
  high: string;
  value: number;
  disabled: boolean;
  onChange: (value: number) => void;
}) {
  return (
    <div>
      <div className="mb-1 flex items-center justify-between gap-2">
        <span className="text-xs font-medium">{label}</span>
        <div className="flex items-center gap-1">
          <button
            type="button"
            className={axisButton}
            disabled={disabled || value <= -100}
            onClick={() => onChange(value - 10)}
            aria-label={`Move ${label.toLowerCase()} 10 points toward ${low}`}
          >
            −10
          </button>
          <span className="w-10 text-center text-sm font-semibold tabular-nums">
            {signedAlignment(value)}
          </span>
          <button
            type="button"
            className={axisButton}
            disabled={disabled || value >= 100}
            onClick={() => onChange(value + 10)}
            aria-label={`Move ${label.toLowerCase()} 10 points toward ${high}`}
          >
            +10
          </button>
        </div>
      </div>
      <div className="flex items-center gap-2">
        <span className="text-2xs text-muted-foreground w-12 shrink-0 text-right">{low}</span>
        <input
          type="range"
          min={-100}
          max={100}
          step={1}
          value={value}
          disabled={disabled}
          onChange={(event) => onChange(event.target.valueAsNumber)}
          aria-label={`${label} points`}
          className="accent-primary min-h-8 flex-1"
        />
        <span className="text-2xs text-muted-foreground w-12 shrink-0">{high}</span>
      </div>
    </div>
  );
}

export type { Alignment };
