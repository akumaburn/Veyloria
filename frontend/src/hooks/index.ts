/**
 * Hooks Index
 *
 * Centralized exports for all custom hooks.
 * Import hooks from '@/hooks' instead of individual files.
 */

// Form state management
export { useFormState } from './useFormState';
export type { UseFormStateReturn } from './useFormState';

// Authentication mutations
export { useAuthMutation, authErrorMappers } from './useAuthMutation';

// Modal state management
export {
  useModalState,
  useModalWithReset,
  useConfirmation,
  useMultipleModals,
} from './useModalState';
export type {
  UseModalStateReturn,
  UseModalWithResetReturn,
  UseConfirmationReturn,
  UseMultipleModalsReturn,
} from './useModalState';

// GraphQL hooks
export {
  // Error handling
  useErrorHandler,

  // Authentication
  useLogin,
  useRegister,
  useLogout,
  useMe,

  // Conversations
  useConversations,
  useConversation,
  useCreateConversation,

  // Messages
  useMessages,
  useSendMessage,
  useSendGroupMessage,
  useEditMessage,

  // Characters
  useCharacters,
  useCreateCharacter,
  useGenerateCharacter,

  // Settings
  useSettings,
  useUpdateSettings,

  // Subscriptions
  useMessageStream,
  useConversationUpdates,
  usePendingStreamingMessage,
  useTypingIndicator,
  useUserPresence,
  useSystemNotifications,

  // Utilities
  useOptimisticUpdate,
  usePagination,
  useDebounce,

  // Chat utilities
  useSendTypingIndicator,
  useStopGeneration,
  useSwitchCharacter,

  // Data management
  useExportData,
  useImportData,
  useDeleteUserData,

  // Multi-user invites
  useMyInvites,
  useConversationParticipants,
  useSearchUsers,
  useInviteUser,
  useRespondToInvite,
  useCancelInvite,
  useUpdateParticipant,
  useRemoveParticipant,
  useLeaveConversation,
  useTransferOwnership,
  useMarkConversationRead,
} from './useGraphQL';

// Types from useGraphQL
export type {
  InviteFilter,
  UserSearchResult,
  ConversationParticipant,
  ConversationInvite,
} from './useGraphQL';

// Saved rosters
export { useCharacterGroups } from './useCharacterGroups';

// Roleplay hooks
export {
  // World state
  useWorldState,
  useUpdateWorldState,
  useInitializeFromTemplate,
  useStoryTemplates,

  // Character sheet
  useCharacterStats,
  useSetCharacterStat,
  useRemoveCharacterStat,
  useCharacterRelationships,
  useSetRelationship,
  useCharacterInventory,
  useAddInventoryItem,
  useRemoveInventoryItem,
  useEquipItem,

  // Dice
  useDiceHistory,
  useRollDice,
  useDiceRoller,

  // Scenes
  useActiveScene,
  useChangeScene,
  useMoveCharacterToScene,

  // Agent config
  useAgentModelConfig,
  useUpdateAgentModelConfig,

  // Knowledge
  useKnowledgeEntries,

  // Bookmarks
  useBookmarks,
  useCreateBookmark,
  useDeleteBookmark,

  // Images
  useGeneratedImages,
  useGenerateImage,

  // Branches
  useStoryBranches,
  useCreateBranch,

  // Parties
  useParties,
  useCreateParty,
  useJoinParty,
  useJoinPartyByCode,
  useLeaveParty,
  useUpdatePartyMemberRole,

  // Roleplay conversations
  useCreateRoleplayConversation,

  // User inventory
  useUserInventory,

  // Agent runs
  useAgentRuns,

  // Character proposals (Casting Director)
  useCharacterProposals,
  useApproveCharacterProposal,
  useRejectCharacterProposal,
  useCharacterProposalSubscription,

  // Image generation
  useCancelImageGeneration,
  useImageGenerationProgress,
  useImageGenerated,

  // Community
  useUnpublishStory,
  useSubmitTemplate,

  // Roleplay subscriptions
  useWorldStateChanged,
  useDiceRolled,
  useSceneChanged,
  useAchievementUnlocked,
  useStatChanged,
  useRelationshipChanged,
  useInventoryChanged,

  // Battle
  useActiveBattle,
  useBattle,
  useInitiateBattle,
  useVoteAction,
  useResolveBattleRound,
  useEndBattle,
  useBattleRoundSubscription,
  useBattleStartedSubscription,
  useBattleEndedSubscription,

  // Item usage
  useUseInventoryItem,
  useTransferInventoryItem,
} from './useRoleplay';
