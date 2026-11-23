import { gql } from '@apollo/client';

// ============================================
// USER OPERATIONS
// ============================================

export const USER_FRAGMENT = gql`
  fragment UserFields on User {
    id
    username
    email
    displayName
    bio
    role
    createdAt
    lastLogin
    emailVerified
    profilePicture
    preferences {
      theme
      language
      fontSize
      soundEnabled
      notificationsEnabled
    }
  }
`;

export const LOGIN_MUTATION = gql`
  mutation Login($username: String!, $password: String!) {
    login(username: $username, password: $password) {
      user {
        ...UserFields
      }
      accessToken
      refreshToken
      expiresIn
    }
  }
  ${USER_FRAGMENT}
`;

export const REGISTER_MUTATION = gql`
  mutation Register($username: String!, $email: String!, $password: String!) {
    register(username: $username, email: $email, password: $password) {
      user {
        ...UserFields
      }
      accessToken
      refreshToken
      expiresIn
    }
  }
  ${USER_FRAGMENT}
`;

export const LOGOUT_MUTATION = gql`
  mutation Logout {
    logout
  }
`;

export const REFRESH_TOKEN_MUTATION = gql`
  mutation RefreshToken($refreshToken: String!) {
    refreshToken(refreshToken: $refreshToken) {
      accessToken
      refreshToken
    }
  }
`;

export const ME_QUERY = gql`
  query Me {
    me {
      ...UserFields
    }
  }
  ${USER_FRAGMENT}
`;

export const UPDATE_USER_MUTATION = gql`
  mutation UpdateUser($input: UpdateUserInput!) {
    updateUser(input: $input) {
      ...UserFields
    }
  }
  ${USER_FRAGMENT}
`;

export const UPDATE_PROFILE_MUTATION = gql`
  mutation UpdateProfile($input: UpdateProfileInput!) {
    updateProfile(input: $input) {
      ...UserFields
    }
  }
  ${USER_FRAGMENT}
`;

export const CHANGE_PASSWORD_MUTATION = gql`
  mutation ChangePassword($oldPassword: String!, $newPassword: String!) {
    changePassword(oldPassword: $oldPassword, newPassword: $newPassword) {
      success
      message
    }
  }
`;

// ============================================
// CONVERSATION OPERATIONS
// ============================================

export const CONVERSATION_FRAGMENT = gql`
  fragment ConversationFields on Conversation {
    id
    title
    description
    createdAt
    updatedAt
    lastMessageAt
    messageCount
    isPinned
    isArchived
    category
    tags
    owner {
      id
      username
    }
    character {
      id
      personality
      gender
      pronouns
      ageYears
      name
      description
      avatar
    }
    lastMessage {
      id
      content
      role
      createdAt
    }
    # Group chat fields
    isGroupChat
    turnMode
    groupState
    currentSpeaker {
      id
      name
      avatar
    }
    characters {
      id
      character {
        id
        personality
        gender
        pronouns
        ageYears
        name
        avatar
      }
      turnOrder
      isActive
      responseCount
    }
    # Roleplay fields
    isRoleplay
    rpMode
    agentPipelineEnabled
    activeSceneId
    storyTemplateId
  }
`;

export const CONVERSATIONS_QUERY = gql`
  query Conversations($filter: ConversationFilter, $limit: Int, $offset: Int) {
    conversations(filter: $filter, limit: $limit, offset: $offset) {
      items {
        ...ConversationFields
      }
      totalCount
      hasMore
    }
  }
  ${CONVERSATION_FRAGMENT}
`;

export const CONVERSATION_QUERY = gql`
  query Conversation($id: String!) {
    conversation(id: $id) {
      ...ConversationFields
    }
  }
  ${CONVERSATION_FRAGMENT}
`;

export const CREATE_CONVERSATION_MUTATION = gql`
  mutation CreateConversation($input: CreateConversationInput!) {
    createConversation(input: $input) {
      ...ConversationFields
    }
  }
  ${CONVERSATION_FRAGMENT}
`;

export const UPDATE_CONVERSATION_MUTATION = gql`
  mutation UpdateConversation($id: String!, $input: UpdateConversationInput!) {
    updateConversation(id: $id, input: $input) {
      ...ConversationFields
    }
  }
  ${CONVERSATION_FRAGMENT}
`;

export const DELETE_CONVERSATION_MUTATION = gql`
  mutation DeleteConversation($id: String!) {
    deleteConversation(id: $id)
  }
`;

export const PIN_CONVERSATION_MUTATION = gql`
  mutation PinConversation($id: String!) {
    pinConversation(id: $id) {
      ...ConversationFields
    }
  }
  ${CONVERSATION_FRAGMENT}
`;

export const ARCHIVE_CONVERSATION_MUTATION = gql`
  mutation ArchiveConversation($id: String!) {
    archiveConversation(id: $id) {
      ...ConversationFields
    }
  }
  ${CONVERSATION_FRAGMENT}
`;

