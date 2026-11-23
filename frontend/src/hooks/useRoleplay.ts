import { BATTLE_VOTE_SUB } from "@/graphql/roleplay-operations";
/**
 * Roleplay Hooks
 *
 * React hooks for roleplay features using Apollo Client and Zustand stores.
 * Provides convenient APIs for world state, character sheets, dice rolling,
 * scenes, agent config, and real-time subscriptions.
 */

import { useQuery, useMutation } from '@apollo/client';
import { useCallback, useEffect } from 'react';
import { print } from 'graphql';
import { useWebSocketSubscription } from '@/lib/websocket-client';
import {
  WORLD_STATE_QUERY,
  ACTIVE_BATTLE_QUERY,
  BATTLE_QUERY,
  INITIATE_BATTLE,
  VOTE_ACTION,
  RESOLVE_BATTLE_ROUND,
  END_BATTLE,
  BATTLE_ROUND_SUB,
  BATTLE_STARTED_SUB,
  BATTLE_ENDED_SUB,
  USE_INVENTORY_ITEM,
  TRANSFER_INVENTORY_ITEM,
  STORY_TEMPLATES_QUERY,
  CHARACTER_STATS_QUERY,
  CHARACTER_RELATIONSHIPS_QUERY,
  INVENTORY_ACCESS_QUERY,
  CHARACTER_INVENTORY_QUERY,
  USER_INVENTORY_QUERY,
  ACTIVE_SCENE_QUERY,
  KNOWLEDGE_ENTRIES_QUERY,
  AGENT_MODEL_CONFIG_QUERY,
  DICE_HISTORY_QUERY,
  BOOKMARKS_QUERY,
  GENERATED_IMAGES_QUERY,
  STORY_BRANCHES_QUERY,
  PARTIES_QUERY,
  UPDATE_WORLD_STATE,
  INITIALIZE_FROM_TEMPLATE,
  SET_CHARACTER_STAT,
  REMOVE_CHARACTER_STAT,
  SET_RELATIONSHIP,
  ADD_INVENTORY_ITEM,
  REMOVE_INVENTORY_ITEM,
  EQUIP_ITEM,
  ROLL_DICE,
  CHANGE_SCENE,
  MOVE_CHARACTER_TO_SCENE,
  UPDATE_AGENT_MODEL_CONFIG,
  CREATE_BOOKMARK,
  DELETE_BOOKMARK,
  GENERATE_IMAGE,
  CREATE_BRANCH,
  CREATE_PARTY,
  JOIN_PARTY,
  JOIN_PARTY_BY_CODE,
  LEAVE_PARTY,
  CREATE_ROLEPLAY_CONVERSATION,
  CHARACTER_PROPOSALS_QUERY,
  APPROVE_CHARACTER_PROPOSAL,
  REJECT_CHARACTER_PROPOSAL,
  UPDATE_PARTY_MEMBER_ROLE,
  CANCEL_IMAGE_GENERATION,
  UNPUBLISH_STORY,
  SUBMIT_TEMPLATE,
  WORLD_STATE_CHANGED_SUB,
  DICE_ROLLED_SUB,
  SCENE_CHANGED_SUB,
  STAT_CHANGED_SUB,
  RELATIONSHIP_CHANGED_SUB,
  INVENTORY_CHANGED_SUB,
  ACHIEVEMENT_UNLOCKED_SUB,
  CHARACTER_PROPOSAL_CREATED_SUB,
  IMAGE_GENERATION_PROGRESS_SUB,
  IMAGE_GENERATED_SUB,
  AGENT_RUNS_QUERY,
} from '@/graphql/roleplay-operations';

// ============================================
// WORLD STATE HOOKS
// ============================================

