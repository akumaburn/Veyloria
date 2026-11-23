export type AffinityTier = 'HATE' | 'DISLIKE' | 'NEUTRAL' | 'LIKE' | 'LOVE';
export type Reaction = Exclude<AffinityTier, 'NEUTRAL'> | 'RESET' | 'SET';
export interface AffinityEvent {
  eventId: string;
  reaction: Reaction;
  delta: number;
  previousPoints: number;
  points: number;
  reason: string | null;
  createdAt: string;
}
export interface CharacterAffinity {
  affinityId: string;
  fromCharacterId: string | null;
  toCharacterId: string | null;
  fromName: string;
  toName: string;
  points: number;
  tier: AffinityTier;
  updatedAt: string;
  events: AffinityEvent[];
}
export interface RelationshipActor {
  characterId: string;
  name: string;
  /** Starting feeling toward the user, used only while the pair has no saved record. */
  initialAffinityToUser?: number;
  /** Starting feeling from the user, used only while the pair has no saved record. */
  initialAffinityFromUser?: number;
}
export interface RelationshipsData {
  relationshipActors: RelationshipActor[];
  characterAffinities: CharacterAffinity[];
}
export const REACTIONS = [
  { reaction: 'HATE', label: 'Hate', delta: -25 },
  { reaction: 'DISLIKE', label: 'Dislike', delta: -10 },
  { reaction: 'LIKE', label: 'Like', delta: 10 },
  { reaction: 'LOVE', label: 'Love', delta: 25 },
] as const;

/** Exact tier anchors. Choosing one sets the direction to that score. */
export const AFFINITY_TIERS = [
  { tier: 'HATE', label: 'Hate', points: -60 },
  { tier: 'DISLIKE', label: 'Dislike', points: -20 },
  { tier: 'NEUTRAL', label: 'Neutral', points: 0 },
  { tier: 'LIKE', label: 'Like', points: 20 },
  { tier: 'LOVE', label: 'Love', points: 60 },
] as const;
export const signedPoints = (points: number) => (points > 0 ? `+${points}` : String(points));
export const actorKey = (id: string | null) => id ?? 'user';
export const tierLabel = (tier: string) => tier.charAt(0) + tier.slice(1).toLowerCase();
/** Mirrors the backend tier bands so a configured starting value shows the right label. */
export const tierOf = (points: number): AffinityTier =>
  points >= 60
    ? 'LOVE'
    : points >= 20
      ? 'LIKE'
      : points <= -60
        ? 'HATE'
        : points <= -20
          ? 'DISLIKE'
          : 'NEUTRAL';
