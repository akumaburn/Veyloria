import { gql } from '@apollo/client';

// ============================================
// ROLEPLAY FRAGMENTS
// ============================================

// Note: globalFlags, locations, activeEffects, narratorNotes are stored server-side
// as JsonObject but marked @Ignore on the WorldState model, so they are intentionally
// not exposed through GraphQL. Keep them out of this fragment to avoid field errors.
export const WORLD_STATE_FRAGMENT = gql`
  fragment WorldStateFields on WorldState {
    stateId
    conversationId
    templateId
    currentScene
    currentLocation
    currentLocationId
    currentArea
    currentAreaId
    timeOfDay
    dayNumber
    weather
    mood
  }
`;

// Note: defaultWorldState, suggestedCharacterArchetypes, rules, and moodPresets are
// JsonObject fields marked @Ignore on the StoryTemplate model; they are not exposed
// through GraphQL. Use initializeFromTemplate to materialize template data.
export const STORY_TEMPLATE_FRAGMENT = gql`
  fragment StoryTemplateFields on StoryTemplate {
    templateId
    name
    genre
    description
    isBuiltIn
    isPublic
    ownerId
  }
`;

export const CHARACTER_STAT_FRAGMENT = gql`
  fragment CharacterStatFields on CharacterStat {
    statId
    conversationId
    characterId
    statName
    statValue
    statMax
    statType
    statCategory
    textValue
    isVisible
  }
`;

// Note: history is a JsonObject marked @Ignore on the backend model;
// it is not exposed through GraphQL.
export const CHARACTER_RELATIONSHIP_FRAGMENT = gql`
  fragment CharacterRelationshipFields on CharacterRelationship {
    relationshipId
    conversationId
    fromCharacterId
    toCharacterId
    relationshipType
    intensity
    description
    isReciprocal
  }
`;

// Item metadata is serialized JSON, shared with the inventory rule engine.
export const CHARACTER_INVENTORY_FRAGMENT = gql`
  fragment CharacterInventoryFields on CharacterInventoryItem {
    inventoryId
    conversationId
    characterId
    userId
    itemName
    itemDescription
    quantity
    category
    properties
    isEquipped
  }
`;

// Note: worldStateSnapshot is a JsonObject marked @Ignore on the backend model;
// it is not exposed through GraphQL.
export const STORY_BOOKMARK_FRAGMENT = gql`
  fragment StoryBookmarkFields on StoryBookmark {
    bookmarkId
    conversationId
    messageId
    label
    description
    tags
    createdAt
  }
`;

export const DICE_ROLL_FRAGMENT = gql`
  fragment DiceRollFields on DiceRollResult {
    rollId
    conversationId
    characterId
    expression
    results
    total
    modifier
    purpose
    isHidden
    createdAt
  }
`;

export const SCENE_FRAGMENT = gql`
  fragment SceneFields on Scene {
    sceneId
    conversationId
    name
    location
    description
    isActive
    characters {
      id
      name
      avatar
    }
    parentSceneId
    createdAt
    endedAt
  }
`;

// Note: visibility is an Object field marked @Ignore on the backend model
// (it holds either the literal "ALL" or a JsonArray of character IDs, which
// is not a stable GraphQL shape); filter/authorize on the server instead.
export const KNOWLEDGE_ENTRY_FRAGMENT = gql`
  fragment KnowledgeEntryFields on KnowledgeEntry {
    entryId
    conversationId
    summary
    importance
    dayNumber
    tags
    isActive
  }
`;

export const AGENT_MODEL_CONFIG_FRAGMENT = gql`
  fragment AgentModelConfigFields on AgentModelConfig {
    conversationId
    defaultModel
    highlighterModel
    museModel
    narratorModel
    accountantModel
    skepticModel
    casterModel
  }
`;

export const GENERATED_IMAGE_FRAGMENT = gql`
  fragment GeneratedImageFields on GeneratedImage {
    imageId
    conversationId
    messageId
    characterId
    prompt
    negativePrompt
    workflowId
    status
    filePath
    thumbnailPath
    width
    height
    seed
    sceneContext
    createdAt
  }
`;

export const ACHIEVEMENT_FRAGMENT = gql`
  fragment AchievementFields on Achievement {
    achievementId
    name
    description
    category
    icon
    tier
    xpReward
    isHidden
  }
`;