export const useWorldState = (conversationId: string) => {
  const { data, loading, error, refetch } = useQuery(WORLD_STATE_QUERY, {
    variables: { conversationId },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    worldState: data?.worldState,
    loading,
    error,
    refetch,
  };
};

export const useUpdateWorldState = () => {
  const [updateWorldState, { loading, error }] = useMutation(UPDATE_WORLD_STATE, {
    refetchQueries: ['WorldState'],
  });

  return {
    updateWorldState: (conversationId: string, input: any) =>
      updateWorldState({ variables: { conversationId, input } }),
    loading,
    error,
  };
};

export const useInitializeFromTemplate = () => {
  const [initializeFromTemplate, { loading, error }] = useMutation(INITIALIZE_FROM_TEMPLATE);

  return {
    initializeFromTemplate: (conversationId: string, templateId: string) =>
      initializeFromTemplate({ variables: { conversationId, templateId } }),
    loading,
    error,
  };
};

export const useStoryTemplates = (genre?: string) => {
  const { data, loading, error, refetch } = useQuery(STORY_TEMPLATES_QUERY, {
    variables: { genre },
    fetchPolicy: 'cache-and-network',
  });

  return {
    templates: data?.storyTemplates || [],
    loading,
    error,
    refetch,
  };
};

// ============================================
// CHARACTER SHEET HOOKS
// ============================================

export const useCharacterStats = (conversationId: string, characterId: string) => {
  const { data, loading, error, refetch } = useQuery(CHARACTER_STATS_QUERY, {
    variables: { conversationId, characterId },
    skip: !conversationId || !characterId,
    fetchPolicy: 'cache-and-network',
  });
  const { inventoryEvent } = useInventoryChanged(conversationId);
  useEffect(() => {
    if (inventoryEvent && conversationId && characterId) void refetch().catch(() => {});
  }, [inventoryEvent, conversationId, characterId, refetch]);

  return {
    stats: data?.characterStats || [],
    loading,
    error,
    refetch,
  };
};

export const useSetCharacterStat = () => {
  const [setCharacterStat, { loading, error }] = useMutation(SET_CHARACTER_STAT, {
    refetchQueries: ['CharacterStats'],
  });

  return {
    setCharacterStat: (input: any) =>
      setCharacterStat({ variables: { input } }),
    loading,
    error,
  };
};

export const useRemoveCharacterStat = () => {
  const [removeCharacterStat, { loading, error }] = useMutation(REMOVE_CHARACTER_STAT, {
    refetchQueries: ['CharacterStats'],
  });

  return {
    removeCharacterStat: (conversationId: string, characterId: string, statName: string) =>
      removeCharacterStat({ variables: { conversationId, characterId, statName } }),
    loading,
    error,
  };
};

export const useCharacterRelationships = (conversationId: string, characterId?: string) => {
  const { data, loading, error, refetch } = useQuery(CHARACTER_RELATIONSHIPS_QUERY, {
    variables: { conversationId, characterId },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    relationships: data?.characterRelationships || [],
    loading,
    error,
    refetch,
  };
};

export const useSetRelationship = () => {
  const [setRelationship, { loading, error }] = useMutation(SET_RELATIONSHIP, {
    refetchQueries: ['CharacterRelationships'],
  });

  return {
    setRelationship: (input: any) =>
      setRelationship({ variables: { input } }),
    loading,
    error,
  };
};

export const useCharacterInventory = (conversationId: string, characterId: string) => {
  const { data, loading, error, refetch } = useQuery(CHARACTER_INVENTORY_QUERY, {
    variables: { conversationId, characterId },
    skip: !conversationId || !characterId,
    fetchPolicy: 'cache-and-network',
  });
  const { inventoryEvent } = useInventoryChanged(conversationId);
  useEffect(() => {
    if (inventoryEvent && conversationId && characterId) void refetch().catch(() => {});
  }, [inventoryEvent, conversationId, characterId, refetch]);

  return {
    inventory: data?.characterInventory || [],
    loading,
    error,
    refetch,
  };
};

export const useUserInventory = (conversationId: string, userId: string) => {
  const { data, loading, error, refetch } = useQuery(USER_INVENTORY_QUERY, {
    variables: { conversationId, userId },
    skip: !conversationId || !userId,
    fetchPolicy: 'cache-and-network',
  });
  const { inventoryEvent } = useInventoryChanged(conversationId);
  useEffect(() => {
    if (inventoryEvent && conversationId && userId) void refetch().catch(() => {});
  }, [inventoryEvent, conversationId, userId, refetch]);

  return {
    inventory: data?.userInventory || [],
    loading,
    error,
    refetch,
  };
};

export const useAddInventoryItem = () => {
  const [addInventoryItem, { loading, error }] = useMutation(ADD_INVENTORY_ITEM, {
    refetchQueries: ['CharacterInventory', 'UserInventory'],
    awaitRefetchQueries: true,
  });

  return {
    addInventoryItem: (input: any) =>
      addInventoryItem({ variables: { input } }),
    loading,
    error,
  };
};

export const useRemoveInventoryItem = () => {
  const [removeInventoryItem, { loading, error }] = useMutation(REMOVE_INVENTORY_ITEM, {
    refetchQueries: ['CharacterInventory', 'UserInventory'],
    awaitRefetchQueries: true,
  });

  return {
    removeInventoryItem: (inventoryId: string) =>
      removeInventoryItem({ variables: { inventoryId } }),
    loading,
    error,
  };
};

export const useEquipItem = () => {
  const [equipItem, { loading, error }] = useMutation(EQUIP_ITEM, {
    refetchQueries: ['CharacterInventory', 'UserInventory'],
    awaitRefetchQueries: true,
  });

  return {
    equipItem: (inventoryId: string, equipped: boolean) =>
      equipItem({ variables: { inventoryId, equipped } }),
    loading,
    error,
  };
};

// ============================================
// DICE ROLLER HOOKS
// ============================================

export const useDiceHistory = (conversationId: string, limit?: number) => {
  const { data, loading, error, refetch } = useQuery(DICE_HISTORY_QUERY, {
    variables: { conversationId, limit },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    rolls: data?.diceHistory || [],
    loading,
    error,
    refetch,
  };
};

export const useRollDice = () => {
  const [rollDice, { loading, error, data }] = useMutation(ROLL_DICE, {
    refetchQueries: ['DiceHistory'],
  });

  return {
    rollDice: (input: any) => rollDice({ variables: { input } }),
    lastRoll: data?.rollDice,
    loading,
    error,
  };
};

/**
 * Combined dice roller hook with history and rolling capability.
 */
export const useDiceRoller = (conversationId: string) => {
  const { rolls, loading: historyLoading, refetch } = useDiceHistory(conversationId);
  const { rollDice, lastRoll, loading: rollLoading } = useRollDice();

  const roll = useCallback(
    (expression: string, characterId?: string, purpose?: string, isHidden?: boolean) =>
      rollDice({
        conversationId,
        characterId,
        expression,
        purpose,
        isHidden,
      }),
    [conversationId, rollDice]
  );

  return {
    rolls,
    lastRoll,
    roll,
    loading: historyLoading || rollLoading,
    refetch,
  };
};

// ============================================
// SCENE HOOKS
// ============================================

export const useActiveScene = (conversationId: string) => {
  const { data, loading, error, refetch } = useQuery(ACTIVE_SCENE_QUERY, {
    variables: { conversationId },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    scene: data?.activeScene,
    loading,
    error,
    refetch,
  };
};

export const useChangeScene = () => {
  const [changeScene, { loading, error }] = useMutation(CHANGE_SCENE, {
    refetchQueries: ['ActiveScene', 'WorldState'],
  });

  return {
    changeScene: (input: any) =>
      changeScene({ variables: { input } }),
    loading,
    error,
  };
};

export const useMoveCharacterToScene = () => {
  const [moveCharacterToScene, { loading, error }] = useMutation(MOVE_CHARACTER_TO_SCENE, {
    refetchQueries: ['ActiveScene'],
  });

  return {
    moveCharacterToScene: (conversationId: string, characterId: string, sceneId: string) =>
      moveCharacterToScene({ variables: { conversationId, characterId, sceneId } }),
    loading,
    error,
  };
};

// ============================================
// AGENT MODEL CONFIG HOOKS
// ============================================

export const useAgentModelConfig = (conversationId: string) => {
  const { data, loading, error, refetch } = useQuery(AGENT_MODEL_CONFIG_QUERY, {
    variables: { conversationId },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    config: data?.agentModelConfig,
    loading,
    error,
    refetch,
  };
};

export const useUpdateAgentModelConfig = () => {
  const [updateAgentModelConfig, { loading, error }] = useMutation(UPDATE_AGENT_MODEL_CONFIG, {
    refetchQueries: ['AgentModelConfig'],
  });

  return {
    updateAgentModelConfig: (conversationId: string, input: any) =>
      updateAgentModelConfig({ variables: { conversationId, input } }),
    loading,
    error,
  };
};

export const useAgentRuns = (conversationId: string, limit?: number) => {
  const { data, loading, error, refetch } = useQuery(AGENT_RUNS_QUERY, {
    variables: { conversationId, limit },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    runs: data?.agentRuns || [],
    loading,
    error,
    refetch,
  };
};

// ============================================
// KNOWLEDGE HOOKS
// ============================================

export const useKnowledgeEntries = (conversationId: string, characterId?: string) => {
  const { data, loading, error, refetch } = useQuery(KNOWLEDGE_ENTRIES_QUERY, {
    variables: { conversationId, characterId },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    entries: data?.knowledgeEntries || [],
    loading,
    error,
    refetch,
  };
};

// ============================================
// BOOKMARK HOOKS
// ============================================

export const useBookmarks = (conversationId: string) => {
  const { data, loading, error, refetch } = useQuery(BOOKMARKS_QUERY, {
    variables: { conversationId },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    bookmarks: data?.bookmarks || [],
    loading,
    error,
    refetch,
  };
};

export const useCreateBookmark = () => {
  const [createBookmark, { loading, error }] = useMutation(CREATE_BOOKMARK, {
    refetchQueries: ['Bookmarks'],
  });

  return {
    createBookmark: (input: any) =>
      createBookmark({ variables: { input } }),
    loading,
    error,
  };
};

export const useDeleteBookmark = () => {
  const [deleteBookmark, { loading, error }] = useMutation(DELETE_BOOKMARK, {
    refetchQueries: ['Bookmarks'],
  });

  return {
    deleteBookmark: (bookmarkId: string) =>
      deleteBookmark({ variables: { bookmarkId } }),
    loading,
    error,
  };
};

// ============================================
// IMAGE GENERATION HOOKS
// ============================================

export const useGeneratedImages = (conversationId: string, limit?: number) => {
  const { data, loading, error, refetch } = useQuery(GENERATED_IMAGES_QUERY, {
    variables: { conversationId, limit },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    images: data?.generatedImages || [],
    loading,
    error,
    refetch,
  };
};

export const useGenerateImage = () => {
  const [generateImage, { loading, error }] = useMutation(GENERATE_IMAGE, {
    refetchQueries: ['GeneratedImages'],
  });

  return {
    generateImage: (input: any) =>
      generateImage({ variables: { input } }),
    loading,
    error,
  };
};

// ============================================
// BRANCH HOOKS
// ============================================

export const useStoryBranches = (conversationId: string) => {
  const { data, loading, error, refetch } = useQuery(STORY_BRANCHES_QUERY, {
    variables: { conversationId },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    branches: data?.storyBranches || [],
    loading,
    error,
    refetch,
  };
};

export const useCreateBranch = () => {
  const [createBranch, { loading, error }] = useMutation(CREATE_BRANCH, {
    refetchQueries: ['StoryBranches'],
  });

  return {
    createBranch: (input: any) =>
      createBranch({ variables: { input } }),
    loading,
    error,
  };
};

// ============================================
// PARTY HOOKS
// ============================================

export const useParties = () => {
  const { data, loading, error, refetch } = useQuery(PARTIES_QUERY, {
    fetchPolicy: 'cache-and-network',
  });

  return {
    parties: data?.parties || [],
    loading,
    error,
    refetch,
  };
};

export const useCreateParty = () => {
  const [createParty, { loading, error }] = useMutation(CREATE_PARTY, {
    refetchQueries: ['Parties'],
  });

  return {
    createParty: (input: any) =>
      createParty({ variables: { input } }),
    loading,
    error,
  };
};

export const useJoinParty = () => {
  const [joinParty, { loading, error }] = useMutation(JOIN_PARTY, {
    refetchQueries: ['Parties'],
  });

  return {
    joinParty: (partyId: string) =>
      joinParty({ variables: { partyId } }),
    loading,
    error,
  };
};

export const useJoinPartyByCode = () => {
  const [joinPartyByCode, { loading, error }] = useMutation(JOIN_PARTY_BY_CODE, {
    refetchQueries: ['Parties'],
  });

  return {
    joinPartyByCode: (inviteCode: string) =>
      joinPartyByCode({ variables: { inviteCode } }),
    loading,
    error,
  };
};

export const useLeaveParty = () => {
  const [leaveParty, { loading, error }] = useMutation(LEAVE_PARTY, {
    refetchQueries: ['Parties'],
  });

  return {
    leaveParty: (partyId: string) =>
      leaveParty({ variables: { partyId } }),
    loading,
    error,
  };
};

export const useUpdatePartyMemberRole = () => {
  const [updatePartyMemberRole, { loading, error }] = useMutation(UPDATE_PARTY_MEMBER_ROLE, {
    refetchQueries: ['Parties'],
  });

  return {
    updatePartyMemberRole: (partyId: string, userId: string, role: string) =>
      updatePartyMemberRole({ variables: { partyId, userId, role } }),
    loading,
    error,
  };
};

// ============================================
// ROLEPLAY CONVERSATION HOOKS
// ============================================

export const useCreateRoleplayConversation = () => {
  const [createRoleplayConversation, { loading, error }] = useMutation(CREATE_ROLEPLAY_CONVERSATION, {
    refetchQueries: ['Conversations'],
  });

  return {
    createRoleplayConversation: (input: any) =>
      createRoleplayConversation({ variables: { input } }),
    loading,
    error,
  };
};

// ============================================
// ROLEPLAY SUBSCRIPTIONS (using standalone WebSocket client)
// Convert gql DocumentNodes from centralized operations to raw strings
// ============================================

const WORLD_STATE_CHANGED_QUERY = print(WORLD_STATE_CHANGED_SUB);
const DICE_ROLLED_QUERY = print(DICE_ROLLED_SUB);
const SCENE_CHANGED_QUERY = print(SCENE_CHANGED_SUB);
const ACHIEVEMENT_UNLOCKED_QUERY = print(ACHIEVEMENT_UNLOCKED_SUB);

export const useWorldStateChanged = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    WORLD_STATE_CHANGED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    worldState: data?.worldStateChanged,
    loading,
    error,
  };
};

export const useDiceRolled = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    DICE_ROLLED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    roll: data?.diceRolled,
    loading,
    error,
  };
};

export const useSceneChanged = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    SCENE_CHANGED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    scene: data?.sceneChanged,
    loading,
    error,
  };
};

export const useAchievementUnlocked = (userId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    ACHIEVEMENT_UNLOCKED_QUERY,
    { userId },
    !userId
  );

  return {
    achievement: data?.achievementUnlocked,
    loading,
    error,
  };
};

// ============================================
// CHARACTER SHEET CHANGE SUBSCRIPTIONS
// ============================================

const STAT_CHANGED_QUERY = print(STAT_CHANGED_SUB);
const RELATIONSHIP_CHANGED_QUERY = print(RELATIONSHIP_CHANGED_SUB);
const INVENTORY_CHANGED_QUERY = print(INVENTORY_CHANGED_SUB);

export const useStatChanged = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    STAT_CHANGED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    stat: data?.statChanged,
    loading,
    error,
  };
};

export const useRelationshipChanged = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    RELATIONSHIP_CHANGED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    relationship: data?.relationshipChanged,
    loading,
    error,
  };
};