export const SWITCH_CHARACTER_MUTATION = gql`
  mutation SwitchCharacter($conversationId: String!, $characterId: String!) {
    switchCharacter(conversationId: $conversationId, characterId: $characterId) {
      ...ConversationFields
    }
  }
  ${CONVERSATION_FRAGMENT}
`;

// ============================================
// MESSAGE OPERATIONS
// ============================================

export const MESSAGE_FRAGMENT = gql`
  fragment MessageFields on Message {
    id
    seq
    content
    contentBase64
    role
    displayName
    isBase64Encoded
    createdAt
    updatedAt
    editCount
    isEdited
    isDeleted
    userId
    characterId
    isInterjection
    messageType
    battleId
    generationStats {
      promptTokens
      completionTokens
      totalTokens
      promptEvalMs
      generationMs
      totalTimeMs
      tokensPerSecond
    }
    author {
      id
      username
      displayName
      profilePicture
    }
    character {
      id
      personality
      gender
      pronouns
      ageYears
      name
      avatar
    }
    conversation {
      id
    }
    attachments {
      id
      filename
      mimeType
      size
      url
    }
  }
`;

export const MESSAGES_QUERY = gql`
  query Messages($conversationId: String!, $limit: Int, $before: String) {
    messages(conversationId: $conversationId, limit: $limit, before: $before) {
      items {
        ...MessageFields
      }
      hasMore
      cursor
    }
  }
  ${MESSAGE_FRAGMENT}
`;

export const SEND_MESSAGE_MUTATION = gql`
  mutation SendMessage($input: SendMessageInput!) {
    sendMessage(input: $input) {
      ...MessageFields
    }
  }
  ${MESSAGE_FRAGMENT}
`;

export const EDIT_MESSAGE_MUTATION = gql`
  mutation EditMessage($id: String!, $content: String!) {
    editMessage(id: $id, content: $content) {
      ...MessageFields
    }
  }
  ${MESSAGE_FRAGMENT}
`;

export const DELETE_MESSAGE_MUTATION = gql`
  mutation DeleteMessage($id: String!) {
    deleteMessage(id: $id)
  }
`;

export const REGENERATE_MESSAGE_MUTATION = gql`
  mutation RegenerateMessage($id: String!) {
    regenerateMessage(id: $id) {
      ...MessageFields
    }
  }
  ${MESSAGE_FRAGMENT}
`;

export const STOP_GENERATION_MUTATION = gql`
  mutation StopGeneration($conversationId: String!) {
    stopGeneration(conversationId: $conversationId)
  }
`;

export const SEND_TYPING_INDICATOR_MUTATION = gql`
  mutation SendTypingIndicator($conversationId: String!, $isTyping: Boolean!) {
    sendTypingIndicator(conversationId: $conversationId, isTyping: $isTyping)
  }
`;

// ============================================
// MESSAGE SEARCH OPERATIONS
// ============================================

export const SEARCH_MESSAGES_QUERY = gql`
  query SearchMessages($input: MessageSearchInput!) {
    searchMessages(input: $input) {
      items {
        id
        content
        role
        displayName
        highlightedContent
        createdAt
        conversation {
          id
          title
        }
      }
      totalCount
      hasMore
    }
  }
`;

// ============================================
// MESSAGE REACTION OPERATIONS
// ============================================

export const REACT_TO_MESSAGE_MUTATION = gql`
  mutation ReactToMessage($messageId: String!, $reaction: String!) {
    reactToMessage(messageId: $messageId, reaction: $reaction) {
      id
      messageId
      userId
      reactionType
      createdAt
    }
  }
`;

export const REMOVE_REACTION_MUTATION = gql`
  mutation RemoveReaction($messageId: String!, $reaction: String!) {
    removeReaction(messageId: $messageId, reaction: $reaction)
  }
`;

// ============================================
// CHARACTER OPERATIONS
// ============================================

export const CHARACTER_FRAGMENT = gql`
  fragment CharacterFields on Character {
    id
    name
    description
    personality
    avatar
    greeting
    systemPrompt
    temperature
    maxTokens
    topP
    topK
    repeatPenalty
    isPublic
    isDefault
    isFavorite
    rating
    usageCount
    creator {
      id
      username
    }
    tags
    createdAt
    updatedAt
    # Extended character fields
    appearance
    likes
    dislikes
    backstory
    goals
    examples
    category
    initialAffinityToUser
    initialAffinityFromUser
    # Physical profile and base attributes
    gender
    pronouns
    ageYears
    height
    build
    hair
    eyes
    strength
    dexterity
    constitution
    intelligence
    wisdom
    charisma
  }
`;

export const CHARACTERS_QUERY = gql`
  query Characters($filter: CharacterFilter, $limit: Int, $offset: Int) {
    characters(filter: $filter, limit: $limit, offset: $offset) {
      items {
        ...CharacterFields
      }
      totalCount
      hasMore
    }
  }
  ${CHARACTER_FRAGMENT}
`;