export const USER_ACHIEVEMENT_FRAGMENT = gql`
  fragment UserAchievementFields on UserAchievement {
    id
    userId
    achievementId
    completedAt
    progress
    isComplete
    conversationId
    achievement {
      ...AchievementFields
    }
  }
  ${ACHIEVEMENT_FRAGMENT}
`;

export const USER_PROGRESSION_FRAGMENT = gql`
  fragment UserProgressionFields on UserProgression {
    userId
    totalXP
    level
    title
    storiesCompleted
    totalMessages
    totalDiceRolls
    totalPlaytimeMin
  }
`;

// Note: worldStateSnapshot is a JsonObject marked @Ignore on the backend model;
// it is not exposed through GraphQL.
export const STORY_BRANCH_FRAGMENT = gql`
  fragment StoryBranchFields on StoryBranch {
    branchId
    parentConversationId
    branchConversationId
    branchPointMessageId
    label
    description
    createdAt
  }
`;

// Note: PartyMember only exposes scalar identifiers; resolve the Character via
// a separate `character(id: characterId)` query if you need the full profile.
export const PARTY_FRAGMENT = gql`
  fragment PartyFields on Party {
    partyId
    name
    description
    leaderId
    maxMembers
    inviteCode
    isPublic
    createdAt
    members {
      id
      partyId
      userId
      characterId
      role
      joinedAt
    }
  }
`;

export const PUBLISHED_STORY_FRAGMENT = gql`
  fragment PublishedStoryFields on PublishedStory {
    storyId
    conversationId
    userId
    title
    synopsis
    coverImageId
    genre
    tags
    wordCount
    rating
    ratingCount
    viewCount
    likeCount
    isPublic
    isFeatured
    publishedAt
  }
`;

// ============================================
// ROLEPLAY QUERIES
// ============================================

export const WORLD_STATE_QUERY = gql`
  query WorldState($conversationId: String!) {
    worldState(conversationId: $conversationId) {
      ...WorldStateFields
    }
  }
  ${WORLD_STATE_FRAGMENT}
`;

export const STORY_TEMPLATES_QUERY = gql`
  query StoryTemplates($genre: String) {
    storyTemplates(genre: $genre) {
      ...StoryTemplateFields
    }
  }
  ${STORY_TEMPLATE_FRAGMENT}
`;

export const CHARACTER_STATS_QUERY = gql`
  query CharacterStats($conversationId: String!, $characterId: String!) {
    characterStats(conversationId: $conversationId, characterId: $characterId) {
      ...CharacterStatFields
    }
  }
  ${CHARACTER_STAT_FRAGMENT}
`;

export const CHARACTER_RELATIONSHIPS_QUERY = gql`
  query CharacterRelationships($conversationId: String!, $characterId: String) {
    characterRelationships(conversationId: $conversationId, characterId: $characterId) {
      ...CharacterRelationshipFields
    }
  }
  ${CHARACTER_RELATIONSHIP_FRAGMENT}
`;

export const CHARACTER_INVENTORY_QUERY = gql`
  query CharacterInventory($conversationId: String!, $characterId: String!) {
    characterInventory(conversationId: $conversationId, characterId: $characterId) {
      ...CharacterInventoryFields
    }
  }
  ${CHARACTER_INVENTORY_FRAGMENT}
`;

export const USER_INVENTORY_QUERY = gql`
  query UserInventory($conversationId: String!, $userId: String!) {
    userInventory(conversationId: $conversationId, userId: $userId) {
      ...CharacterInventoryFields
    }
  }
  ${CHARACTER_INVENTORY_FRAGMENT}
`;

export const BOOKMARKS_QUERY = gql`
  query Bookmarks($conversationId: String!) {
    bookmarks(conversationId: $conversationId) {
      ...StoryBookmarkFields
    }
  }
  ${STORY_BOOKMARK_FRAGMENT}
`;

export const DICE_HISTORY_QUERY = gql`
  query DiceHistory($conversationId: String!, $limit: Int) {
    diceHistory(conversationId: $conversationId, limit: $limit) {
      ...DiceRollFields
    }
  }
  ${DICE_ROLL_FRAGMENT}
`;

