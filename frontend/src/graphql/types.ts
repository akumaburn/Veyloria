// ============================================
// USER TYPES
// ============================================

export interface User {
  id: string;
  username: string;
  email: string;
  displayName?: string;
  role: 'user' | 'admin';
  createdAt: string;
  lastLogin?: string;
  emailVerified?: boolean;
  profilePicture?: string;
  bio?: string;
  isBanned?: boolean;
  bannedAt?: string;
  bannedReason?: string;
  preferences: UserPreferences;
}

export interface UserPreferences {
  theme: 'dark' | 'light' | 'system';
  language: string;
  fontSize: 'small' | 'medium' | 'large';
  soundEnabled: boolean;
  notificationsEnabled: boolean;
  codeTheme?: string;
  analytics?: boolean;
}

export interface AuthResponse {
  user: User;
  accessToken: string;
  refreshToken: string;
  expiresIn?: number;
}

export interface UpdateUserInput {
  displayName?: string;
  email?: string;
}

// ============================================
// CONVERSATION TYPES
// ============================================

export interface Conversation {
  id: string;
  title: string;
  description?: string;
  createdAt: string;
  updatedAt: string;
  lastMessageAt?: string;
  messageCount: number;
  isPinned: boolean;
  isArchived: boolean;
  category?: string;
  tags: string[];
  owner: Pick<User, 'id' | 'username'>;
  character?: Character;
  // Group chat fields
  isGroupChat?: boolean;
  turnMode?: TurnMode;
  currentSpeaker?: Pick<Character, 'id' | 'name' | 'avatar'>;
  lastSpeaker?: Pick<Character, 'id' | 'name' | 'avatar'>;
  maxConsecutiveResponses?: number;
  consecutiveResponseCount?: number;
  groupState?: GroupState;
  processingOnly?: boolean;
  processingStatus?: import('@/services/conversationProgress').ConversationProgress | null;
  characters?: ConversationCharacter[];
  pendingInterjections?: InterjectionRequest[];
  // Last message (returned by ConversationFields fragment)
  lastMessage?: {
    id: string;
    content: string;
    role: 'user' | 'assistant' | 'system';
    createdAt: string;
  };
  // Roleplay fields
  isRoleplay?: boolean;
  rpMode?: string;
  agentPipelineEnabled?: boolean;
  activeSceneId?: string;
  storyTemplateId?: string;
}

// ============================================
// GROUP CHAT TYPES
// ============================================

export type TurnMode = 'RANDOM' | 'ROUND_ROBIN' | 'WEIGHTED';

export type GroupState = 'INITIALIZING' | 'WAITING_FOR_USER' | 'CHARACTER_RESPONDING' | 'PAUSED' | 'INTERJECTION_PENDING';

export interface ConversationCharacter {
  id: string;
  character: Pick<Character, 'id' | 'name' | 'description' | 'avatar' | 'systemPrompt' | 'personality' | 'gender' | 'pronouns' | 'ageYears'>;
  joinedAt: string;
  isActive: boolean;
  turnOrder: number;
  responseCount: number;
  lastResponseAt?: string;
  weight?: number;
  interjectionProbability?: number;
}

export interface InterjectionRequest {
  id: string;
  requester?: Pick<Character, 'id' | 'name' | 'avatar'>;
  requesterType: 'user' | 'character';
  reason?: string;
  priority: number;
  status: 'pending' | 'approved' | 'rejected' | 'expired';
  createdAt: string;
  resolvedAt?: string;
}

export interface CreateGroupConversationInput {
  title?: string;
  characterIds: string[];
  charactersWithWeights?: CharacterWeight[];
  turnMode?: TurnMode;
  maxConsecutiveResponses?: number;
}

export interface CharacterWeight {
  characterId: string;
  weight: number;
}

export interface AddCharacterToGroupInput {
  conversationId: string;
  characterId: string;
  weight?: number;
  turnOrder?: number;
}

export interface RemoveCharacterFromGroupInput {
  conversationId: string;
  characterId: string;
}

export interface UpdateGroupSettingsInput {
  turnMode?: TurnMode;
  maxConsecutiveResponses?: number;
}

export interface SendGroupMessageInput {
  conversationId: string;
  content: string;
}

export interface RequestInterjectionInput {
  conversationId: string;
  characterId?: string;
  reason?: string;
  priority?: number;
}

export interface ConversationFilter {
  category?: string;
  isPinned?: boolean;
  isArchived?: boolean;
  characterId?: string;
}

export interface CreateConversationInput {
  title: string;
  characterId?: string;
}

export interface UpdateConversationInput {
  title?: string;
  description?: string;
  category?: string;
  tags?: string[];
}