export const CHARACTER_QUERY = gql`
  query Character($id: String!) {
    character(id: $id) {
      ...CharacterFields
    }
  }
  ${CHARACTER_FRAGMENT}
`;

export const CREATE_CHARACTER_MUTATION = gql`
  mutation CreateCharacter($input: CreateCharacterInput!) {
    createCharacter(input: $input) {
      ...CharacterFields
    }
  }
  ${CHARACTER_FRAGMENT}
`;

export const UPDATE_CHARACTER_MUTATION = gql`
  mutation UpdateCharacter($id: String!, $input: UpdateCharacterInput!) {
    updateCharacter(id: $id, input: $input) {
      ...CharacterFields
    }
  }
  ${CHARACTER_FRAGMENT}
`;

export const DELETE_CHARACTER_MUTATION = gql`
  mutation DeleteCharacter($id: String!) {
    deleteCharacter(id: $id)
  }
`;

export const GENERATE_CHARACTER_MUTATION = gql`
  mutation GenerateCharacter($prompt: String!) {
    generateCharacter(prompt: $prompt) {
      character {
        ...CharacterFields
      }
      lawfulness
      goodness
      alignment
      relationships {
        targetName
        tier
        reason
      }
    }
  }
  ${CHARACTER_FRAGMENT}
`;

export const GENERATE_CHARACTER_ROSTER_MUTATION = gql`
  mutation GenerateCharacterRoster($prompt: String!, $count: Int!) {
    generateCharacterRoster(prompt: $prompt, count: $count) {
      character {
        ...CharacterFields
      }
      lawfulness
      goodness
      alignment
      relationships {
        targetName
        tier
        reason
      }
    }
  }
  ${CHARACTER_FRAGMENT}
`;

export const CREATE_CHARACTER_ROSTER_MUTATION = gql`
  mutation CreateCharacterRoster($inputs: [RosterMemberInput!]!, $groupName: String, $setting: String) {
    createCharacterRoster(inputs: $inputs, groupName: $groupName, setting: $setting) {
      ...CharacterFields
    }
  }
  ${CHARACTER_FRAGMENT}
`;

export const CHARACTER_GROUPS_QUERY = gql`
  query CharacterGroups {
    characterGroups {
      id
      name
      description
      setting
      characterIds
      characterCount
      createdAt
      updatedAt
    }
  }
`;

export const RENAME_CHARACTER_GROUP_MUTATION = gql`
  mutation RenameCharacterGroup($id: String!, $name: String!, $description: String) {
    renameCharacterGroup(id: $id, name: $name, description: $description) {
      id
      name
      description
      setting
      characterIds
      characterCount
      createdAt
      updatedAt
    }
  }
`;

export const DELETE_CHARACTER_GROUP_MUTATION = gql`
  mutation DeleteCharacterGroup($id: String!) {
    deleteCharacterGroup(id: $id)
  }
`;

export const REMOVE_CHARACTER_FROM_ROSTER_MUTATION = gql`
  mutation RemoveCharacterFromRoster($groupId: String!, $characterId: String!) {
    removeCharacterFromCharacterGroup(groupId: $groupId, characterId: $characterId)
  }
`;

export const CLONE_CHARACTER_MUTATION = gql`
  mutation CloneCharacter($id: String!, $name: String!) {
    cloneCharacter(id: $id, name: $name) {
      ...CharacterFields
    }
  }
  ${CHARACTER_FRAGMENT}
`;

export const RATE_CHARACTER_MUTATION = gql`
  mutation RateCharacter($id: String!, $rating: Int!) {
    rateCharacter(id: $id, rating: $rating) {
      ...CharacterFields
    }
  }
  ${CHARACTER_FRAGMENT}
`;

export const FAVORITE_CHARACTER_MUTATION = gql`
  mutation FavoriteCharacter($id: String!, $isFavorite: Boolean!) {
    favoriteCharacter(id: $id, isFavorite: $isFavorite) {
      ...CharacterFields
    }
  }
  ${CHARACTER_FRAGMENT}
`;

// ============================================
// SETTINGS OPERATIONS
// ============================================