export const ACTIVE_SCENE_QUERY = gql`
  query ActiveScene($conversationId: String!) {
    activeScene(conversationId: $conversationId) {
      ...SceneFields
    }
  }
  ${SCENE_FRAGMENT}
`;

export const KNOWLEDGE_ENTRIES_QUERY = gql`
  query KnowledgeEntries($conversationId: String!, $characterId: String) {
    knowledgeEntries(conversationId: $conversationId, characterId: $characterId) {
      ...KnowledgeEntryFields
    }
  }
  ${KNOWLEDGE_ENTRY_FRAGMENT}
`;

export const AGENT_MODEL_CONFIG_QUERY = gql`
  query AgentModelConfig($conversationId: String!) {
    agentModelConfig(conversationId: $conversationId) {
      ...AgentModelConfigFields
    }
  }
  ${AGENT_MODEL_CONFIG_FRAGMENT}
`;

// Note: AgentRun.output is a JsonObject marked @Ignore on the backend model;
// there is no "summary" field. Use errorMessage / status / metrics to surface info.
export const AGENT_RUNS_QUERY = gql`
  query AgentRuns($conversationId: String!, $limit: Int) {
    agentRuns(conversationId: $conversationId, limit: $limit) {
      runId
      conversationId
      triggerMessageId
      agentName
      agentPass
      modelUsed
      inputTokens
      outputTokens
      durationMs
      status
      retryCount
      errorMessage
      createdAt
    }
  }
`;

export const GENERATED_IMAGES_QUERY = gql`
  query GeneratedImages($conversationId: String!, $characterId: String, $limit: Int) {
    generatedImages(conversationId: $conversationId, characterId: $characterId, limit: $limit) {
      ...GeneratedImageFields
    }
  }
  ${GENERATED_IMAGE_FRAGMENT}
`;

export const COMFYUI_STATUS_QUERY = gql`
  query ComfyUIStatus {
    comfyuiStatus {
      connected
      queueSize
      availableModels
      availableLoras
    }
  }
`;

// Note: workflowJson and defaultParams are JsonObject fields marked @Ignore on
// the backend model; the raw workflow payload is not exposed over GraphQL.
export const COMFYUI_WORKFLOWS_QUERY = gql`
  query ComfyUIWorkflows {
    comfyuiWorkflows {
      workflowId
      name
      description
      triggerType
      isBuiltIn
      createdAt
      updatedAt
    }
  }
`;

export const ACHIEVEMENTS_QUERY = gql`
  query Achievements($category: String) {
    achievements(category: $category) {
      ...AchievementFields
    }
  }
  ${ACHIEVEMENT_FRAGMENT}
`;

export const USER_ACHIEVEMENTS_QUERY = gql`
  query UserAchievements($userId: String) {
    userAchievements(userId: $userId) {
      ...UserAchievementFields
    }
  }
  ${USER_ACHIEVEMENT_FRAGMENT}
`;

export const USER_PROGRESSION_QUERY = gql`
  query UserProgression($userId: String) {
    userProgression(userId: $userId) {
      ...UserProgressionFields
    }
  }
  ${USER_PROGRESSION_FRAGMENT}
`;

export const STORY_BRANCHES_QUERY = gql`
  query StoryBranches($conversationId: String!) {
    storyBranches(conversationId: $conversationId) {
      ...StoryBranchFields
    }
  }
  ${STORY_BRANCH_FRAGMENT}
`;

export const PARTIES_QUERY = gql`
  query Parties {
    parties {
      ...PartyFields
    }
  }
  ${PARTY_FRAGMENT}
`;

export const PARTY_QUERY = gql`
  query Party($partyId: String!) {
    party(partyId: $partyId) {
      ...PartyFields
    }
  }
  ${PARTY_FRAGMENT}
`;

export const PARTY_BY_INVITE_CODE_QUERY = gql`
  query PartyByInviteCode($inviteCode: String!) {
    partyByInviteCode(inviteCode: $inviteCode) {
      ...PartyFields
    }
  }
  ${PARTY_FRAGMENT}
`;

export const PUBLISHED_STORIES_QUERY = gql`
  query PublishedStories($genre: String, $limit: Int, $offset: Int) {
    publishedStories(genre: $genre, limit: $limit, offset: $offset) {
      items {
        ...PublishedStoryFields
      }
      totalCount
      hasMore
    }
  }
  ${PUBLISHED_STORY_FRAGMENT}
`;

