import { useEffect, useId, useState } from 'react';
import { Input } from '@/components/ui/Input';
import { Textarea } from '@/components/ui/Textarea';
import { Button } from '@/components/ui/Button';
import { Checkbox } from '@/components/ui/Checkbox';
import { cn } from '@/lib/utils';
import {
  BarChart3,
  BookOpen,
  Bot,
  Check,
  Heart,
  Image,
  Scale,
  Sparkles,
  Terminal,
  User,
  X,
} from 'lucide-react';
import { CharacterAlignmentPanel } from '@/components/characters/CharacterAlignmentPanel';
import { CharacterRelationshipsPanel } from '@/components/characters/CharacterRelationshipsPanel';
import { AlignmentPicker } from '@/components/characters/AlignmentPicker';
import { alignmentMeta, alignmentOf, type Alignment } from '@/components/characters/alignment';
import type { Character } from '@/graphql/types';

export interface CharacterFormData {
  name: string;
  description: string;
  systemPrompt: string;
  temperature: number;
  topP: number;
  topK: number;
  maxTokens: number;
  repeatPenalty: number;
  category: string;
  isPublic: boolean;
  greeting?: string;
  personality?: string;
  backstory?: string;
  appearance?: string;
  goals?: string;
  likes?: string;
  dislikes?: string;
  tags?: string[];
  avatar?: string;
  /** Physical profile. Generated characters fill these; older characters may not have them. */
  gender?: string;
  pronouns?: string;
  ageYears?: number;
  height?: string;
  build?: string;
  hair?: string;
  eyes?: string;
  /** Base attributes on a 1-20 scale, 10 is average. */
  strength?: number;
  dexterity?: number;
  constitution?: number;
  intelligence?: number;
  wisdom?: number;
  charisma?: number;
  initialAffinityToUser: number;
  initialAffinityFromUser: number;
  /** Starting points written to the alignment table when this character is created. */
  lawfulness: number;
  goodness: number;
}

export const defaultFormData: CharacterFormData = {
  name: '',
  description: '',
  systemPrompt: '',
  temperature: 0.7,
  topP: 0.9,
  topK: 40,
  maxTokens: -1,
  repeatPenalty: 1.1,
  category: 'general',
  isPublic: false,
  greeting: '',
  personality: '',
  backstory: '',
  appearance: '',
  goals: '',
  likes: '',
  dislikes: '',
  tags: [],
  avatar: '',
  gender: '',
  pronouns: '',
  ageYears: undefined,
  height: '',
  build: '',
  hair: '',
  eyes: '',
  strength: undefined,
  dexterity: undefined,
  constitution: undefined,
  intelligence: undefined,
  wisdom: undefined,
  charisma: undefined,
  initialAffinityToUser: 0,
  initialAffinityFromUser: 0,
  lawfulness: 0,
  goodness: 0,
};

/** Maps a saved or generated character onto the editor's fields. */
export function formDataFromCharacter(
  character: Character,
  alignment?: { lawfulness: number; goodness: number } | null,
): CharacterFormData {
  return {
    name: character.name,
    description: character.description || '',
    systemPrompt: character.systemPrompt || '',
    temperature: character.temperature ?? 0.7,
    topP: character.topP ?? 0.9,
    topK: character.topK ?? 40,
    maxTokens: character.maxTokens ?? -1,
    repeatPenalty: character.repeatPenalty ?? 1.1,
    category: character.category || 'general',
    isPublic: character.isPublic ?? false,
    greeting: character.greeting || '',
    personality: character.personality || '',
    backstory: character.backstory || '',
    appearance: character.appearance || '',
    goals: character.goals || '',
    likes: character.likes || '',
    dislikes: character.dislikes || '',
    tags: character.tags || [],
    avatar: character.avatar || '',
    gender: character.gender || '',
    pronouns: character.pronouns || '',
    ageYears: character.ageYears ?? undefined,
    height: character.height || '',
    build: character.build || '',
    hair: character.hair || '',
    eyes: character.eyes || '',
    strength: character.strength ?? undefined,
    dexterity: character.dexterity ?? undefined,
    constitution: character.constitution ?? undefined,
    intelligence: character.intelligence ?? undefined,
    wisdom: character.wisdom ?? undefined,
    charisma: character.charisma ?? undefined,
    initialAffinityToUser: character.initialAffinityToUser ?? 0,
    initialAffinityFromUser: character.initialAffinityFromUser ?? 0,
    lawfulness: alignment?.lawfulness ?? 0,
    goodness: alignment?.goodness ?? 0,
  };
}