export const SETTINGS_FRAGMENT = gql`
  fragment SettingsFields on Settings {
    llm {
      model
      temperature
      maxTokens
      topP
      topK
      repeatPenalty
      presencePenalty
      frequencyPenalty
      minP
      tfsZ
      typicalP
      seed
      stopSequences
      nPredict
      nCtx
      nBatch
      nThreads
      nGpuLayers
      mainGpu
      promptTemplate
      systemPrompt
      chatFormat
      streamingEnabled
      mirostat
      mirostatTau
      mirostatEta
      contextSize
      templates
      currentTemplate
    }
    ui {
      theme
      fontSize
      fontFamily
      compactMode
      showTimestamps
      showTokenCount
      showModelInfo
      enterToSend
      soundEnabled
      notificationsEnabled
      autoScroll
      messageGrouping
      syntaxHighlighting
      markdownRendering
      emojiPicker
      spellCheck
      autoSave
      autoSaveInterval
      sidebarPosition
      sidebarCollapsed
      messageLayout
      language
      codeTheme
      editorTheme
      showLineNumbers
      wordWrap
    }
    privacy {
      shareAnalytics
      saveHistory
      encryptMessages
      clearOnLogout
      exportFormat
      dataRetentionDays
      analytics
      shareData
      publicProfile
      showOnlineStatus
    }
    notifications {
      desktop
      sound
      vibration
      mentions
      replies
      newConversations
      systemAlerts
      schedule
      customSchedule {
        startTime
        endTime
        daysOfWeek
      }
      enabled
      email
      messageAlerts
    }
    keyboard {
      shortcuts {
        key
        value
      }
    }
  }
`;

export const GET_SETTINGS_QUERY = gql`
  query GetSettings {
    settings {
      ...SettingsFields
    }
  }
  ${SETTINGS_FRAGMENT}
`;

export const UPDATE_SETTINGS_MUTATION = gql`
  mutation UpdateSettings($input: UpdateSettingsInput!) {
    updateSettings(input: $input) {
      ...SettingsFields
    }
  }
  ${SETTINGS_FRAGMENT}
`;

export const RESET_SETTINGS_MUTATION = gql`
  mutation ResetSettings {
    resetSettings {
      ...SettingsFields
    }
  }
  ${SETTINGS_FRAGMENT}
`;

export const EXPORT_SETTINGS_QUERY = gql`
  query ExportSettings {
    exportData {
      settings {
        ...SettingsFields
      }
      exportDate
      version
    }
  }
  ${SETTINGS_FRAGMENT}
`;

export const IMPORT_SETTINGS_MUTATION = gql`
  mutation ImportSettings($data: String!) {
    importSettings(data: $data) {
      ...SettingsFields
    }
  }
  ${SETTINGS_FRAGMENT}
`;

// ============================================
// ADMIN OPERATIONS
// ============================================

// System stats query - requires ADMIN role
export const SYSTEM_STATS_QUERY = gql`
  query SystemStats {
    systemStats {
      totalUsers
      activeUsers
      totalConversations
      totalMessages
      totalCharacters
      messagesLast24h
      newUsersLast24h
      serverUptime
      memoryUsed
      memoryTotal
      databaseSize
    }
  }
`;

// Admin users query - requires ADMIN role
export const ADMIN_USERS_QUERY = gql`
  query AdminUsers($pagination: PaginationInput) {
    users(pagination: $pagination) {
      id
      username
      email
      role
      createdAt
      lastLogin
      isBanned
      bannedAt
      bannedReason
    }
  }
`;

// Ban user mutation - requires ADMIN role
export const BAN_USER_MUTATION = gql`
  mutation BanUser($userId: String!, $reason: String!) {
    banUser(userId: $userId, reason: $reason) {
      success
      user {
        id
        username
        isBanned
        bannedAt
        bannedReason
      }
      isBanned
      bannedAt
      bannedReason
      message
    }
  }
`;

// Unban user mutation - requires ADMIN role
export const UNBAN_USER_MUTATION = gql`
  mutation UnbanUser($userId: String!) {
    unbanUser(userId: $userId) {
      success
      user {
        id
        username
        isBanned
      }
      isBanned
      message
    }
  }
`;

// Update user role mutation - requires ADMIN role
export const UPDATE_USER_ROLE_MUTATION = gql`
  mutation UpdateUserRole($userId: String!, $role: String!) {
    updateUserRole(userId: $userId, role: $role) {
      success
      user {
        id
        username
        role
      }
      previousRole
      newRole
      message
    }
  }
`;

// Shutdown server mutation - requires ADMIN role
export const SHUTDOWN_SERVER_MUTATION = gql`
  mutation ShutdownServer {
    shutdownServer
  }
`;

// Reload model mutation - requires ADMIN role
export const RELOAD_MODEL_MUTATION = gql`
  mutation ReloadModel {
    reloadModel
  }
`;

// Note: Backend has deleteUser which deletes current user, not other users
export const DELETE_USER_MUTATION = gql`
  mutation DeleteUser {
    deleteUser
  }
`;

// Health check query (available to all)
export const HEALTH_QUERY = gql`
  query Health {
    health
  }
`;

// ============================================
// SUBSCRIPTIONS
// ============================================

export const MESSAGE_STREAM_SUBSCRIPTION = gql`
  subscription MessageStream($conversationId: String!) {
    messageStream(conversationId: $conversationId) {
      id
      content
      streamingContent
      role
      messageType
      battleId
      isComplete
      tokenCount
      error
      createdAt
      characterId
      displayName
      conversationId
      generationStats {
        promptTokens
        completionTokens
        totalTokens
        promptEvalMs
        generationMs
        totalTimeMs
        tokensPerSecond
      }
    }
  }
`;