export const useInventoryChanged = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    INVENTORY_CHANGED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    inventoryEvent: data?.inventoryChanged,
    loading,
    error,
  };
};

// ============================================
// CASTING DIRECTOR HOOKS
// ============================================

export const useCharacterProposals = (conversationId: string) => {
  const { data, loading, error, refetch } = useQuery(CHARACTER_PROPOSALS_QUERY, {
    variables: { conversationId },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    proposals: data?.characterProposals ?? [],
    loading,
    error,
    refetch,
  };
};

export const useApproveCharacterProposal = () => {
  const [mutate, { loading, error }] = useMutation(APPROVE_CHARACTER_PROPOSAL);

  const approveProposal = useCallback(async (proposalId: string) => {
    const result = await mutate({
      variables: { proposalId },
      refetchQueries: ['CharacterProposals'],
    });
    return result.data?.approveCharacterProposal;
  }, [mutate]);

  return { approveProposal, loading, error };
};

export const useRejectCharacterProposal = () => {
  const [mutate, { loading, error }] = useMutation(REJECT_CHARACTER_PROPOSAL);

  const rejectProposal = useCallback(async (proposalId: string) => {
    const result = await mutate({
      variables: { proposalId },
      refetchQueries: ['CharacterProposals'],
    });
    return result.data?.rejectCharacterProposal;
  }, [mutate]);

  return { rejectProposal, loading, error };
};

const CHARACTER_PROPOSAL_CREATED_QUERY = print(CHARACTER_PROPOSAL_CREATED_SUB);

export const useCharacterProposalSubscription = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    CHARACTER_PROPOSAL_CREATED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    proposal: data?.characterProposalCreated,
    loading,
    error,
  };
};