export const categories = [
  { value: 'general', label: 'General', color: 'bg-muted-foreground' },
  { value: 'assistant', label: 'Assistant', color: 'bg-primary' },
  { value: 'creative', label: 'Creative', color: 'bg-secondary' },
  { value: 'educational', label: 'Educational', color: 'bg-warning' },
  { value: 'technical', label: 'Technical', color: 'bg-foreground' },
  { value: 'roleplay', label: 'Roleplay', color: 'bg-primary-hover' },
];

/** Starting points for a pair with no recorded affinity. Neutral is the default. */
export const startingFeelings = [
  { value: 0, label: 'Neutral', tier: 'NEUTRAL', points: 0 },
  { value: 20, label: 'Like', tier: 'LIKE', points: 20 },
  { value: 60, label: 'Love', tier: 'LOVE', points: 60 },
  { value: -20, label: 'Dislike', tier: 'DISLIKE', points: -20 },
  { value: -60, label: 'Hate', tier: 'HATE', points: -60 },
];

const TABS = [
  { id: 'identity', label: 'Identity', icon: User },
  { id: 'story', label: 'Story', icon: BookOpen },
  { id: 'stats', label: 'Stats', icon: BarChart3 },
  { id: 'instructions', label: 'Instructions', icon: Terminal },
  { id: 'alignment', label: 'Alignment', icon: Scale },
  { id: 'feelings', label: 'Feelings', icon: Heart },
] as const;

type TabId = (typeof TABS)[number]['id'];

const PROFILE_FIELDS = [
  { key: 'gender', label: 'Gender', placeholder: 'Woman, Man, Non-binary…' },
  { key: 'pronouns', label: 'Pronouns', placeholder: 'she/her, he/him, they/them…' },
  { key: 'height', label: 'Height', placeholder: '5 ft 8 in / 173 cm' },
  { key: 'build', label: 'Build', placeholder: 'Lean and wiry, broad, slight…' },
  { key: 'hair', label: 'Hair', placeholder: 'Color, length, and style' },
  { key: 'eyes', label: 'Eyes', placeholder: 'Color and any notable quality' },
] as const;

const ATTRIBUTE_FIELDS = [
  { key: 'strength', label: 'Strength', hint: 'Force, weight, and close combat.' },
  { key: 'dexterity', label: 'Dexterity', hint: 'Speed, balance, and fine movement.' },
  { key: 'constitution', label: 'Constitution', hint: 'Endurance, health, and grit.' },
  { key: 'intelligence', label: 'Intelligence', hint: 'Memory, reasoning, and study.' },
  { key: 'wisdom', label: 'Wisdom', hint: 'Attention, judgment, and insight.' },
  { key: 'charisma', label: 'Charisma', hint: 'Presence, persuasion, and nerve.' },
] as const;

const prompts = [
  'Stay in character and never mention being an AI.',
  'Keep replies to a few sentences unless asked for detail.',
  'Ask a question when the scene needs a choice.',
];

interface CharacterFormProps {
  formData: CharacterFormData;
  onChange: (data: CharacterFormData) => void;
  onSubmit: () => void;
  isLoading: boolean;
  submitLabel: string;
  saveSuccess?: boolean;
  /** Present only for a saved character. Enables live alignment and feeling controls. */
  characterId?: string;
  /** Saved alignment label, used by the summary until the live panel loads. */
  alignment?: Alignment | null;
  /** Exact saved axis points for an existing character. */
  alignmentPoints?: { lawfulness: number; goodness: number } | null;
  onCancel?: () => void;
}