export const CONVERSATION_UPDATES_SUBSCRIPTION = gql`
  subscription ConversationUpdates($conversationId: String!) {
    conversationUpdates(conversationId: $conversationId) {
      ...ConversationFields
    }
  }
  ${CONVERSATION_FRAGMENT}
`;

export const TYPING_INDICATOR_SUBSCRIPTION = gql`
  subscription TypingIndicator($conversationId: String!) {
    typingIndicator(conversationId: $conversationId) {
      userId
      username
      isTyping
    }
  }
`;

export const CHARACTER_SWITCH_SUBSCRIPTION = gql`
  subscription CharacterSwitch($conversationId: String!) {
    characterSwitch(conversationId: $conversationId) {
      ...CharacterFields
    }
  }
  ${CHARACTER_FRAGMENT}
`;

export const USER_PRESENCE_SUBSCRIPTION = gql`
  subscription UserPresence {
    userPresence {
      userId
      username
      status
      lastSeen
    }
  }
`;

export const SYSTEM_NOTIFICATION_SUBSCRIPTION = gql`
  subscription SystemNotification {
    systemNotification {
      id
      type
      title
      message
      severity
      timestamp
    }
  }
`;

// ============================================
// HEALTH & STATUS
// ============================================

export const HEALTH_CHECK_QUERY = gql`
  query HealthCheck {
    health
  }
`;

export const LLM_STATUS_QUERY = gql`
  query LLMStatus {
    llmStatus {
      isRunning: running
      modelLoaded
      currentModel
      availableMemory
      usedMemory
      providerType
    }
  }
`;

export const LLM_CONFIG_QUERY = gql`
  query LLMConfig {
    llmConfig {
      modelPath
      gpuLayers
      contextSize
      batchSize
      threads
      temperature
      topP
      topK
      templates
      currentTemplate
      providerType
      externalBaseUrl
      externalModel
    }
  }
`;

// ============================================
// DATA MANAGEMENT OPERATIONS
// ============================================

export const EXPORT_DATA_QUERY = gql`
  query ExportData($filter: ExportFilter) {
    exportData(filter: $filter) {
      conversations {
        ...ConversationFields
      }
      messages {
        ...MessageFields
      }
      characters {
        ...CharacterFields
      }
      settings {
        ...SettingsFields
      }
      exportDate
      version
    }
  }
  ${CONVERSATION_FRAGMENT}
  ${MESSAGE_FRAGMENT}
  ${CHARACTER_FRAGMENT}
  ${SETTINGS_FRAGMENT}
`;

export const IMPORT_DATA_MUTATION = gql`
  mutation ImportData($data: String!) {
    importData(data: $data) {
      success
      message
      result {
        conversations
        messages
        characters
        settings
        errors
      }
    }
  }
`;

export const EXPORT_FORMATTED_DATA_QUERY = gql`
  query ExportFormattedData($format: String!, $filter: ExportFilter) {
    exportFormattedData(format: $format, filter: $filter)
  }
`;

export const SEND_SYSTEM_NOTIFICATION_MUTATION = gql`
  mutation SendSystemNotification(
    $type: String!
    $title: String!
    $message: String!
    $severity: String!
    $userId: String
  ) {
    sendSystemNotification(
      type: $type
      title: $title
      message: $message
      severity: $severity
      userId: $userId
    )
  }
`;

export const DELETE_ALL_DATA_MUTATION = gql`
  mutation DeleteAllData($dataType: String!) {
    deleteAllData(dataType: $dataType)
  }
`;

// Streaming recovery - get pending message if client disconnected during streaming
export const PENDING_STREAMING_MESSAGE_QUERY = gql`
  query PendingStreamingMessage($conversationId: String!) {
    pendingStreamingMessage(conversationId: $conversationId) {
      id
      content
      streamingContent
      role
      isComplete
      tokenCount
      error
      createdAt
      characterId
      displayName
      messageType
      battleId
      conversationId
      generationStats {
        promptTokens
        completionTokens
        totalTokens
        promptEvalMs
        generationMs
        totalTimeMs
        tokensPerSecond
      }
    }
  }
`;

// ============================================
// GROUP CHAT OPERATIONS
// ============================================

export const CONVERSATION_CHARACTER_FRAGMENT = gql`
  fragment ConversationCharacterFields on ConversationCharacter {
    id
    character {
      id
      personality
      gender
      pronouns
      ageYears
      name
      description
      avatar
      systemPrompt
    }
    joinedAt
    isActive
    turnOrder
    responseCount
    lastResponseAt
    weight
    interjectionProbability
  }
`;

export const INTERJECTION_REQUEST_FRAGMENT = gql`
  fragment InterjectionRequestFields on InterjectionRequest {
    id
    requester {
      id
      name
      avatar
    }
    requesterType
    reason
    priority
    status
    createdAt
    resolvedAt
  }
`;