// ============================================
// IMAGE GENERATION MANAGEMENT HOOKS
// ============================================

export const useCancelImageGeneration = () => {
  const [cancelImageGeneration, { loading, error }] = useMutation(CANCEL_IMAGE_GENERATION);

  return {
    cancelImageGeneration: (promptId: string) =>
      cancelImageGeneration({ variables: { promptId } }),
    loading,
    error,
  };
};

const IMAGE_GENERATION_PROGRESS_QUERY = print(IMAGE_GENERATION_PROGRESS_SUB);

export const useImageGenerationProgress = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    IMAGE_GENERATION_PROGRESS_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    progress: data?.imageGenerationProgress,
    loading,
    error,
  };
};

const IMAGE_GENERATED_QUERY = print(IMAGE_GENERATED_SUB);

export const useImageGenerated = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    IMAGE_GENERATED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    image: data?.imageGenerated,
    loading,
    error,
  };
};

// ============================================
// BATTLE HOOKS
// ============================================

export const useActiveBattle = (conversationId: string) => {
  const { data, loading, error, refetch } = useQuery(ACTIVE_BATTLE_QUERY, {
    variables: { conversationId },
    skip: !conversationId,
    fetchPolicy: 'cache-and-network',
  });

  const { battleStarted } = useBattleStartedSubscription(conversationId);
  const { battleEnded } = useBattleEndedSubscription(conversationId);
  const { round } = useBattleRoundSubscription(conversationId);
  const { data: vote } = useWebSocketSubscription(print(BATTLE_VOTE_SUB), { conversationId }, !conversationId);
  useEffect(() => {
    if (conversationId && (battleStarted || battleEnded || round || vote)) void refetch();
  }, [conversationId, battleStarted, battleEnded, round, vote, refetch]);
  return {
    battle: data?.activeBattle,
    loading,
    error,
    refetch,
  };
};