export const PUBLISHED_STORY_QUERY = gql`
  query PublishedStory($storyId: String!) {
    publishedStory(storyId: $storyId) {
      ...PublishedStoryFields
    }
  }
  ${PUBLISHED_STORY_FRAGMENT}
`;

export const FEATURED_STORIES_QUERY = gql`
  query FeaturedStories($limit: Int) {
    featuredStories(limit: $limit) {
      ...PublishedStoryFields
    }
  }
  ${PUBLISHED_STORY_FRAGMENT}
`;

export const COMMUNITY_TEMPLATES_QUERY = gql`
  query CommunityTemplates($limit: Int, $offset: Int) {
    communityTemplates(limit: $limit, offset: $offset) {
      items {
        submissionId
        templateId
        template {
          ...StoryTemplateFields
        }
        userId
        status
        downloads
        rating
        ratingCount
        submittedAt
      }
      totalCount
      hasMore
    }
  }
  ${STORY_TEMPLATE_FRAGMENT}
`;

// ============================================
// ROLEPLAY MUTATIONS
// ============================================

export const CREATE_ROLEPLAY_CONVERSATION = gql`
  mutation CreateRoleplayConversation($input: CreateRoleplayConversationInput!) {
    createRoleplayConversation(input: $input) {
      id
      title
      isRoleplay
      rpMode
      agentPipelineEnabled
      createdAt
    }
  }
`;

export const UPDATE_WORLD_STATE = gql`
  mutation UpdateWorldState($conversationId: String!, $input: UpdateWorldStateInput!) {
    updateWorldState(conversationId: $conversationId, input: $input) {
      ...WorldStateFields
    }
  }
  ${WORLD_STATE_FRAGMENT}
`;

export const INITIALIZE_FROM_TEMPLATE = gql`
  mutation InitializeFromTemplate($conversationId: String!, $templateId: String!) {
    initializeFromTemplate(conversationId: $conversationId, templateId: $templateId) {
      ...WorldStateFields
    }
  }
  ${WORLD_STATE_FRAGMENT}
`;

export const SET_CHARACTER_STAT = gql`
  mutation SetCharacterStat($input: SetCharacterStatInput!) {
    setCharacterStat(input: $input) {
      ...CharacterStatFields
    }
  }
  ${CHARACTER_STAT_FRAGMENT}
`;

export const REMOVE_CHARACTER_STAT = gql`
  mutation RemoveCharacterStat($conversationId: String!, $characterId: String!, $statName: String!) {
    removeCharacterStat(conversationId: $conversationId, characterId: $characterId, statName: $statName)
  }
`;

export const SET_RELATIONSHIP = gql`
  mutation SetRelationship($input: SetRelationshipInput!) {
    setRelationship(input: $input) {
      ...CharacterRelationshipFields
    }
  }
  ${CHARACTER_RELATIONSHIP_FRAGMENT}
`;

export const ADD_INVENTORY_ITEM = gql`
  mutation AddInventoryItem($input: AddInventoryItemInput!) {
    addInventoryItem(input: $input) {
      ...CharacterInventoryFields
    }
  }
  ${CHARACTER_INVENTORY_FRAGMENT}
`;

export const REMOVE_INVENTORY_ITEM = gql`
  mutation RemoveInventoryItem($inventoryId: String!) {
    removeInventoryItem(inventoryId: $inventoryId)
  }
`;

export const EQUIP_ITEM = gql`
  mutation EquipItem($inventoryId: String!, $equipped: Boolean!) {
    equipItem(inventoryId: $inventoryId, equipped: $equipped) {
      ...CharacterInventoryFields
    }
  }
  ${CHARACTER_INVENTORY_FRAGMENT}
`;

export const CREATE_BOOKMARK = gql`
  mutation CreateBookmark($input: CreateBookmarkInput!) {
    createBookmark(input: $input) {
      ...StoryBookmarkFields
    }
  }
  ${STORY_BOOKMARK_FRAGMENT}
`;

export const DELETE_BOOKMARK = gql`
  mutation DeleteBookmark($bookmarkId: String!) {
    deleteBookmark(bookmarkId: $bookmarkId)
  }
`;