export const GROUP_CONVERSATION_FRAGMENT = gql`
  fragment GroupConversationFields on Conversation {
    id
    title
    isGroupChat
    turnMode
    currentSpeaker {
      id
      name
      avatar
    }
    lastSpeaker {
      id
      name
      avatar
    }
    maxConsecutiveResponses
    consecutiveResponseCount
    groupState
    characters {
      ...ConversationCharacterFields
    }
    pendingInterjections {
      ...InterjectionRequestFields
    }
    messageCount
    createdAt
    updatedAt
  }
  ${CONVERSATION_CHARACTER_FRAGMENT}
  ${INTERJECTION_REQUEST_FRAGMENT}
`;

export const CREATE_GROUP_CONVERSATION_MUTATION = gql`
  mutation CreateGroupConversation($input: CreateGroupConversationInput!) {
    createGroupConversation(input: $input) {
      ...GroupConversationFields
    }
  }
  ${GROUP_CONVERSATION_FRAGMENT}
`;

export const ADD_CHARACTER_TO_GROUP_MUTATION = gql`
  mutation AddCharacterToGroup($input: AddCharacterToGroupInput!) {
    addCharacterToGroup(input: $input) {
      ...ConversationCharacterFields
    }
  }
  ${CONVERSATION_CHARACTER_FRAGMENT}
`;

export const REMOVE_CHARACTER_FROM_GROUP_MUTATION = gql`
  mutation RemoveCharacterFromGroup($input: RemoveCharacterFromGroupInput!) {
    removeCharacterFromGroup(input: $input)
  }
`;

export const UPDATE_GROUP_SETTINGS_MUTATION = gql`
  mutation UpdateGroupSettings($conversationId: String!, $input: UpdateGroupSettingsInput!) {
    updateGroupSettings(conversationId: $conversationId, input: $input) {
      ...GroupConversationFields
    }
  }
  ${GROUP_CONVERSATION_FRAGMENT}
`;

export const SEND_GROUP_MESSAGE_MUTATION = gql`
  mutation SendGroupMessage($input: SendGroupMessageInput!) {
    sendGroupMessage(input: $input) {
      ...MessageFields
      conversation {
        id
      }
    }
  }
  ${MESSAGE_FRAGMENT}
`;

export const TRIGGER_NEXT_CHARACTER_RESPONSE_MUTATION = gql`
  mutation TriggerNextCharacterResponse($conversationId: String!) {
    triggerNextCharacterResponse(conversationId: $conversationId) {
      id
      content
      role
      displayName
      character {
        id
        personality
        gender
        pronouns
        ageYears
        name
        avatar
      }
      createdAt
    }
  }
`;

export const REQUEST_INTERJECTION_MUTATION = gql`
  mutation RequestInterjection($input: RequestInterjectionInput!) {
    requestInterjection(input: $input) {
      ...InterjectionRequestFields
    }
  }
  ${INTERJECTION_REQUEST_FRAGMENT}
`;

export const CANCEL_INTERJECTION_MUTATION = gql`
  mutation CancelInterjection($interjectionId: String!) {
    cancelInterjection(interjectionId: $interjectionId)
  }
`;

export const APPROVE_INTERJECTION_MUTATION = gql`
  mutation ApproveInterjection($interjectionId: String!) {
    approveInterjection(interjectionId: $interjectionId) {
      ...InterjectionRequestFields
    }
  }
  ${INTERJECTION_REQUEST_FRAGMENT}
`;

export const REJECT_INTERJECTION_MUTATION = gql`
  mutation RejectInterjection($interjectionId: String!) {
    rejectInterjection(interjectionId: $interjectionId) {
      ...InterjectionRequestFields
    }
  }
  ${INTERJECTION_REQUEST_FRAGMENT}
`;

export const PAUSE_GROUP_CONVERSATION_MUTATION = gql`
  mutation PauseGroupConversation($conversationId: String!) {
    pauseGroupConversation(conversationId: $conversationId) {
      ...GroupConversationFields
    }
  }
  ${GROUP_CONVERSATION_FRAGMENT}
`;

export const RESUME_GROUP_CONVERSATION_MUTATION = gql`
  mutation ResumeGroupConversation($conversationId: String!) {
    resumeGroupConversation(conversationId: $conversationId) {
      ...GroupConversationFields
    }
  }
  ${GROUP_CONVERSATION_FRAGMENT}
`;

// Group chat subscriptions
export const GROUP_CHARACTER_RESPONSE_SUBSCRIPTION = gql`
  subscription GroupCharacterResponse($conversationId: String!) {
    groupCharacterResponse(conversationId: $conversationId) {
      id
      content
      streamingContent
      role
      messageType
      battleId
      isComplete
      tokenCount
      error
      createdAt
      characterId
      displayName
      conversationId
      generationStats {
        promptTokens
        completionTokens
        totalTokens
        promptEvalMs
        generationMs
        totalTimeMs
        tokensPerSecond
      }
    }
  }
`;