export default function CharacterForm({
  formData,
  onChange,
  onSubmit,
  isLoading,
  submitLabel,
  saveSuccess,
  characterId,
  alignment,
  alignmentPoints,
  onCancel,
}: CharacterFormProps) {
  const id = useId();
  const [tab, setTab] = useState<TabId>('identity');
  const [errors, setErrors] = useState<Partial<Record<keyof CharacterFormData, string>>>({});
  const [tokenDraft, setTokenDraft] = useState<string | null>(null);
  const [tagDraft, setTagDraft] = useState('');
  const [liveAlignment, setLiveAlignment] = useState<Alignment | null>(alignment ?? null);
  const [livePoints, setLivePoints] = useState(alignmentPoints ?? null);
  const savedLawfulness = alignmentPoints?.lawfulness ?? null;
  const savedGoodness = alignmentPoints?.goodness ?? null;
  useEffect(() => {
    setLiveAlignment(alignment ?? null);
    setLivePoints(
      savedLawfulness === null || savedGoodness === null
        ? null
        : { lawfulness: savedLawfulness, goodness: savedGoodness },
    );
  }, [alignment, savedLawfulness, savedGoodness, characterId]);
  const points = livePoints ?? {
    lawfulness: formData.lawfulness,
    goodness: formData.goodness,
  };
  const update = <K extends keyof CharacterFormData>(key: K, value: CharacterFormData[K]) => {
    setErrors((previous) => ({ ...previous, [key]: undefined }));
    onChange({ ...formData, [key]: value });
  };
  const meta = alignmentMeta(liveAlignment ?? alignmentOf(points.lawfulness, points.goodness));
  const disabled = isLoading || saveSuccess;

  const addTag = () => {
    const value = tagDraft.trim().replace(/^#/, '');
    if (!value) return;
    const tags = formData.tags ?? [];
    if (tags.length >= 12 || tags.some((tag) => tag.toLowerCase() === value.toLowerCase())) return;
    update('tags', [...tags, value.slice(0, 32)]);
    setTagDraft('');
  };

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (disabled) return;
    const form = event.currentTarget;
    const nextErrors: typeof errors = {};
    if (!formData.name.trim()) nextErrors.name = 'Enter a name for this character.';
    if (!formData.systemPrompt.trim())
      nextErrors.systemPrompt = 'Describe how this character should behave.';
    const avatar = form.elements.namedItem('avatar') as HTMLInputElement;
    if (formData.avatar && !avatar.validity.valid)
      nextErrors.avatar = 'Enter a complete image URL, such as https://example.com/avatar.png.';
    if (
      formData.maxTokens !== -1 &&
      (!Number.isInteger(formData.maxTokens) ||
        formData.maxTokens < 1 ||
        formData.maxTokens > 1024000 ||
        tokenDraft === '')
    ) {
      nextErrors.maxTokens =
        'Enter a whole number from 1 to 1,024,000, or turn off the response limit.';
    }
    if (
      formData.ageYears !== undefined &&
      (!Number.isInteger(formData.ageYears) ||
        formData.ageYears < 0 ||
        formData.ageYears > 1000000)
    ) {
      nextErrors.ageYears = 'Enter an age in whole years.';
    }
    for (const attribute of ATTRIBUTE_FIELDS) {
      const value = formData[attribute.key];
      if (
        value !== undefined &&
        (!Number.isInteger(value) || value < 1 || value > 20)
      ) {
        nextErrors[attribute.key] = 'Enter a whole number from 1 to 20.';
      }
    }
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length) {
      const first = Object.keys(nextErrors)[0];
      const onStatsTab = ATTRIBUTE_FIELDS.some((attribute) => attribute.key === first);
      setTab(
        first === 'name' || first === 'avatar' || first === 'ageYears'
          ? 'identity'
          : onStatsTab
            ? 'stats'
            : 'instructions',
      );
      requestAnimationFrame(() => form.querySelector<HTMLElement>(`[name="${first}"]`)?.focus());
      return;
    }
    onSubmit();
  };

  const sliders = [
    {
      key: 'temperature',
      label: 'Temperature',
      min: 0,
      max: 2,
      step: 0.1,
      help: 'Lower values are more predictable; higher values add variety.',
    },
    {
      key: 'topP',
      label: 'Top P',
      min: 0,
      max: 1,
      step: 0.05,
      help: 'Limits responses to the most likely token choices.',
    },
    {
      key: 'topK',
      label: 'Top K',
      min: 1,
      max: 100,
      step: 1,
      help: 'The number of token choices considered at each step.',
    },
    {
      key: 'repeatPenalty',
      label: 'Repetition penalty',
      min: 0.5,
      max: 2,
      step: 0.05,
      help: 'Higher values discourage repeating the same words and phrases.',
    },
  ] as const;

  const extendedFields = [
    {
      key: 'personality',
      label: 'Personality traits',
      placeholder: 'Friendly, curious, helpful…',
    },
    {
      key: 'appearance',
      label: 'Appearance',
      placeholder: 'Physical appearance, clothing style…',
    },
    {
      key: 'backstory',
      label: 'Backstory',
      placeholder: 'Character history, background, origins…',
    },
    {
      key: 'goals',
      label: 'Goals',
      placeholder: 'Motivations and what the character wants to achieve…',
    },
    { key: 'likes', label: 'Likes', placeholder: 'Hobbies, interests, favorites…' },
    { key: 'dislikes', label: 'Dislikes', placeholder: 'Things they avoid or dislike…' },
  ] as const;

  return (
    <form
      onSubmit={handleSubmit}
      noValidate
      aria-label="Character editor"
      aria-busy={isLoading}
      className="grid min-w-0 grid-cols-1 items-start gap-6 xl:grid-cols-[minmax(0,1fr)_19rem]"
    >
      <div className="min-w-0 space-y-4">
        <div
          role="tablist"
          aria-label="Character editor sections"
          className="border-border bg-card scrollbar-hide flex gap-1 overflow-x-auto rounded-xl border p-1 sm:flex-wrap sm:overflow-visible"
        >
          {TABS.map(({ id: tabId, label, icon: Icon }) => (
            <button
              key={tabId}
              type="button"
              role="tab"
              id={`${id}-tab-${tabId}`}
              aria-selected={tab === tabId}
              aria-controls={`${id}-panel`}
              tabIndex={tab === tabId ? 0 : -1}
              onClick={() => setTab(tabId)}
              onKeyDown={(event) => {
                const index = TABS.findIndex((entry) => entry.id === tab);
                if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
                  event.preventDefault();
                  const next =
                    (index + (event.key === 'ArrowRight' ? 1 : -1) + TABS.length) % TABS.length;
                  setTab(TABS[next].id);
                  document.getElementById(`${id}-tab-${TABS[next].id}`)?.focus();
                } else if (event.key === 'Home' || event.key === 'End') {
                  event.preventDefault();
                  const next = event.key === 'Home' ? 0 : TABS.length - 1;
                  setTab(TABS[next].id);
                  document.getElementById(`${id}-tab-${TABS[next].id}`)?.focus();
                }
              }}
              className={cn(
                'text-muted-foreground inline-flex min-h-10 shrink-0 items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors',
                'hover:bg-accent hover:text-foreground',
                tab === tabId &&
                  'bg-primary text-primary-foreground hover:bg-primary hover:text-primary-foreground',
              )}
            >
              <Icon size={15} aria-hidden />
              {label}
            </button>
          ))}
        </div>

        <fieldset
          disabled={disabled}
          className="border-border bg-card min-w-0 rounded-xl border p-4 sm:p-5"
        >
          <div role="tabpanel" id={`${id}-panel`} aria-labelledby={`${id}-tab-${tab}`} tabIndex={0}>
            {tab === 'identity' && (
              <section className="space-y-5" aria-label="Identity">
                <div>
                  <h3 className="text-foreground flex items-center gap-2 font-semibold">
                    <User size={18} className="text-primary" aria-hidden />
                    Identity
                  </h3>
                  <p className="text-muted-foreground mt-1 text-xs">
                    Who this character is and how they appear in your library.
                  </p>
                </div>
                <div className="flex flex-wrap items-start gap-4">
                  <div className="relative shrink-0">
                    {formData.avatar ? (
                      <>
                        <img
                          src={formData.avatar}
                          alt="Character avatar preview"
                          className="border-border h-20 w-20 rounded-xl border object-cover"
                        />
                        <button
                          type="button"
                          onClick={() => update('avatar', '')}
                          aria-label="Remove avatar"
                          className="bg-destructive text-destructive-foreground hover:bg-destructive-hover absolute -top-2 -right-2 flex h-8 w-8 items-center justify-center rounded-full"
                        >
                          <X size={14} />
                        </button>
                      </>
                    ) : (
                      <div className="border-input bg-muted text-muted-foreground flex h-20 w-20 items-center justify-center rounded-xl border border-dashed">
                        <Image size={28} aria-hidden="true" />
                      </div>
                    )}
                  </div>
                  <div className="min-w-0 flex-1 basis-48 space-y-4">
                    <Input
                      label="Name"
                      name="name"
                      required
                      value={formData.name}
                      onChange={(event) => update('name', event.target.value)}
                      error={errors.name}
                      placeholder="e.g., Helpful Assistant"
                    />
                    <Input
                      label="Avatar URL"
                      name="avatar"
                      type="url"
                      value={formData.avatar ?? ''}
                      onChange={(event) => update('avatar', event.target.value)}
                      error={errors.avatar}
                      placeholder="https://example.com/avatar.png"
                      helperText="A complete URL for the character’s image."
                    />
                  </div>
                </div>
                <Textarea
                  label="Description"
                  name="description"
                  value={formData.description}
                  onChange={(event) => update('description', event.target.value)}
                  rows={2}
                  placeholder="A brief description of what this character does"
                />
                <fieldset className="min-w-0 space-y-3">
                  <legend className="text-foreground text-sm font-medium">Physical profile</legend>
                  <p className="text-muted-foreground text-xs">
                    Concrete facts the story can rely on. The character card shows whatever you
                    fill in.
                  </p>
                  <div className="grid gap-3 sm:grid-cols-2">
                    {PROFILE_FIELDS.map((field) => (
                      <Input
                        key={field.key}
                        label={field.label}
                        name={field.key}
                        value={formData[field.key] ?? ''}
                        onChange={(event) => update(field.key, event.target.value)}
                        placeholder={field.placeholder}
                      />
                    ))}
                    <Input
                      label="Age"
                      name="ageYears"
                      type="number"
                      inputMode="numeric"
                      min={0}
                      step={1}
                      value={formData.ageYears ?? ''}
                      onChange={(event) =>
                        update(
                          'ageYears',
                          event.target.value === '' ? undefined : event.target.valueAsNumber,
                        )
                      }
                      error={errors.ageYears}
                      placeholder="27"
                    />
                  </div>
                </fieldset>
                <fieldset className="min-w-0">
                  <legend className="text-foreground mb-2 text-sm font-medium">Category</legend>
                  <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                    {categories.map((category) => (
                      <label key={category.value} className="relative cursor-pointer">
                        <input
                          type="radio"
                          className="peer sr-only"
                          name="category"
                          value={category.value}
                          checked={formData.category === category.value}
                          onChange={() => update('category', category.value)}
                        />
                        <span className="border-border bg-muted text-muted-foreground peer-checked:border-primary peer-checked:bg-primary/10 peer-checked:text-primary peer-focus-visible:outline-ring flex min-h-11 items-center gap-2 rounded-lg border px-3 py-2 text-sm peer-checked:font-medium peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2">
                          <span
                            className={cn('h-2 w-2 shrink-0 rounded-full', category.color)}
                            aria-hidden
                          />
                          {category.label}
                        </span>
                      </label>
                    ))}
                  </div>
                </fieldset>
                <div>
                  <label htmlFor={`${id}-tags`} className="text-foreground text-sm font-medium">
                    Tags
                  </label>
                  <div className="border-input bg-background focus-within:border-primary focus-within:ring-primary mt-1.5 flex min-h-11 flex-wrap items-center gap-1.5 rounded-lg border px-2 py-1.5 focus-within:ring-1">
                    {(formData.tags ?? []).map((tag) => (
                      <span
                        key={tag}
                        className="bg-accent text-foreground inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs"
                      >
                        {tag}
                        <button
                          type="button"
                          aria-label={`Remove tag ${tag}`}
                          onClick={() =>
                            update(
                              'tags',
                              (formData.tags ?? []).filter((item) => item !== tag),
                            )
                          }
                          className="text-muted-foreground hover:text-foreground -my-2 -mr-2 inline-flex h-8 w-8 items-center justify-center rounded-md sm:m-0 sm:h-auto sm:w-auto"
                        >
                          <X size={12} />
                        </button>
                      </span>
                    ))}
                    <input
                      id={`${id}-tags`}
                      value={tagDraft}
                      onChange={(event) => setTagDraft(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' || event.key === ',') {
                          event.preventDefault();
                          addTag();
                        } else if (
                          event.key === 'Backspace' &&
                          !tagDraft &&
                          formData.tags?.length
                        ) {
                          update('tags', formData.tags.slice(0, -1));
                        }
                      }}
                      onBlur={addTag}
                      placeholder={formData.tags?.length ? 'Add another…' : 'Add a tag…'}
                      className="text-foreground placeholder:text-muted-foreground min-w-24 flex-1 bg-transparent px-1 py-1 text-sm outline-none"
                    />
                  </div>
                  <p className="text-muted-foreground mt-1 text-xs">
                    Press Enter to add. Up to 12 tags of 32 characters.
                  </p>
                </div>
                <Checkbox
                  label="Make this character public"
                  description="Public characters can be used by all users."
                  checked={formData.isPublic}
                  onChange={(event) => update('isPublic', event.target.checked)}
                />
              </section>
            )}

            {tab === 'story' && (
              <section className="space-y-4" aria-label="Story">
                <div>
                  <h3 className="text-foreground flex items-center gap-2 font-semibold">
                    <BookOpen size={18} className="text-primary" aria-hidden />
                    Personality and history
                  </h3>
                  <p className="text-muted-foreground mt-1 text-xs">
                    Optional details that deepen the character’s voice and continuity.
                  </p>
                </div>
                {extendedFields.map(({ key, label, placeholder }) => (
                  <Textarea
                    key={key}
                    label={label}
                    name={key}
                    value={formData[key] ?? ''}
                    onChange={(event) => update(key, event.target.value)}
                    rows={key === 'backstory' ? 5 : 3}
                    placeholder={placeholder}
                  />
                ))}
              </section>
            )}

            {tab === 'stats' && (
              <section className="space-y-4" aria-label="Stats">
                <div>
                  <h3 className="text-foreground flex items-center gap-2 font-semibold">
                    <BarChart3 size={18} className="text-primary" aria-hidden />
                    Base attributes
                  </h3>
                  <p className="text-muted-foreground mt-1 text-xs">
                    A 1–20 scale where 10 is average. These start the character sheet in every
                    story; play can change the recorded stats without changing these.
                  </p>
                </div>
                <div className="grid gap-4 sm:grid-cols-2">
                  {ATTRIBUTE_FIELDS.map((attribute) => (
                    <Input
                      key={attribute.key}
                      label={attribute.label}
                      name={attribute.key}
                      type="number"
                      inputMode="numeric"
                      min={1}
                      max={20}
                      step={1}
                      value={formData[attribute.key] ?? ''}
                      onChange={(event) =>
                        update(
                          attribute.key,
                          event.target.value === '' ? undefined : event.target.valueAsNumber,
                        )
                      }
                      error={errors[attribute.key]}
                      helperText={attribute.hint}
                      placeholder="10"
                    />
                  ))}
                </div>
              </section>
            )}

            {tab === 'instructions' && (
              <section className="space-y-6" aria-label="Instructions">
                <div>
                  <h3 className="text-foreground flex items-center gap-2 font-semibold">
                    <Terminal size={18} className="text-primary" aria-hidden />
                    Voice and behavior
                  </h3>
                  <p className="text-muted-foreground mt-1 text-xs">
                    These instructions define how the character behaves and responds.
                  </p>
                </div>
                <Textarea
                  label="System prompt"
                  name="systemPrompt"
                  required
                  value={formData.systemPrompt}
                  onChange={(event) => update('systemPrompt', event.target.value)}
                  error={errors.systemPrompt}
                  rows={7}
                  helperText="Instructions that define how the character behaves and responds."
                  placeholder="You are a helpful assistant that…"
                  className="font-mono"
                />
                <div>
                  <p className="text-muted-foreground text-xs font-medium">Quick additions</p>
                  <div className="mt-1.5 flex flex-wrap gap-1.5">
                    {prompts.map((prompt) => (
                      <button
                        key={prompt}
                        type="button"
                        onClick={() =>
                          update(
                            'systemPrompt',
                            formData.systemPrompt.includes(prompt)
                              ? formData.systemPrompt
                              : `${formData.systemPrompt.trim()}\n\n${prompt}`.trim(),
                          )
                        }
                        className="border-border bg-background hover:bg-accent text-muted-foreground hover:text-foreground inline-flex min-h-10 items-center gap-1.5 rounded-full border px-3 py-1 text-left text-xs sm:min-h-0 sm:px-2.5"
                      >
                        <Sparkles size={11} aria-hidden />
                        {prompt.replace(/\.$/, '')}
                      </button>
                    ))}
                  </div>
                </div>
                <Textarea
                  label="Greeting message"
                  name="greeting"
                  value={formData.greeting ?? ''}
                  onChange={(event) => update('greeting', event.target.value)}
                  rows={3}
                  helperText="The first message shown when someone starts a conversation."
                  placeholder="Hello! How can I help you today?"
                />
                <div className="border-border space-y-5 border-t pt-5">
                  <h3 className="text-foreground font-semibold">Model controls</h3>
                  {sliders.map(({ key, label, min, max, step, help }) => (
                    <div key={key}>
                      <div className="mb-1 flex items-center justify-between gap-2 text-sm">
                        <label htmlFor={`${id}-${key}`} className="text-foreground font-medium">
                          {label}
                        </label>
                        <output
                          htmlFor={`${id}-${key}`}
                          className="text-muted-foreground tabular-nums"
                        >
                          {formData[key]}
                        </output>
                      </div>
                      <input
                        id={`${id}-${key}`}
                        type="range"
                        min={min}
                        max={max}
                        step={step}
                        value={formData[key]}
                        onChange={(event) => update(key, event.target.valueAsNumber)}
                        aria-describedby={`${id}-${key}-help`}
                        className="accent-primary min-h-8 w-full"
                      />
                      <p id={`${id}-${key}-help`} className="text-muted-foreground text-xs">
                        {help}
                      </p>
                    </div>
                  ))}
                  <Checkbox
                    label="Limit response length"
                    description="Set a maximum number of tokens for each response."
                    checked={formData.maxTokens !== -1}
                    onChange={(event) => {
                      setTokenDraft(null);
                      update('maxTokens', event.target.checked ? 2048 : -1);
                    }}
                  />
                  {formData.maxTokens !== -1 && (
                    <Input
                      label="Response token limit"
                      name="maxTokens"
                      type="number"
                      inputMode="numeric"
                      min={1}
                      max={1024000}
                      step={1}
                      value={tokenDraft ?? formData.maxTokens}
                      onChange={(event) => {
                        setTokenDraft(event.target.value);
                        if (Number.isFinite(event.target.valueAsNumber))
                          update('maxTokens', event.target.valueAsNumber);
                      }}
                      error={errors.maxTokens}
                      helperText="A whole number from 1 to 1,024,000."
                    />
                  )}
                  <button
                    type="button"
                    onClick={() => {
                      setTokenDraft(null);
                      onChange({
                        ...formData,
                        temperature: defaultFormData.temperature,
                        topP: defaultFormData.topP,
                        topK: defaultFormData.topK,
                        repeatPenalty: defaultFormData.repeatPenalty,
                        maxTokens: defaultFormData.maxTokens,
                      });
                    }}
                    className="text-muted-foreground hover:text-foreground inline-flex min-h-10 items-center text-xs underline-offset-4 hover:underline sm:min-h-0"
                  >
                    Reset model settings to defaults
                  </button>
                </div>
              </section>
            )}

            {tab === 'alignment' && (
              <section className="space-y-4" aria-label="Alignment">
                <div>
                  <h3 className="text-foreground flex items-center gap-2 font-semibold">
                    <Scale size={18} className="text-primary" aria-hidden />
                    Moral alignment
                  </h3>
                  <p className="text-muted-foreground mt-1 text-xs">
                    {characterId
                      ? 'Changes save immediately and persist across chats. Alignment guides values and methods; it never overrides your choices.'
                      : 'Choose the starting alignment. It is saved with the new character.'}
                  </p>
                </div>
                {characterId ? (
                  <CharacterAlignmentPanel
                    characterId={characterId}
                    onRecord={(record) => {
                      if (record.characterId !== characterId) return;
                      setLiveAlignment(record.alignment);
                      setLivePoints({
                        lawfulness: record.lawfulness,
                        goodness: record.goodness,
                      });
                      onChange({
                        ...formData,
                        lawfulness: record.lawfulness,
                        goodness: record.goodness,
                      });
                    }}
                  />
                ) : (
                  <AlignmentPicker
                    lawfulness={formData.lawfulness}
                    goodness={formData.goodness}
                    onChange={(next) =>
                      onChange({
                        ...formData,
                        lawfulness: next.lawfulness,
                        goodness: next.goodness,
                      })
                    }
                  />
                )}
              </section>
            )}

            {tab === 'feelings' && (
              <section className="space-y-5" aria-label="Feelings">
                <div>
                  <h3 className="text-foreground flex items-center gap-2 font-semibold">
                    <Heart size={18} className="text-primary" aria-hidden />
                    Feelings
                  </h3>
                  <p className="text-muted-foreground mt-1 text-xs">
                    {characterId
                      ? 'Directional feelings that persist across chats. Choose an exact tier or record a change.'
                      : 'Set how this character and you feel before any recorded moment. Neutral is the default.'}
                  </p>
                </div>
                {characterId ? (
                  <CharacterRelationshipsPanel characterId={characterId} />
                ) : (
                  <div className="space-y-5">
                    <FeelingStartingField
                      legend="This character feels toward you"
                      value={formData.initialAffinityToUser}
                      onChange={(points) => update('initialAffinityToUser', points)}
                      helper="How the character regards you before any recorded moment."
                    />
                    <FeelingStartingField
                      legend="You feel toward this character"
                      value={formData.initialAffinityFromUser}
                      onChange={(points) => update('initialAffinityFromUser', points)}
                      helper="How you regard the character before any recorded moment."
                    />
                    <p className="text-muted-foreground border-border rounded-lg border p-3 text-xs leading-relaxed">
                      Starting values appear in Relationships until you record a reaction. After the
                      character is saved, open this tab to manage every direction, including
                      feelings toward other characters.
                    </p>
                  </div>
                )}
              </section>
            )}
          </div>
        </fieldset>
      </div>

      <aside className="space-y-4 xl:sticky xl:top-4">
        <div className="border-border bg-card space-y-4 rounded-xl border p-4">
          <div className="flex items-start gap-3">
            {formData.avatar ? (
              <img
                src={formData.avatar}
                alt=""
                className="border-border h-14 w-14 rounded-xl border object-cover"
              />
            ) : (
              <div className="from-secondary to-secondary-hover flex h-14 w-14 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br">
                <Bot size={24} className="text-secondary-foreground" aria-hidden />
              </div>
            )}
            <div className="min-w-0">
              <p className="text-foreground truncate font-semibold">
                {formData.name.trim() || 'Untitled character'}
              </p>
              <p className="text-muted-foreground text-xs">
                {characterId ? 'Saved character' : 'Unsaved draft'}
              </p>
            </div>
          </div>
          <div className="flex flex-wrap gap-1.5">
            <span className="bg-accent text-muted-foreground text-2xs rounded-md px-2 py-0.5 capitalize">
              {formData.category}
            </span>
            {formData.isPublic && (
              <span className="bg-primary/10 text-primary text-2xs rounded-md px-2 py-0.5">
                Public
              </span>
            )}
            <span
              className={cn(
                'text-2xs inline-flex items-center gap-1 rounded-md border px-2 py-0.5 font-medium',
                meta.cell,
                meta.text,
              )}
            >
              <span className={cn('h-1.5 w-1.5 rounded-full', meta.dot)} aria-hidden />
              {meta.label}
            </span>
          </div>
          <dl className="text-muted-foreground grid grid-cols-2 gap-2 border-t pt-3 text-xs">
            <div>
              <dt>Order</dt>
              <dd className="text-foreground font-medium tabular-nums">
                {points.lawfulness > 0 ? '+' : ''}
                {points.lawfulness}
              </dd>
            </div>
            <div>
              <dt>Morality</dt>
              <dd className="text-foreground font-medium tabular-nums">
                {points.goodness > 0 ? '+' : ''}
                {points.goodness}
              </dd>
            </div>
            <div>
              <dt>Tags</dt>
              <dd className="text-foreground font-medium">{formData.tags?.length ?? 0}</dd>
            </div>
            <div>
              <dt>Answers</dt>
              <dd className="text-foreground font-medium">
                {formData.maxTokens === -1 ? 'No limit' : `${formData.maxTokens} tokens`}
              </dd>
            </div>
          </dl>
        </div>
        <div className="hidden space-y-2 xl:block">
          <Button
            type="submit"
            isLoading={isLoading}
            loadingText="Saving…"
            disabled={saveSuccess}
            leftIcon={<Check size={16} />}
            className="w-full"
          >
            {saveSuccess ? 'Saved!' : submitLabel}
          </Button>
          <p className="text-muted-foreground text-center text-xs">
            {characterId
              ? 'Alignment and feelings save immediately; other fields save with this button.'
              : 'The character is created when you save.'}
          </p>
        </div>
      </aside>

      {/* The save action sits under the form on narrow screens, so keep it in reach. */}
      <div className="border-border bg-background sticky bottom-0 z-20 -mx-4 border-t px-4 py-3 sm:-mx-6 sm:px-6 xl:hidden">
        <div className="flex gap-3">
          {onCancel && (
            <Button
              type="button"
              variant="ghost"
              onClick={onCancel}
              disabled={isLoading}
              className="flex-1"
            >
              Cancel
            </Button>
          )}
          <Button
            type="submit"
            isLoading={isLoading}
            loadingText="Saving…"
            disabled={saveSuccess}
            leftIcon={<Check size={16} />}
            className="flex-1"
          >
            {saveSuccess ? 'Saved!' : 'Save'}
          </Button>
        </div>
      </div>
    </form>
  );
}