export const useBattle = (battleId: string) => {
  const { data, loading, error, refetch } = useQuery(BATTLE_QUERY, {
    variables: { battleId },
    skip: !battleId,
    fetchPolicy: 'cache-and-network',
  });

  return {
    battle: data?.battle,
    loading,
    error,
    refetch,
  };
};

export const useInitiateBattle = () => {
  const [initiateBattle, { loading, error }] = useMutation(INITIATE_BATTLE, {
    refetchQueries: ['ActiveBattle'],
  });

  return {
    initiateBattle: (input: any) =>
      initiateBattle({ variables: { input } }),
    loading,
    error,
  };
};

export const useVoteAction = () => {
  const [voteAction, { loading, error }] = useMutation(VOTE_ACTION, {
    refetchQueries: ['ActiveBattle'],
  });

  return {
    voteAction: (input: any) =>
      voteAction({ variables: { input } }),
    loading,
    error,
  };
};

export const useResolveBattleRound = () => {
  const [resolveBattleRound, { loading, error }] = useMutation(RESOLVE_BATTLE_ROUND, {
    refetchQueries: ['ActiveBattle'],
  });

  return {
    resolveBattleRound: (battleId: string, roundNumber?: number) =>
      resolveBattleRound({ variables: { battleId, roundNumber } }),
    loading,
    error,
  };
};

