import type { RelationshipActor } from './relationships';

export type Alignment =
  | 'LAWFUL_GOOD'
  | 'NEUTRAL_GOOD'
  | 'CHAOTIC_GOOD'
  | 'LAWFUL_NEUTRAL'
  | 'TRUE_NEUTRAL'
  | 'CHAOTIC_NEUTRAL'
  | 'LAWFUL_EVIL'
  | 'NEUTRAL_EVIL'
  | 'CHAOTIC_EVIL';

export interface AlignmentEvent {
  eventId: string;
  action: 'SET' | 'ADJUST';
  lawDelta: number;
  goodDelta: number;
  previousLawfulness: number;
  previousGoodness: number;
  lawfulness: number;
  goodness: number;
  alignment: Alignment;
  reason: string | null;
  createdAt: string;
}

export interface CharacterAlignment {
  alignmentId: string;
  characterId: string | null;
  name: string;
  lawfulness: number;
  goodness: number;
  alignment: Alignment;
  updatedAt: string;
  events: AlignmentEvent[];
}

export interface AlignmentData {
  relationshipActors: RelationshipActor[];
  characterAlignments: CharacterAlignment[];
}

export interface AlignmentSummaryData {
  characterAlignments: Pick<
    CharacterAlignment,
    'characterId' | 'alignment' | 'lawfulness' | 'goodness'
  >[];
}

export interface AlignmentMeta {
  label: string;
  short: string;
  summary: string;
  description: string;
  examples: string;
  anchorLaw: number;
  anchorGood: number;
  cell: string;
  selected: string;
  dot: string;
  text: string;
}

const good = {
  cell: 'border-success/30 bg-success/10 hover:bg-success/15',
  selected: 'ring-2 ring-emerald-500 bg-success/15',
  dot: 'bg-success',
  text: 'text-emerald-700 dark:text-success',
};
const neutral = {
  cell: 'border-border bg-muted-foreground/10 hover:bg-muted-foreground/20',
  selected: 'ring-2 ring-muted-foreground bg-muted-foreground/20',
  dot: 'bg-muted-foreground',
  text: 'text-muted-foreground',
};
const lawful = {
  cell: 'border-sky-500/30 bg-sky-500/10 hover:bg-sky-500/20',
  selected: 'ring-2 ring-sky-500 bg-sky-500/20',
  dot: 'bg-sky-500',
  text: 'text-sky-700 dark:text-sky-300',
};
const chaotic = {
  cell: 'border-violet-500/30 bg-violet-500/10 hover:bg-violet-500/20',
  selected: 'ring-2 ring-violet-500 bg-violet-500/20',
  dot: 'bg-violet-500',
  text: 'text-violet-700 dark:text-violet-300',
};
const evil = {
  cell: 'border-rose-500/30 bg-rose-500/10 hover:bg-rose-500/20',
  selected: 'ring-2 ring-rose-500 bg-rose-500/20',
  dot: 'bg-rose-500',
  text: 'text-rose-700 dark:text-rose-300',
};