export const ROLL_DICE = gql`
  mutation RollDice($input: RollDiceInput!) {
    rollDice(input: $input) {
      ...DiceRollFields
    }
  }
  ${DICE_ROLL_FRAGMENT}
`;

export const CHANGE_SCENE = gql`
  mutation ChangeScene($input: ChangeSceneInput!) {
    changeScene(input: $input) {
      ...SceneFields
    }
  }
  ${SCENE_FRAGMENT}
`;

export const MOVE_CHARACTER_TO_SCENE = gql`
  mutation MoveCharacterToScene($conversationId: String!, $characterId: String!, $sceneId: String!) {
    moveCharacterToScene(conversationId: $conversationId, characterId: $characterId, sceneId: $sceneId)
  }
`;

export const UPDATE_AGENT_MODEL_CONFIG = gql`
  mutation UpdateAgentModelConfig($conversationId: String!, $input: AgentModelConfigInput!) {
    updateAgentModelConfig(conversationId: $conversationId, input: $input) {
      ...AgentModelConfigFields
    }
  }
  ${AGENT_MODEL_CONFIG_FRAGMENT}
`;

export const GENERATE_IMAGE = gql`
  mutation GenerateImage($input: GenerateImageInput!) {
    generateImage(input: $input) {
      ...GeneratedImageFields
    }
  }
  ${GENERATED_IMAGE_FRAGMENT}
`;

export const CREATE_BRANCH = gql`
  mutation CreateBranch($input: CreateBranchInput!) {
    createBranch(input: $input) {
      ...StoryBranchFields
    }
  }
  ${STORY_BRANCH_FRAGMENT}
`;

export const CREATE_PARTY = gql`
  mutation CreateParty($input: CreatePartyInput!) {
    createParty(input: $input) {
      ...PartyFields
    }
  }
  ${PARTY_FRAGMENT}
`;

export const JOIN_PARTY = gql`
  mutation JoinParty($partyId: String!) {
    joinParty(partyId: $partyId) {
      id
      partyId
      userId
      role
      joinedAt
    }
  }
`;

export const JOIN_PARTY_BY_CODE = gql`
  mutation JoinPartyByCode($inviteCode: String!) {
    joinPartyByCode(inviteCode: $inviteCode) {
      id
      partyId
      userId
      role
      joinedAt
    }
  }
`;

export const LEAVE_PARTY = gql`
  mutation LeaveParty($partyId: String!) {
    leaveParty(partyId: $partyId)
  }
`;

export const PUBLISH_STORY = gql`
  mutation PublishStory($input: PublishStoryInput!) {
    publishStory(input: $input) {
      ...PublishedStoryFields
    }
  }
  ${PUBLISHED_STORY_FRAGMENT}
`;

export const RATE_STORY = gql`
  mutation RateStory($input: RateStoryInput!) {
    rateStory(input: $input) {
      id
      storyId
      userId
      rating
      review
      createdAt
    }
  }
`;

export const UPDATE_PARTY_MEMBER_ROLE = gql`
  mutation UpdatePartyMemberRole($partyId: String!, $userId: String!, $role: String!) {
    updatePartyMemberRole(partyId: $partyId, userId: $userId, role: $role) {
      id
      partyId
      userId
      role
      joinedAt
    }
  }
`;

export const CANCEL_IMAGE_GENERATION = gql`
  mutation CancelImageGeneration($promptId: String!) {
    cancelImageGeneration(promptId: $promptId)
  }
`;

export const UNPUBLISH_STORY = gql`
  mutation UnpublishStory($storyId: String!) {
    unpublishStory(storyId: $storyId)
  }
`;

export const SUBMIT_TEMPLATE = gql`
  mutation SubmitTemplate($input: SubmitTemplateInput!) {
    submitTemplate(input: $input) {
      submissionId
      templateId
      template {
        ...StoryTemplateFields
      }
      userId
      status
      downloads
      rating
      ratingCount
      submittedAt
    }
  }
  ${STORY_TEMPLATE_FRAGMENT}
`;

// ============================================
// ROLEPLAY SUBSCRIPTIONS
// ============================================

export const WORLD_STATE_CHANGED_SUB = gql`
  subscription WorldStateChanged($conversationId: String!) {
    worldStateChanged(conversationId: $conversationId) {
      ...WorldStateFields
    }
  }
  ${WORLD_STATE_FRAGMENT}
`;