export const useEndBattle = () => {
  const [endBattle, { loading, error }] = useMutation(END_BATTLE, {
    refetchQueries: ['ActiveBattle'],
  });

  return {
    endBattle: (battleId: string, status: string) =>
      endBattle({ variables: { battleId, status } }),
    loading,
    error,
  };
};

const BATTLE_ROUND_QUERY = print(BATTLE_ROUND_SUB);
const BATTLE_STARTED_QUERY = print(BATTLE_STARTED_SUB);
const BATTLE_ENDED_QUERY = print(BATTLE_ENDED_SUB);

export const useBattleRoundSubscription = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    BATTLE_ROUND_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    round: data?.battleRound,
    loading,
    error,
  };
};

export const useBattleStartedSubscription = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    BATTLE_STARTED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    battleStarted: data?.battleStarted,
    loading,
    error,
  };
};

export const useBattleEndedSubscription = (conversationId: string) => {
  const { data, loading, error } = useWebSocketSubscription(
    BATTLE_ENDED_QUERY,
    { conversationId },
    !conversationId
  );

  return {
    battleEnded: data?.battleEnded,
    loading,
    error,
  };
};

// ============================================
// ITEM USAGE HOOKS
// ============================================

export const useUseInventoryItem = () => {
  const [useInventoryItem, { loading, error }] = useMutation(USE_INVENTORY_ITEM, {
    refetchQueries: ['CharacterInventory', 'UserInventory', 'CharacterStats'],
    awaitRefetchQueries: true,
  });

  return {
    useInventoryItem: (inventoryId: string, targetCharacterId?: string) =>
      useInventoryItem({ variables: { inventoryId, targetCharacterId } }),
    loading,
    error,
  };
};