export const GROUP_STATE_CHANGED_SUBSCRIPTION = gql`
  subscription GroupStateChanged($conversationId: String!) {
    groupStateChanged(conversationId: $conversationId) {
      id
      groupState
      processingOnly
      processingStatus { conversationId runId sequence stage label active updatedAt }
      turnMode
      currentSpeaker {
        id
        name
        avatar
      }
      lastSpeaker {
        id
        name
        avatar
      }
      consecutiveResponseCount
    }
  }
`;

export const INTERJECTION_REQUESTED_SUBSCRIPTION = gql`
  subscription InterjectionRequested($conversationId: String!) {
    interjectionRequested(conversationId: $conversationId) {
      ...InterjectionRequestFields
    }
  }
  ${INTERJECTION_REQUEST_FRAGMENT}
`;

export const INTERJECTION_RESOLVED_SUBSCRIPTION = gql`
  subscription InterjectionResolved($conversationId: String!) {
    interjectionResolved(conversationId: $conversationId) {
      ...InterjectionRequestFields
    }
  }
  ${INTERJECTION_REQUEST_FRAGMENT}
`;

export const CHARACTER_JOINED_SUBSCRIPTION = gql`
  subscription CharacterJoined($conversationId: String!) {
    characterJoined(conversationId: $conversationId) {
      ...ConversationCharacterFields
    }
  }
  ${CONVERSATION_CHARACTER_FRAGMENT}
`;

export const CHARACTER_LEFT_SUBSCRIPTION = gql`
  subscription CharacterLeft($conversationId: String!) {
    characterLeft(conversationId: $conversationId)
  }
`;

export const TURN_CHANGED_SUBSCRIPTION = gql`
  subscription TurnChanged($conversationId: String!) {
    turnChanged(conversationId: $conversationId) {
      id
      name
      avatar
    }
  }
`;

// ============================================
// MULTI-USER INVITE OPERATIONS
// ============================================

export const CONVERSATION_PARTICIPANT_FRAGMENT = gql`
  fragment ConversationParticipantFields on ConversationParticipant {
    id
    conversationId
    user {
      id
      username
      displayName
      profilePicture
      email
    }
    role
    nickname
    isActive
    canInvite
    canAddCharacters
    joinedAt
    leftAt
    lastReadAt
    lastTypingAt
  }
`;

export const CONVERSATION_INVITE_FRAGMENT = gql`
  fragment ConversationInviteFields on ConversationInvite {
    id
    conversationId
    conversation {
      id
      title
      description
    }
    inviter {
      id
      username
      displayName
      profilePicture
    }
    invitee {
      id
      username
      displayName
      profilePicture
    }
    inviteeEmail
    inviteeUsername
    status
    message
    roleToGrant
    createdAt
    respondedAt
    expiresAt
  }
`;

// Queries
export const MY_INVITES_QUERY = gql`
  query MyInvites($filter: InviteFilter, $limit: Int, $offset: Int) {
    myInvites(filter: $filter, limit: $limit, offset: $offset) {
      items {
        ...ConversationInviteFields
      }
      totalCount
      hasMore
    }
  }
  ${CONVERSATION_INVITE_FRAGMENT}
`;

export const CONVERSATION_PARTICIPANTS_QUERY = gql`
  query ConversationParticipants($conversationId: String!) {
    conversationParticipants(conversationId: $conversationId) {
      ...ConversationParticipantFields
    }
  }
  ${CONVERSATION_PARTICIPANT_FRAGMENT}
`;

export const SEARCH_USERS_QUERY = gql`
  query SearchUsers($query: String!, $limit: Int) {
    searchUsers(query: $query, limit: $limit) {
      id
      username
      displayName
      profilePicture
      email
    }
  }
`;

// Mutations
export const INVITE_USER_TO_CONVERSATION_MUTATION = gql`
  mutation InviteUserToConversation($input: InviteUserInput!) {
    inviteUserToConversation(input: $input) {
      ...ConversationInviteFields
    }
  }
  ${CONVERSATION_INVITE_FRAGMENT}
`;

export const RESPOND_TO_INVITE_MUTATION = gql`
  mutation RespondToInvite($input: RespondToInviteInput!) {
    respondToInvite(input: $input) {
      ...ConversationInviteFields
    }
  }
  ${CONVERSATION_INVITE_FRAGMENT}
`;

export const CANCEL_INVITE_MUTATION = gql`
  mutation CancelInvite($inviteId: String!) {
    cancelInvite(inviteId: $inviteId)
  }
`;

export const UPDATE_PARTICIPANT_MUTATION = gql`
  mutation UpdateParticipant($input: UpdateParticipantInput!) {
    updateParticipant(input: $input) {
      ...ConversationParticipantFields
    }
  }
  ${CONVERSATION_PARTICIPANT_FRAGMENT}
`;