export interface PaginatedConversations {
  items: Conversation[];
  totalCount: number;
  hasMore: boolean;
}

// ============================================
// MESSAGE TYPES
// ============================================

export interface Message {
  id: string;
  content: string;
  contentBase64?: string;
  role: 'user' | 'assistant' | 'system';
  isBase64Encoded?: boolean;
  createdAt: string;
  updatedAt?: string;
  editCount: number;
  isEdited: boolean;
  isDeleted: boolean;
  generationStats?: GenerationStats;
  author?: Pick<User, 'id' | 'username'>;
  conversation: Pick<Conversation, 'id'>;
  attachments?: Attachment[];
  // Group chat fields
  character?: Pick<Character, 'id' | 'name' | 'avatar' | 'personality' | 'gender' | 'pronouns' | 'ageYears'>;
  characterId?: string;
  displayName?: string;
  isInterjection?: boolean;
  battleId?: string;
  messageType?: 'DIALOGUE' | 'NARRATION' | 'ACTION' | 'OOC' | 'SYSTEM' | 'DICE';
}

/**
 * Extended Message type for store state with additional UI properties
 */
export interface StoreMessage extends Omit<Message, 'conversation'> {
  conversationId: string;
  status?: 'sending' | 'sent' | 'error' | 'generating';
  error?: string;
}

export interface Attachment {
  id: string;
  filename: string;
  mimeType: string;
  size: number;
  url: string;
}

export interface SendMessageInput {
  conversationId: string;
  content: string;
  attachments?: File[];
}

export interface PaginatedMessages {
  items: Message[];
  hasMore: boolean;
  cursor?: string;
}

// ============================================
// MESSAGE SEARCH TYPES
// ============================================

export interface MessageSearchResult {
  id: string;
  content: string;
  role: 'user' | 'assistant' | 'system';
  displayName?: string;
  conversation: Pick<Conversation, 'id' | 'title'>;
  highlightedContent?: string;
  createdAt: string;
}

export interface MessageSearchPagination {
  items: MessageSearchResult[];
  totalCount: number;
  hasMore: boolean;
}

export interface MessageSearchInput {
  query: string;
  conversationId?: string;
  limit?: number;
  offset?: number;
}

// ============================================
// MESSAGE REACTION TYPES
// ============================================

export interface Reaction {
  id: string;
  messageId: string;
  userId: string;
  user?: Pick<User, 'id' | 'username'>;
  reactionType: string;
  createdAt: string;
}

export interface ReactionSummary {
  reactionType: string;
  count: number;
  userReacted: boolean;
}

// Generation statistics for LLM responses
export interface GenerationStats {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  promptEvalMs: number;
  generationMs: number;
  totalTimeMs: number;
  tokensPerSecond: number;
}

export interface StreamingMessage extends Message {
  streamingContent?: string;
  isComplete: boolean;
  /** In-band generation failure reported on the final frame. */
  error?: string;
  /** The conversation the frame belongs to; frames for any other are discarded. */
  conversationId?: string;
  generationStats?: GenerationStats;
}

// ============================================
// CHARACTER TYPES
// ============================================

export interface Character {
  id: string;
  name: string;
  description: string;
  personality?: string;
  avatar?: string;
  greeting?: string;
  systemPrompt: string;
  temperature: number;
  maxTokens: number;
  topP: number;
  topK: number;
  repeatPenalty: number;
  isPublic: boolean;
  isDefault: boolean;
  isFavorite?: boolean;
  rating?: number;
  usageCount: number;
  creator: Pick<User, 'id' | 'username'>;
  tags: string[];
  createdAt: string;
  updatedAt: string;
  // Extended character fields
  appearance?: string;
  likes?: string;
  dislikes?: string;
  backstory?: string;
  goals?: string;
  examples?: string;
  category?: string;
  /** Physical profile generated with the character. */
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
  photoAlbum?: string[];
  chatBackgroundImage?: string;
  /** How this character feels toward the user until a reaction is recorded. */
  initialAffinityToUser?: number;
  /** How the user feels toward this character until a reaction is recorded. */
  initialAffinityFromUser?: number;
}