export const DICE_ROLLED_SUB = gql`
  subscription DiceRolled($conversationId: String!) {
    diceRolled(conversationId: $conversationId) {
      ...DiceRollFields
    }
  }
  ${DICE_ROLL_FRAGMENT}
`;

export const SCENE_CHANGED_SUB = gql`
  subscription SceneChanged($conversationId: String!) {
    sceneChanged(conversationId: $conversationId) {
      ...SceneFields
    }
  }
  ${SCENE_FRAGMENT}
`;

export const STAT_CHANGED_SUB = gql`
  subscription StatChanged($conversationId: String!) {
    statChanged(conversationId: $conversationId) {
      ...CharacterStatFields
    }
  }
  ${CHARACTER_STAT_FRAGMENT}
`;

export const RELATIONSHIP_CHANGED_SUB = gql`
  subscription RelationshipChanged($conversationId: String!) {
    relationshipChanged(conversationId: $conversationId) {
      ...CharacterRelationshipFields
    }
  }
  ${CHARACTER_RELATIONSHIP_FRAGMENT}
`;

// Events invalidate inventory queries; private item details stay in authorized queries.
export const INVENTORY_CHANGED_SUB = gql`
  subscription InventoryChanged($conversationId: String!) {
    inventoryChanged(conversationId: $conversationId) { inventoryId conversationId }
  }
`;

export const INVENTORY_ACCESS_QUERY = gql`
  query InventoryAccess($conversationId: String!, $characterId: String, $userId: String) {
    inventoryAccess(conversationId: $conversationId, characterId: $characterId, userId: $userId) {
      canModify canManage recipients { id name kind canReceiveEffects }
    }
  }
`;

export const IMAGE_GENERATED_SUB = gql`
  subscription ImageGenerated($conversationId: String!) {
    imageGenerated(conversationId: $conversationId) {
      ...GeneratedImageFields
    }
  }
  ${GENERATED_IMAGE_FRAGMENT}
`;

export const ACHIEVEMENT_UNLOCKED_SUB = gql`
  subscription AchievementUnlocked($userId: String!) {
    achievementUnlocked(userId: $userId) {
      ...UserAchievementFields
    }
  }
  ${USER_ACHIEVEMENT_FRAGMENT}
`;

// ============================================
// CASTING DIRECTOR OPERATIONS
// ============================================

export const CHARACTER_PROPOSAL_FRAGMENT = gql`
  fragment CharacterProposalFields on CharacterProposal {
    proposalId
    conversationId
    triggerMessageId
    castingPrompt
    proposedName
    proposedDescription
    proposedSystemPrompt
    proposedGreeting
    proposedPersonality
    proposedAppearance
    proposedBackstory
    proposedGoals
    accountantReview
    skepticReview
    status
    createdCharacterId
    createdAt
    updatedAt
  }
`;

export const CHARACTER_PROPOSALS_QUERY = gql`
  query CharacterProposals($conversationId: String!) {
    characterProposals(conversationId: $conversationId) {
      ...CharacterProposalFields
    }
  }
  ${CHARACTER_PROPOSAL_FRAGMENT}
`;

export const APPROVE_CHARACTER_PROPOSAL = gql`
  mutation ApproveCharacterProposal($proposalId: String!) {
    approveCharacterProposal(proposalId: $proposalId) {
      id
      name
      description
      avatar
      greeting
      systemPrompt
      personality
      appearance
      backstory
      goals
    }
  }
`;

export const REJECT_CHARACTER_PROPOSAL = gql`
  mutation RejectCharacterProposal($proposalId: String!) {
    rejectCharacterProposal(proposalId: $proposalId)
  }
`;

export const CHARACTER_PROPOSAL_CREATED_SUB = gql`
  subscription CharacterProposalCreated($conversationId: String!) {
    characterProposalCreated(conversationId: $conversationId) {
      ...CharacterProposalFields
    }
  }
  ${CHARACTER_PROPOSAL_FRAGMENT}
`;

export const IMAGE_GENERATION_PROGRESS_SUB = gql`
  subscription ImageGenerationProgress($conversationId: String!) {
    imageGenerationProgress(conversationId: $conversationId) {
      promptId
      conversationId
      progress
      currentStep
      totalSteps
      status
    }
  }
`;