export const REMOVE_PARTICIPANT_MUTATION = gql`
  mutation RemoveParticipant($input: RemoveParticipantInput!) {
    removeParticipant(input: $input)
  }
`;

export const LEAVE_CONVERSATION_MUTATION = gql`
  mutation LeaveConversation($conversationId: String!) {
    leaveConversation(conversationId: $conversationId)
  }
`;

export const TRANSFER_OWNERSHIP_MUTATION = gql`
  mutation TransferOwnership($input: TransferOwnershipInput!) {
    transferOwnership(input: $input) {
      ...ConversationFields
    }
  }
  ${CONVERSATION_FRAGMENT}
`;

export const MARK_CONVERSATION_READ_MUTATION = gql`
  mutation MarkConversationRead($conversationId: String!) {
    markConversationRead(conversationId: $conversationId) {
      ...ConversationParticipantFields
    }
  }
  ${CONVERSATION_PARTICIPANT_FRAGMENT}
`;

// Personal directional relationship scores, separate from story-scoped bonds and public ratings.
export const AFFINITY_FRAGMENT = gql`
  fragment AffinityFields on CharacterAffinity {
    affinityId
    fromCharacterId
    toCharacterId
    fromName
    toName
    points
    tier
    updatedAt
    events {
      eventId
      reaction
      delta
      previousPoints
      points
      reason
      createdAt
    }
  }
`;
export const RELATIONSHIPS_QUERY = gql`
  query Relationships {
    relationshipActors {
      characterId
      name
      initialAffinityToUser
      initialAffinityFromUser
    }
    characterAffinities {
      ...AffinityFields
    }
  }
  ${AFFINITY_FRAGMENT}
`;
export const SET_CHARACTER_AFFINITY_MUTATION = gql`
  mutation SetCharacterAffinity(
    $fromCharacterId: String
    $toCharacterId: String
    $points: Int!
    $reason: String
    $requestId: String!
  ) {
    setCharacterAffinity(
      fromCharacterId: $fromCharacterId
      toCharacterId: $toCharacterId
      points: $points
      reason: $reason
      requestId: $requestId
    ) {
      ...AffinityFields
    }
  }
  ${AFFINITY_FRAGMENT}
`;
export const REACT_TO_CHARACTER_MUTATION = gql`
  mutation ReactToCharacter(
    $fromCharacterId: String
    $toCharacterId: String
    $reaction: String!
    $reason: String
    $requestId: String!
  ) {
    reactToCharacter(
      fromCharacterId: $fromCharacterId
      toCharacterId: $toCharacterId
      reaction: $reaction
      reason: $reason
      requestId: $requestId
    ) {
      ...AffinityFields
    }
  }
  ${AFFINITY_FRAGMENT}
`;

// Two-axis moral alignment, separate from feelings and story-scoped bonds.
export const ALIGNMENT_FRAGMENT = gql`
  fragment AlignmentFields on CharacterAlignment {
    alignmentId
    characterId
    name
    lawfulness
    goodness
    alignment
    updatedAt
    events {
      eventId
      action
      lawDelta
      goodDelta
      previousLawfulness
      previousGoodness
      lawfulness
      goodness
      alignment
      reason
      createdAt
    }
  }
`;
export const CHARACTER_ALIGNMENTS_QUERY = gql`
  query CharacterAlignments {
    relationshipActors {
      characterId
      name
    }
    characterAlignments {
      ...AlignmentFields
    }
  }
  ${ALIGNMENT_FRAGMENT}
`;
export const CHARACTER_ALIGNMENT_SUMMARY_QUERY = gql`
  query CharacterAlignmentSummary {
    characterAlignments {
      characterId
      alignment
      lawfulness
      goodness
    }
  }
`;
export const SET_CHARACTER_ALIGNMENT_MUTATION = gql`
  mutation SetCharacterAlignment(
    $characterId: String
    $lawfulness: Int!
    $goodness: Int!
    $reason: String
    $requestId: String!
  ) {
    setCharacterAlignment(
      characterId: $characterId
      lawfulness: $lawfulness
      goodness: $goodness
      reason: $reason
      requestId: $requestId
    ) {
      ...AlignmentFields
    }
  }
  ${ALIGNMENT_FRAGMENT}
`;
export const ADJUST_CHARACTER_ALIGNMENT_MUTATION = gql`
  mutation AdjustCharacterAlignment(
    $characterId: String
    $lawfulnessDelta: Int!
    $goodnessDelta: Int!
    $reason: String
    $requestId: String!
  ) {
    adjustCharacterAlignment(
      characterId: $characterId
      lawfulnessDelta: $lawfulnessDelta
      goodnessDelta: $goodnessDelta
      reason: $reason
      requestId: $requestId
    ) {
      ...AlignmentFields
    }
  }
  ${ALIGNMENT_FRAGMENT}
`;