export const useTransferInventoryItem = () => {
  const [transferInventoryItem, { loading, error }] = useMutation(TRANSFER_INVENTORY_ITEM, {
    refetchQueries: ['CharacterInventory', 'UserInventory', 'CharacterStats'],
    awaitRefetchQueries: true,
  });

  return {
    transferInventoryItem: (inventoryId: string, targetCharacterId?: string, targetUserId?: string, quantity?: number) =>
      transferInventoryItem({ variables: { inventoryId, targetCharacterId, targetUserId, quantity } }),
    loading,
    error,
  };
};

// ============================================
// COMMUNITY MANAGEMENT HOOKS
// ============================================

export const useUnpublishStory = () => {
  const [unpublishStory, { loading, error }] = useMutation(UNPUBLISH_STORY, {
    refetchQueries: ['PublishedStories'],
  });

  return {
    unpublishStory: (storyId: string) =>
      unpublishStory({ variables: { storyId } }),
    loading,
    error,
  };
};

export const useSubmitTemplate = () => {
  const [submitTemplate, { loading, error }] = useMutation(SUBMIT_TEMPLATE, {
    refetchQueries: ['CommunityTemplates'],
  });

  return {
    submitTemplate: (input: { templateId: string }) =>
      submitTemplate({ variables: { input } }),
    loading,
    error,
  };
};

export interface InventoryRecipient { id: string; name: string; kind: 'character' | 'user'; canReceiveEffects: boolean }
export const useInventoryAccess = (conversationId: string, characterId?: string, userId?: string) => {
  const { data, loading, error, refetch } = useQuery<{
    inventoryAccess: { canModify: boolean; canManage: boolean; recipients: InventoryRecipient[] }
  }>(INVENTORY_ACCESS_QUERY, {
    variables: { conversationId, characterId, userId },
    skip: !conversationId || (!characterId && !userId),
    fetchPolicy: 'cache-and-network',
  });
  return { ...data?.inventoryAccess, loading, error, refetch };
};