export const ALIGNMENTS: Record<Alignment, AlignmentMeta> = {
  LAWFUL_GOOD: {
    ...good,
    label: 'Lawful Good',
    short: 'LG',
    summary: 'Honor and justice',
    description:
      'Believes in honor, justice, and doing the right thing as expected by society and the law.',
    examples: 'Superman, Captain America, Aragorn',
    anchorLaw: 70,
    anchorGood: 70,
  },
  NEUTRAL_GOOD: {
    ...good,
    label: 'Neutral Good',
    short: 'NG',
    summary: 'Conscience and compassion',
    description:
      'Tries to do what is right without being bound by law, social norms, or tradition.',
    examples: 'Spider-Man, Gandalf, Harry Potter',
    anchorLaw: 0,
    anchorGood: 70,
  },
  CHAOTIC_GOOD: {
    ...good,
    label: 'Chaotic Good',
    short: 'CG',
    summary: 'Freedom with a conscience',
    description: 'Follows conscience with little regard for law or what others expect.',
    examples: 'Robin Hood, Han Solo, Arya Stark',
    anchorLaw: -70,
    anchorGood: 70,
  },
  LAWFUL_NEUTRAL: {
    ...lawful,
    label: 'Lawful Neutral',
    short: 'LN',
    summary: 'Order and duty',
    description:
      'Believes in the rule of law but is not swayed by those in need or the temptations of evil.',
    examples: 'Judge Dredd, Spock, James Bond',
    anchorLaw: 70,
    anchorGood: 0,
  },
  TRUE_NEUTRAL: {
    ...neutral,
    label: 'True Neutral',
    short: 'N',
    summary: 'Balance and circumstance',
    description: 'Does what seems best at the time and avoids taking sides or moral arguments.',
    examples: 'Dr. Manhattan, Treebeard, The Watcher',
    anchorLaw: 0,
    anchorGood: 0,
  },
  CHAOTIC_NEUTRAL: {
    ...chaotic,
    label: 'Chaotic Neutral',
    short: 'CN',
    summary: 'Personal freedom',
    description: 'Believes in personal freedom and following their own desires and whims.',
    examples: 'Jack Sparrow, Deadpool, Rick Sanchez',
    anchorLaw: -70,
    anchorGood: 0,
  },
  LAWFUL_EVIL: {
    ...lawful,
    ...evil,
    label: 'Lawful Evil',
    short: 'LE',
    summary: 'Power within the rules',
    description: 'Takes what they want within the limits of the law or a personal code.',
    examples: 'Darth Vader, Thanos, Dolores Umbridge',
    anchorLaw: 70,
    anchorGood: -70,
  },
  NEUTRAL_EVIL: {
    ...evil,
    label: 'Neutral Evil',
    short: 'NE',
    summary: 'Self-interest without limits',
    description: 'Takes what they want and is untroubled by who they hurt or what laws they break.',
    examples: 'Cersei Lannister, Lord Voldemort, Jabba the Hutt',
    anchorLaw: 0,
    anchorGood: -70,
  },
  CHAOTIC_EVIL: {
    ...chaotic,
    ...evil,
    label: 'Chaotic Evil',
    short: 'CE',
    summary: 'Ruin without restraint',
    description: 'Acts on arbitrary violence, hatred, bloodlust, or a desire for vengeance.',
    examples: 'The Joker, Ramsay Bolton, Hannibal Lecter',
    anchorLaw: -70,
    anchorGood: -70,
  },
};

/** Row-major order for the three-by-three chart: good at the top, evil at the bottom. */
export const ALIGNMENT_ORDER: Alignment[] = [
  'LAWFUL_GOOD',
  'NEUTRAL_GOOD',
  'CHAOTIC_GOOD',
  'LAWFUL_NEUTRAL',
  'TRUE_NEUTRAL',
  'CHAOTIC_NEUTRAL',
  'LAWFUL_EVIL',
  'NEUTRAL_EVIL',
  'CHAOTIC_EVIL',
];

export const clampAlignment = (value: number) => Math.max(-100, Math.min(100, Math.round(value)));

export const alignmentOf = (lawfulness: number, goodness: number): Alignment => {
  const law = clampAlignment(lawfulness);
  const moral = clampAlignment(goodness);
  const order = law >= 34 ? 'LAWFUL' : law <= -34 ? 'CHAOTIC' : 'NEUTRAL';
  const ethics = moral >= 34 ? 'GOOD' : moral <= -34 ? 'EVIL' : 'NEUTRAL';
  if (order === 'NEUTRAL' && ethics === 'NEUTRAL') return 'TRUE_NEUTRAL';
  if (order === 'NEUTRAL') return `NEUTRAL_${ethics}` as Alignment;
  if (ethics === 'NEUTRAL') return `${order}_NEUTRAL` as Alignment;
  return `${order}_${ethics}` as Alignment;
};

export const alignmentMeta = (alignment: Alignment | null | undefined) =>
  ALIGNMENTS[alignment ?? 'TRUE_NEUTRAL'] ?? ALIGNMENTS.TRUE_NEUTRAL;

export const signedAlignment = (points: number) => (points > 0 ? `+${points}` : String(points));
export const actorAlignmentKey = (id: string | null) => id ?? 'user';