function FeelingStartingField({
  legend,
  value,
  onChange,
  helper,
}: {
  legend: string;
  value: number;
  onChange: (points: number) => void;
  helper: string;
}) {
  return (
    <fieldset className="min-w-0">
      <legend className="text-foreground text-sm font-medium">{legend}</legend>
      <div role="radiogroup" aria-label={legend} className="mt-2 grid grid-cols-5 gap-1">
        {startingFeelings.map((feeling) => (
          <button
            key={feeling.tier}
            type="button"
            role="radio"
            aria-checked={value === feeling.points}
            aria-label={feeling.label}
            onClick={() => onChange(feeling.points)}
            className={cn(
              'min-h-10 rounded-lg border px-0.5 py-1.5 text-[11px] font-medium transition-colors sm:min-h-9 sm:px-1 sm:text-xs',
              value === feeling.points
                ? 'border-primary bg-primary text-primary-foreground'
                : 'border-border bg-background hover:bg-accent',
              feeling.points < 0 && value !== feeling.points && 'text-destructive',
            )}
          >
            {feeling.label}
          </button>
        ))}
      </div>
      <p className="text-muted-foreground mt-1.5 text-xs">
        {helper} Selected score: {value > 0 ? '+' : ''}
        {value}.
      </p>
    </fieldset>
  );
}