// ============================================
// BATTLE OPERATIONS
// ============================================

export const BATTLE_FRAGMENT = gql`
  fragment BattleFields on Battle {
    battleId
    conversationId
    status
    roundNumber
    currentNarration
    actionOptions
    battleData
    canManage
    votes { id battleId roundNumber userId optionIndex }
    createdAt
    updatedAt
  }
`;

export const BATTLE_QUERY = gql`
  query Battle($battleId: String!) {
    battle(battleId: $battleId) {
      ...BattleFields
    }
  }
  ${BATTLE_FRAGMENT}
`;

export const ACTIVE_BATTLE_QUERY = gql`
  query ActiveBattle($conversationId: String!) {
    activeBattle(conversationId: $conversationId) {
      ...BattleFields
    }
  }
  ${BATTLE_FRAGMENT}
`;

export const INITIATE_BATTLE = gql`
  mutation InitiateBattle($input: InitiateBattleInput!) {
    initiateBattle(input: $input) {
      ...BattleFields
    }
  }
  ${BATTLE_FRAGMENT}
`;

export const VOTE_ACTION = gql`
  mutation VoteAction($input: VoteActionInput!) {
    voteAction(input: $input) {
      id
      battleId
      roundNumber
      userId
      optionIndex
    }
  }
`;

export const RESOLVE_BATTLE_ROUND = gql`
  mutation ResolveBattleRound($battleId: String!, $roundNumber: Int) {
    resolveBattleRound(battleId: $battleId, roundNumber: $roundNumber) {
      ...BattleFields
    }
  }
  ${BATTLE_FRAGMENT}
`;

export const END_BATTLE = gql`
  mutation EndBattle($battleId: String!, $status: String!) {
    endBattle(battleId: $battleId, status: $status) {
      ...BattleFields
    }
  }
  ${BATTLE_FRAGMENT}
`;

export const BATTLE_ROUND_SUB = gql`
  subscription BattleRound($conversationId: String!) {
    battleRound(conversationId: $conversationId) {
      ...BattleFields
    }
  }
  ${BATTLE_FRAGMENT}
`;

export const BATTLE_STARTED_SUB = gql`
  subscription BattleStarted($conversationId: String!) {
    battleStarted(conversationId: $conversationId) {
      ...BattleFields
    }
  }
  ${BATTLE_FRAGMENT}
`;

export const BATTLE_ENDED_SUB = gql`
  subscription BattleEnded($conversationId: String!) {
    battleEnded(conversationId: $conversationId) {
      ...BattleFields
    }
  }
  ${BATTLE_FRAGMENT}
`;

export const USE_INVENTORY_ITEM = gql`
  mutation UseInventoryItem($inventoryId: String!, $targetCharacterId: String) {
    useInventoryItem(inventoryId: $inventoryId, targetCharacterId: $targetCharacterId) {
      ...CharacterInventoryFields
    }
  }
  ${CHARACTER_INVENTORY_FRAGMENT}
`;

export const TRANSFER_INVENTORY_ITEM = gql`
  mutation TransferInventoryItem($inventoryId: String!, $targetCharacterId: String, $targetUserId: String, $quantity: Int) {
    transferInventoryItem(inventoryId: $inventoryId, targetCharacterId: $targetCharacterId, targetUserId: $targetUserId, quantity: $quantity) {
      ...CharacterInventoryFields
    }
  }
  ${CHARACTER_INVENTORY_FRAGMENT}
`;


export const BATTLES_QUERY = gql`
  query Battles($conversationId: String!) {
    battles(conversationId: $conversationId) { ...BattleFields }
  }
  ${BATTLE_FRAGMENT}
`;
export const BATTLE_VOTE_SUB = gql`
  subscription BattleVote($conversationId: String!) {
    battleVote(conversationId: $conversationId) { id battleId userId roundNumber optionIndex }
  }
`;
export const PROPOSE_BATTLE_ACTION = gql`
  mutation ProposeBattleAction($battleId: String!, $roundNumber: Int!, $description: String!) {
    proposeBattleAction(battleId: $battleId, roundNumber: $roundNumber, description: $description) { ...BattleFields }
  }
  ${BATTLE_FRAGMENT}
`;