export interface CharacterGroup {
  id: string;
  name: string;
  description?: string;
  setting?: string;
  characterIds: string[];
  characterCount: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface CharacterFilter {
  search?: string;
  tags?: string[];
  isPublic?: boolean;
  isFavorite?: boolean;
  category?: string;
}

/** How a generated character feels toward another member of the same roster. */
export interface CharacterDraftRelationship {
  targetName: string;
  tier: string;
  reason: string;
}

/** An unsaved generated character with its starting alignment and feelings. */
export interface CharacterDraft {
  character: Character;
  lawfulness: number;
  goodness: number;
  alignment: string;
  relationships: CharacterDraftRelationship[];
}

export interface CreateCharacterInput {
  name: string;
  description?: string;
  avatar?: string;
  greeting?: string;
  systemPrompt?: string;
  temperature?: number;
  maxTokens?: number;
  topP?: number;
  topK?: number;
  repeatPenalty?: number;
  isPublic?: boolean;
  appearance?: string;
  personality?: string;
  likes?: string;
  dislikes?: string;
  backstory?: string;
  goals?: string;
  examples?: string;
  category?: string;
}

export interface UpdateCharacterInput extends Partial<CreateCharacterInput> {
  photoAlbum?: string[];
  chatBackgroundImage?: string;
}

export interface PaginatedCharacters {
  items: Character[];
  totalCount: number;
  hasMore: boolean;
}

// ============================================
// SETTINGS TYPES
// ============================================

export interface Settings {
  llm: LLMSettings;
  ui: UISettings;
  privacy: PrivacySettings;
  notifications: NotificationSettings;
  keyboard: KeyboardSettings;
}

export interface LLMSettings {
  model?: string;
  temperature: number;
  maxTokens: number;
  topP: number;
  topK: number;
  repeatPenalty: number;
  presencePenalty: number;
  frequencyPenalty: number;
  minP: number;
  tfsZ: number;
  typicalP: number;
  seed?: number;
  stopSequences: string[];
  nPredict: number;
  nCtx: number;
  nBatch: number;
  nThreads: number;
  nGpuLayers: number;
  mainGpu: number;
  promptTemplate: string;
  systemPrompt?: string;
  chatFormat: string;
  streamingEnabled: boolean;
  mirostat: number;
  mirostatTau: number;
  mirostatEta: number;
  contextSize: number;
  templates: string[];
  currentTemplate: string;
}

export interface UISettings {
  theme: string;
  fontSize: string;
  fontFamily: string;
  compactMode: boolean;
  showTimestamps: boolean;
  showTokenCount: boolean;
  showModelInfo: boolean;
  enterToSend: boolean;
  soundEnabled: boolean;
  notificationsEnabled: boolean;
  autoScroll: boolean;
  messageGrouping: boolean;
  syntaxHighlighting: boolean;
  markdownRendering: boolean;
  emojiPicker: boolean;
  spellCheck: boolean;
  autoSave: boolean;
  autoSaveInterval: number;
  sidebarPosition: string;
  sidebarCollapsed: boolean;
  messageLayout: string;
  language: string;
  codeTheme: string;
  editorTheme: string;
  showLineNumbers: boolean;
  wordWrap: boolean;
}

export interface PrivacySettings {
  shareAnalytics: boolean;
  saveHistory: boolean;
  encryptMessages: boolean;
  clearOnLogout: boolean;
  exportFormat: string;
  dataRetentionDays: number;
  analytics: boolean;
  shareData: boolean;
  publicProfile: boolean;
  showOnlineStatus: boolean;
}

export interface NotificationSettings {
  desktop: boolean;
  sound: boolean;
  vibration: boolean;
  mentions: boolean;
  replies: boolean;
  newConversations: boolean;
  systemAlerts: boolean;
  schedule: string;
  customSchedule?: {
    startTime: string;
    endTime: string;
    daysOfWeek: number[];
  };
  enabled: boolean;
  email: boolean;
  messageAlerts: boolean;
}

/**
 * Entry shape for Map<String, String> fields as serialized by SmallRye GraphQL.
 * Preserves ordering in the wire format; callers convert to Record as needed.
 */
export interface ShortcutEntry {
  key: string;
  value: string;
}

export interface KeyboardSettings {
  shortcuts: ShortcutEntry[];
}

/** Keyboard shortcuts input — mutation side uses the same entry shape. */
export interface KeyboardSettingsInput {
  shortcuts?: ShortcutEntry[];
}

export interface UpdateSettingsInput {
  llm?: Partial<LLMSettings>;
  ui?: Partial<UISettings>;
  privacy?: Partial<PrivacySettings>;
  notifications?: Partial<NotificationSettings>;
  keyboard?: KeyboardSettingsInput;
}

// ============================================
// ADMIN TYPES
// ============================================

export interface AdminStats {
  users: {
    total: number;
    active: number;
    banned: number;
    online: number;
  };
  messages: {
    total: number;
    today: number;
    avgPerUser: number;
    avgResponseTime: number;
  };
  conversations: {
    total: number;
    active: number;
    avgLength: number;
  };
  system: {
    uptime: string;
    cpuUsage: number;
    memoryUsage: number;
    diskUsage: number;
    redisConnections: number;
    dbConnections: number;
  };
  llm: {
    status: 'online' | 'offline' | 'error';
    model: string;
    requestsToday: number;
    avgTokensPerRequest: number;
    queueLength: number;
    errorRate: number;
  };
}

export interface AdminUser extends User {
  status: 'active' | 'banned' | 'inactive';
  messageCount: number;
  conversationCount: number;
}

export interface UserFilter {
  search?: string;
  role?: 'user' | 'admin';
  status?: 'active' | 'banned' | 'inactive';
  createdFrom?: string;
  createdTo?: string;
}

export interface PaginatedAdminUsers {
  items: AdminUser[];
  totalCount: number;
  hasMore: boolean;
}

export interface SystemLog {
  id: string;
  timestamp: string;
  level: 'info' | 'warn' | 'error';
  source: string;
  message: string;
  details?: any;
}

// ============================================
// SUBSCRIPTION TYPES
// ============================================

export interface ConversationUpdate {
  type: 'message_added' | 'message_edited' | 'message_deleted' | 'conversation_updated';
  conversation?: Conversation;
  message?: Message;
}

export interface TypingIndicator {
  userId: string;
  conversationId: string;
  username: string;
  isTyping: boolean;
}

export interface CharacterSwitch {
  conversation: Conversation;
  previousCharacter?: Pick<Character, 'id' | 'name'>;
  newCharacter: Pick<Character, 'id' | 'name'>;
}

export interface UserPresence {
  userId: string;
  username: string;
  status: 'online' | 'away' | 'busy' | 'offline';
  lastSeen: string;
}

export interface SystemNotification {
  id: string;
  type: 'info' | 'warning' | 'error' | 'success';
  title: string;
  message: string;
  severity: 'low' | 'medium' | 'high' | 'critical';
  timestamp: string;
}

// ============================================
// HEALTH & STATUS TYPES
// ============================================

export interface HealthStatus {
  status: 'healthy' | 'degraded' | 'unhealthy';
  uptime: string;
  version: string;
  services: ServiceStatus[];
}

export interface ServiceStatus {
  name: string;
  status: 'online' | 'offline' | 'error';
  latency?: number;
}

export interface LLMStatus {
  isRunning: boolean;
  modelLoaded: boolean;
  currentModel?: string;
  availableMemory: number;
  usedMemory: number;
  providerType: string;
}

export interface LLMConfig {
  modelPath: string;
  gpuLayers: number;
  contextSize: number;
  batchSize: number;
  threads: number;
  temperature: number;
  topP: number;
  topK: number;
  templates: string[];
  currentTemplate: string;
  providerType: string;
  externalBaseUrl?: string;
  externalModel?: string;
}

// ============================================
// ERROR TYPES
// ============================================

export interface GraphQLError {
  message: string;
  extensions?: {
    code?: string;
    field?: string;
    details?: any;
  };
  path?: string[];
  locations?: Array<{
    line: number;
    column: number;
  }>;
}

export interface NetworkError {
  message: string;
  statusCode?: number;
  response?: any;
}

// ============================================
// UTILITY TYPES
// ============================================

export type ID = string;

export type Timestamp = string;

export type Base64 = string;

export type JSON = string | number | boolean | null | { [key: string]: JSON } | JSON[];

export interface PaginationInput {
  limit?: number;
  offset?: number;
}

export interface SortInput {
  field: string;
  direction: 'ASC' | 'DESC';
}

export interface DateRangeInput {
  from?: string;
  to?: string;
}

export interface FileInput {
  filename: string;
  mimeType: string;
  data: Base64;
}

// ============================================
// ENUM TYPES
// ============================================

export enum Role {
  USER = 'user',
  ADMIN = 'admin',
}

export enum MessageRole {
  USER = 'user',
  ASSISTANT = 'assistant',
  SYSTEM = 'system',
}

export enum Theme {
  DARK = 'dark',
  LIGHT = 'light',
  SYSTEM = 'system',
}

export enum NotificationType {
  INFO = 'info',
  WARNING = 'warning',
  ERROR = 'error',
  SUCCESS = 'success',
}

export enum UserStatus {
  ACTIVE = 'active',
  BANNED = 'banned',
  INACTIVE = 'inactive',
}

export enum LogLevel {
  INFO = 'info',
  WARN = 'warn',
  ERROR = 'error',
  DEBUG = 'debug',
}

// ============================================
// RESPONSE TYPES
// ============================================

export interface MutationResponse {
  success: boolean;
  message?: string;
  errors?: GraphQLError[];
}

export interface DeleteResponse extends MutationResponse {}

export interface BatchResponse<T> extends MutationResponse {
  items?: T[];
  failedItems?: Array<{ item: T; error: string }>;
}

export interface UploadResponse extends MutationResponse {
  url?: string;
  filename?: string;
  size?: number;
}

export interface ExportResponse extends MutationResponse {
  data?: string;
  format?: 'json' | 'csv' | 'xml';
  size?: number;
}

export interface ImportResponse extends MutationResponse {
  importedCount?: number;
  skippedCount?: number;
  importErrors?: Array<{ line: number; error: string }>;
}

// ============================================
// UI-SPECIFIC TYPES
// These types are used for component rendering
// ============================================

/**
 * UI representation of a message for rendering in components.
 * Transforms GraphQL Message type to a format suitable for the chat interface.
 */
export interface UIMessage {
  id: string;
  content: string;
  role: 'user' | 'assistant' | 'system';
  timestamp: string;
  userId?: string;
  characterId?: string;
  displayName?: string;
  attachments?: UIAttachment[];
  metadata?: {
    model?: string;
    completionTokens?: number;
    totalTimeMs?: number;
    edited?: boolean;
    editedAt?: string;
  };
  status?: 'sending' | 'sent' | 'error' | 'generating';
}

export interface UIAttachment {
  id: string;
  type: string;
  name: string;
  url: string;
  size: number;
}

/**
 * Stream chunk from GraphQL subscription
 */
export interface StreamChunk {
  id: string;
  content?: string;
  streamingContent?: string;
  role: string;
  isComplete: boolean;
  tokenCount?: number;
  error?: string;
  createdAt: string;
  // Group chat character attribution fields
  characterId?: string;
  displayName?: string;
  conversationId?: string;
  battleId?: string;
  messageType?: 'DIALOGUE' | 'NARRATION' | 'ACTION' | 'OOC' | 'SYSTEM' | 'DICE';
  // Generation statistics (populated when isComplete=true)
  generationStats?: GenerationStats;
}

/**
 * Preset for LLM settings
 */
export interface LLMPreset {
  id: string;
  name: string;
  description?: string;
  settings: Partial<LLMSettings>;
  isDefault?: boolean;
  createdAt: string;
  updatedAt: string;
}

// ============================================
// ROLEPLAY TYPES
// ============================================

// Mirrors the SmallRye-generated WorldState type. JSON-shaped fields
// (globalFlags, locations, activeEffects, narratorNotes) are intentionally
// omitted because they are @Ignore on the backend model.
export interface WorldState {
  stateId: string;
  conversationId: string;
  templateId?: string;
  currentScene?: string;
  currentLocation?: string;
  currentLocationId?: string;
  currentArea?: string;
  currentAreaId?: string;
  timeOfDay?: string;
  dayNumber?: number;
  weather?: string;
  mood?: string;
}

// JSON-shaped template data (defaultWorldState, suggestedCharacterArchetypes,
// rules, moodPresets) is held server-side and applied via
// initializeFromTemplate, not exposed as GraphQL fields.
export interface StoryTemplate {
  templateId: string;
  name: string;
  genre: string;
  description?: string;
  isBuiltIn?: boolean;
  isPublic?: boolean;
  ownerId?: string;
}

export interface CharacterStat {
  statId: string;
  conversationId: string;
  characterId: string;
  statName: string;
  statValue: number;
  statMax?: number;
  statType?: string;
  statCategory?: string;
  textValue?: string;
  isVisible?: boolean;
}

// `history` is a JsonObject marked @Ignore on the backend model and not
// exposed through GraphQL.
export interface CharacterRelationship {
  relationshipId: string;
  conversationId: string;
  fromCharacterId: string;
  toCharacterId: string;
  relationshipType: string;
  intensity?: number;
  description?: string;
  isReciprocal?: boolean;
}

export interface CharacterInventoryItem {
  inventoryId: string;
  conversationId: string;
  characterId?: string;
  userId?: string;
  itemName: string;
  itemDescription?: string;
  quantity?: number;
  category?: string;
  properties?: string;
  isEquipped?: boolean;
}

// `worldStateSnapshot` is a JsonObject marked @Ignore on the backend model
// and not exposed through GraphQL.
export interface StoryBookmark {
  bookmarkId: string;
  conversationId: string;
  messageId?: string;
  label: string;
  description?: string;
  tags?: string[];
  createdAt?: string;
}

export interface DiceRollResult {
  rollId: string;
  conversationId: string;
  characterId?: string;
  expression: string;
  results: number[];
  total: number;
  modifier?: number;
  purpose?: string;
  isHidden?: boolean;
  createdAt?: string;
}

export interface Scene {
  sceneId: string;
  conversationId: string;
  name: string;
  location?: string;
  description?: string;
  isActive?: boolean;
  characters?: Pick<Character, 'id' | 'name' | 'avatar'>[];
  parentSceneId?: string;
  createdAt?: string;
  endedAt?: string;
}

// `visibility` ("ALL" | character-ID array) is marked @Ignore on the backend
// model. Visibility is enforced server-side, so it's not exposed to GraphQL.
export interface KnowledgeEntry {
  entryId: string;
  conversationId: string;
  summary: string;
  importance?: number;
  dayNumber?: number;
  tags?: string[];
  isActive?: boolean;
}

export interface AgentModelConfig {
  conversationId: string;
  defaultModel: string;
  highlighterModel?: string;
  museModel?: string;
  narratorModel?: string;
  accountantModel?: string;
  skepticModel?: string;
  casterModel?: string;
}

// The agent's raw output JsonObject is marked @Ignore on the backend, so
// there is no `summary` or `output` field surfaced via GraphQL.
export interface AgentRun {
  runId: string;
  conversationId: string;
  triggerMessageId?: string;
  agentName: string;
  agentPass?: string;
  modelUsed?: string;
  inputTokens?: number;
  outputTokens?: number;
  durationMs?: number;
  status: string;
  retryCount?: number;
  errorMessage?: string;
  createdAt?: string;
}

export interface CharacterProposal {
  proposalId: string;
  conversationId: string;
  triggerMessageId?: string;
  castingPrompt?: string;
  proposedName?: string;
  proposedDescription?: string;
  proposedSystemPrompt?: string;
  proposedGreeting?: string;
  proposedPersonality?: string;
  proposedAppearance?: string;
  proposedBackstory?: string;
  proposedGoals?: string;
  accountantReview?: string;
  skepticReview?: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'FAILED';
  createdCharacterId?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface GeneratedImage {
  imageId: string;
  conversationId: string;
  messageId?: string;
  characterId?: string;
  prompt: string;
  negativePrompt?: string;
  workflowId?: string;
  /** Generation status from row metadata: queued | complete | failed */
  status?: string;
  filePath: string;
  thumbnailPath?: string;
  width?: number;
  height?: number;
  seed?: string;
  sceneContext?: string;
  createdAt?: string;
}

export interface ComfyUIStatus {
  connected: boolean;
  queueSize?: number;
  availableModels?: string[];
  availableLoras?: string[];
}

// `workflowJson` and `defaultParams` are JsonObject fields marked @Ignore on
// the backend model; the raw ComfyUI payload is not exposed to the client.
export interface ComfyUIWorkflow {
  workflowId: string;
  name: string;
  description?: string;
  triggerType: string;
  isBuiltIn?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface Achievement {
  achievementId: string;
  name: string;
  description: string;
  icon?: string;
  category?: string;
  tier?: string;
  xpReward?: number;
  isHidden?: boolean;
}

export interface UserAchievement {
  id: string;
  userId: string;
  achievementId: string;
  achievement?: Achievement;
  progress?: number;
  isComplete?: boolean;
  completedAt?: string;
  conversationId?: string;
}

export interface UserProgression {
  userId: string;
  totalXP?: number;
  level?: number;
  title?: string;
  storiesCompleted?: number;
  totalMessages?: number;
  totalDiceRolls?: number;
  totalPlaytimeMin?: number;
}

// `worldStateSnapshot` is a JsonObject marked @Ignore on the backend model
// and is not exposed through GraphQL.
export interface StoryBranch {
  branchId: string;
  parentConversationId: string;
  branchConversationId?: string;
  branchPointMessageId: string;
  label: string;
  description?: string;
  createdAt?: string;
}

// ============================================
// BATTLE TYPES
// ============================================

export interface Battle {
  battleId: string;
  conversationId: string;
  status: string;
  roundNumber: number;
  currentNarration?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface BattleVote {
  id: string;
  battleId: string;
  roundNumber: number;
  userId: string;
  optionIndex: number;
  createdAt?: string;
}

export interface BattleAction {
  title: string;
  description: string;
  type: 'attack' | 'defend' | 'special' | 'item' | 'retreat';
}

export interface BattleRoundData {
  battleId: string;
  conversationId: string;
  narration: string;
  actions: BattleAction[];
  roundNumber: number;
  battleState?: {
    enemyHp?: number;
    partyStatus?: string;
    roundOutcome?: string;
  };
  battleEnded?: boolean;
  endStatus?: string;
}

export interface InitiateBattleInput {
  conversationId: string;
  enemyName: string;
  enemyDescription?: string;
  enemyHp?: number;
  narrativeContext?: string;
}

export interface VoteActionInput {
  battleId: string;
  optionIndex: number;
}

export interface WorldMap {
  mapId: string;
  conversationId: string;
  name: string;
  mapType?: string;
  mapScale?: string;
  imageId?: string;
  parentMapId?: string;
  gridWidth?: number;
  gridHeight?: number;
  locations?: MapLocation[];
}

export interface MapLocation {
  locationId: string;
  mapId: string;
  name: string;
  description?: string;
  x: number;
  y: number;
  z: number;
  icon?: string;
  locationType?: string;
  linkedSceneId?: string;
  isDiscovered?: boolean;
  movementCost?: number;
}

export interface LocalArea {
  localAreaId: string;
  mapId: string;
  locationId: string;
  name: string;
  description?: string;
  areaType?: string;
  x: number;
  y: number;
  isGateway?: boolean;
  isEntry?: boolean;
  sortOrder?: number;
}

export interface LocalAreaConnection {
  connectionId: string;
  mapId: string;
  locationId: string;
  fromAreaId: string;
  toAreaId: string;
  label?: string;
}

export interface LocalMap {
  mapId: string;
  locationId: string;
  locationName: string;
  locationType?: string;
  currentAreaId?: string;
  currentAreaName?: string;
  visitedAreaIds?: string[];
  areas: LocalArea[];
  connections: LocalAreaConnection[];
}

export interface TravelResult {
  distance: number;
  effectiveCost: number;
  timePeriodsRequired: number;
  usesRoad: boolean;
  roadType?: string;
  dominantBiome?: string;
  fromLocationId: string;
  toLocationId: string;
  fromLocationName: string;
  toLocationName: string;
  discoveredLocationIds: string[];
}

export type JourneyStatus = 'TRAVELING' | 'PAUSED' | 'INTERRUPTED' | 'COMPLETED' | 'CANCELLED';

export interface JourneyState {
  journeyId: string;
  conversationId: string;
  mapId: string;
  userId: string;
  characterIds?: string[];
  fromLocationId: string;
  fromLocationName?: string;
  toLocationId: string;
  toLocationName?: string;
  totalSteps: number;
  currentStep: number;
  status: JourneyStatus;
  interruptReason?: string;
  distance?: number;
  effectiveCost?: number;
  timePeriodsRequired?: number;
  usesRoad?: boolean;
  stepResults?: JourneyStepResult[];
  createdAt?: string;
  updatedAt?: string;
}

export interface JourneyStepResult {
  journeyId: string;
  stepNumber: number;
  totalSteps: number;
  narration?: string;
  eventType?: string;
  x: number;
  y: number;
  z: number;
  biome?: string;
  timeOfDay?: string;
  dayNumber?: number;
  journeyComplete: boolean;
  journeyInterrupted: boolean;
  journeyPaused?: boolean;
  battleId?: string;
  interruptReason?: string;
  discoveredLocationIds?: string[];
}

export interface MapCharacterPosition {
  id: string;
  characterId?: string;
  userId?: string;
  mapId: string;
  locationId?: string;
  x?: number;
  y?: number;
  z?: number;
}

export interface MapItemPosition {
  id: string;
  mapId: string;
  conversationId: string;
  itemName: string;
  itemDescription?: string;
  itemType?: string;
  x: number;
  y: number;
  z: number;
  linkedLocationId?: string;
  isDiscovered?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface DistanceResult {
  distance: number;
  fromX: number;
  fromY: number;
  fromZ: number;
  toX: number;
  toY: number;
  toZ: number;
}

export interface AddMapItemPositionInput {
  mapId: string;
  conversationId: string;
  itemName: string;
  itemDescription?: string;
  itemType?: string;
  x: number;
  y: number;
  z?: number;
  linkedLocationId?: string;
  isDiscovered?: boolean;
}

export interface UpdateEntityPositionInput {
  mapId: string;
  characterId?: string;
  userId?: string;
  locationId?: string;
  x?: number;
  y?: number;
  z?: number;
}

export interface TerrainData {
  width: number;
  height: number;
  heightmap: string;   // Base64-encoded Float32Array
  biomeGrid: string;   // Base64-encoded Int32Array
  waterLevel: number;
  seed: string;
  mapScale: string;
  theme: string;
}

export interface RoadSegment {
  roadId: string;
  fromLocationId: string;
  toLocationId: string;
  waypoints: number[][]; // [[x, y, z], ...]
  roadType: string;
}

export interface CulturalRegion {
  culture: string;
  name: string;
  centerX: number;
  centerY: number;
  radius: number;
  worldX: number;
  worldY: number;
}

export interface GenerateMapInput {
  conversationId: string;
  name: string;
  mapScale?: string;
  seed?: string;
  waterLevel?: number;
  locationCount?: number;
  theme?: string;
}

export interface Party {
  partyId: string;
  name: string;
  leaderId: string;
  description?: string;
  maxMembers?: number;
  isPublic?: boolean;
  inviteCode?: string;
  members?: PartyMember[];
  createdAt?: string;
}

// PartyMember only exposes scalar identifiers via GraphQL; resolve the full
// Character or User with separate queries when needed.
export interface PartyMember {
  id: string;
  partyId: string;
  userId: string;
  characterId?: string;
  role?: string;
  joinedAt?: string;
}

export interface PublishedStory {
  storyId: string;
  conversationId: string;
  userId: string;
  title: string;
  synopsis?: string;
  coverImageId?: string;
  genre?: string;
  tags?: string[];
  wordCount?: number;
  rating?: number;
  ratingCount?: number;
  viewCount?: number;
  likeCount?: number;
  isPublic?: boolean;
  isFeatured?: boolean;
  publishedAt?: string;
}

export interface StoryRating {
  id: string;
  storyId: string;
  userId: string;
  rating: number;
  review?: string;
  createdAt?: string;
}

export interface CommunityTemplate {
  submissionId: string;
  templateId: string;
  template?: StoryTemplate;
  userId: string;
  status?: string;
  downloads?: number;
  rating?: number;
  ratingCount?: number;
  submittedAt?: string;
}

// ============================================
// ROLEPLAY INPUT TYPES
// ============================================

export interface CreateRoleplayConversationInput {
  title?: string;
  description?: string;
  characterIds: string[];
  templateId?: string;
  rpMode?: string;
  turnMode?: TurnMode;
  agentPipelineEnabled?: boolean;
  agentModels?: AgentModelConfigInput;
}

export interface AgentModelConfigInput {
  defaultModel?: string;
  highlighterModel?: string;
  museModel?: string;
  narratorModel?: string;
  accountantModel?: string;
  skepticModel?: string;
  casterModel?: string;
}

// globalFlags is transmitted to the backend as a JSON string (the backend
// input type is `String`) because SmallRye GraphQL has no first-class JSON
// scalar for arbitrary objects — stringify before sending.
export interface UpdateWorldStateInput {
  currentScene?: string;
  currentLocation?: string;
  currentLocationId?: string;
  timeOfDay?: string;
  dayNumber?: number;
  weather?: string;
  mood?: string;
  globalFlags?: string;
}

export interface SetCharacterStatInput {
  conversationId: string;
  characterId: string;
  statName: string;
  statValue?: number;
  statMax?: number;
  statType?: string;
  statCategory?: string;
  textValue?: string;
  isVisible?: boolean;
}

// history is transmitted as a JSON string — see UpdateWorldStateInput.
export interface SetRelationshipInput {
  conversationId: string;
  fromCharacterId: string;
  toCharacterId: string;
  relationshipType: string;
  intensity?: number;
  description?: string;
  history?: string;
  isReciprocal?: boolean;
}

// properties is transmitted as a JSON string — see UpdateWorldStateInput.
export interface AddInventoryItemInput {
  conversationId: string;
  characterId?: string;
  userId?: string;
  itemName: string;
  itemDescription?: string;
  quantity?: number;
  category?: string;
  properties?: string;
  isEquipped?: boolean;
}

export interface CreateBookmarkInput {
  conversationId: string;
  messageId?: string;
  label: string;
  description?: string;
  tags?: string[];
}

export interface RollDiceInput {
  conversationId: string;
  characterId?: string;
  expression: string;
  purpose?: string;
  isHidden?: boolean;
}

export interface ChangeSceneInput {
  conversationId: string;
  sceneName: string;
  location?: string;
  description?: string;
  characterIds?: string[];
}

export interface GenerateImageInput {
  conversationId: string;
  characterId?: string;
  prompt?: string;
  negativePrompt?: string;
  width?: number;
  height?: number;
  steps?: number;
  cfg?: number;
  /** ComfyUI workflow trigger, e.g. "character_portrait" or "scene_change" */
  triggerType?: string;
  workflowId?: string;
}

export interface CreateBranchInput {
  parentConversationId: string;
  branchPointMessageId: string;
  bookmarkId?: string;
  label: string;
  description?: string;
}

export interface PublishStoryInput {
  conversationId: string;
  title: string;
  synopsis?: string;
  genre?: string;
  tags?: string[];
  coverImageId?: string;
}

export interface RateStoryInput {
  storyId: string;
  rating: number;
  review?: string;
}

export interface SubmitTemplateInput {
  templateId: string;
}
