-- ============================================================
-- Veyloria Complete Database Schema
-- Version: 1.0.0 (Consolidated from V1-V4 migrations)
-- MySQL 8.0+
-- ============================================================

-- Drop tables in reverse dependency order for clean reset
-- Roleplay tables (Phase 7)
DROP TABLE IF EXISTS battle_vote;
DROP TABLE IF EXISTS battle;
-- Roleplay tables (Phase 6)
DROP TABLE IF EXISTS community_template;
DROP TABLE IF EXISTS story_rating;
DROP TABLE IF EXISTS published_story;
-- Roleplay tables (Phase 5)
DROP TABLE IF EXISTS party_member;
DROP TABLE IF EXISTS party;
DROP TABLE IF EXISTS map_item_position;
DROP TABLE IF EXISTS map_character_position;
DROP TABLE IF EXISTS local_area_connection;
DROP TABLE IF EXISTS local_area_visit;
DROP TABLE IF EXISTS local_area;
DROP TABLE IF EXISTS map_location;
DROP TABLE IF EXISTS world_map;
DROP TABLE IF EXISTS story_branch;
-- Roleplay tables (Phase 4)
DROP TABLE IF EXISTS user_achievement;
DROP TABLE IF EXISTS achievement;
DROP TABLE IF EXISTS user_progression;
-- Roleplay tables (Phase 3)
DROP TABLE IF EXISTS character_visual_ref;
DROP TABLE IF EXISTS comfyui_workflow;
-- Roleplay tables (Phase 2)
DROP TABLE IF EXISTS highlighter_cache;
DROP TABLE IF EXISTS agent_run;
DROP TABLE IF EXISTS agent_model_config;
-- Roleplay tables (Phase 1)
DROP TABLE IF EXISTS scene_character;
DROP TABLE IF EXISTS scene;
DROP TABLE IF EXISTS knowledge_entry;
DROP TABLE IF EXISTS generated_image;
DROP TABLE IF EXISTS dice_roll;
DROP TABLE IF EXISTS story_bookmark;
DROP TABLE IF EXISTS inventory_activity;
DROP TABLE IF EXISTS character_inventory;
DROP TABLE IF EXISTS character_relationship;
DROP TABLE IF EXISTS character_stat;
DROP TABLE IF EXISTS world_state;
DROP TABLE IF EXISTS story_template;
DROP TABLE IF EXISTS character_proposal;
-- Original tables
DROP TABLE IF EXISTS message_attachment;
DROP TABLE IF EXISTS message_edit;
DROP TABLE IF EXISTS message_reaction;
DROP TABLE IF EXISTS message_metadata;
DROP TABLE IF EXISTS attachment;
DROP TABLE IF EXISTS message;
DROP TABLE IF EXISTS interjection_request;
DROP TABLE IF EXISTS conversation_character;
DROP TABLE IF EXISTS conversation_invite;
DROP TABLE IF EXISTS conversation_participant;
DROP TABLE IF EXISTS conversation;
DROP TABLE IF EXISTS character_affinity_event;
DROP TABLE IF EXISTS character_affinity;
DROP TABLE IF EXISTS character_alignment_event;
DROP TABLE IF EXISTS character_alignment;
DROP TABLE IF EXISTS character_favorite;
DROP TABLE IF EXISTS character_rating;
DROP TABLE IF EXISTS `character`;
DROP TABLE IF EXISTS settings;
DROP TABLE IF EXISTS user_settings;
DROP TABLE IF EXISTS session;
DROP TABLE IF EXISTS journey_state;
DROP TABLE IF EXISTS user;

-- ============================================================
-- User table
-- ============================================================
CREATE TABLE user (
    userUUID VARCHAR(36) PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    passwordHash VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE,
    displayName VARCHAR(255),
    assistantName VARCHAR(255) DEFAULT 'Assistant',
    language VARCHAR(10) DEFAULT 'en',
    notifications BOOLEAN DEFAULT TRUE,
    sounds BOOLEAN DEFAULT TRUE,
    fontSize VARCHAR(20) DEFAULT 'medium',
    codeTheme VARCHAR(50) DEFAULT 'github-dark',
    analytics BOOLEAN DEFAULT FALSE,
    improvement BOOLEAN DEFAULT FALSE,
    admin BOOLEAN DEFAULT FALSE,
    preferredModelPath TEXT,
    preferredGpuLayers INT,
    apiLimits BIGINT DEFAULT -1,
    chatLimits BIGINT DEFAULT -1,
    currentConversationId VARCHAR(36),
    role VARCHAR(50) DEFAULT 'USER',
    lastLogin DATETIME,
    emailVerified BOOLEAN DEFAULT FALSE,
    profilePicture TEXT,
    bio TEXT,
    theme VARCHAR(50) DEFAULT 'dark',
    keyboardShortcuts TEXT,
    isBanned BOOLEAN DEFAULT FALSE,
    bannedAt DATETIME,
    bannedReason TEXT,
    bannedBy VARCHAR(36),
    desktopNotifications BOOLEAN DEFAULT FALSE,
    emailNotifications BOOLEAN DEFAULT FALSE,
    messageAlerts BOOLEAN DEFAULT TRUE,
    systemAlerts BOOLEAN DEFAULT TRUE,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_user_username (username),
    INDEX idx_user_email (email),
    INDEX idx_user_created (createdAt),
    INDEX idx_user_role (role),
    INDEX idx_user_banned (isBanned)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Session table for user sessions
-- ============================================================
CREATE TABLE session (
    sessionId VARCHAR(36) PRIMARY KEY,
    userId VARCHAR(36) NOT NULL,
    token TEXT NOT NULL,
    refreshToken TEXT,
    expiresAt DATETIME NOT NULL,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    lastActivity DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    userAgent TEXT,
    ipAddress VARCHAR(45),

    FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE CASCADE,
    INDEX idx_session_user (userId),
    INDEX idx_session_expires (expiresAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- User settings tables
-- ============================================================
CREATE TABLE user_settings (
    fk_userUUID VARCHAR(36) PRIMARY KEY,
    theme VARCHAR(50) DEFAULT 'dark',
    language VARCHAR(10) DEFAULT 'en',
    llmTemperature FLOAT DEFAULT 0.7,
    llmMaxTokens INT DEFAULT -1,
    llmTopP FLOAT DEFAULT 0.9,
    llmTopK INT DEFAULT 40,
    llmContextSize INT DEFAULT 4096,
    notificationsEnabled BOOLEAN DEFAULT TRUE,
    soundEnabled BOOLEAN DEFAULT TRUE,
    FOREIGN KEY (fk_userUUID) REFERENCES user(userUUID) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE settings (
    settingsId VARCHAR(36) PRIMARY KEY,
    userId VARCHAR(36) NOT NULL UNIQUE,
    llmSettings JSON,
    uiSettings JSON,
    notificationSettings JSON,
    privacySettings JSON,
    keyboardSettings JSON,
    created DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Character table (backticks required - 'character' is a reserved keyword)
-- ============================================================
CREATE TABLE `character` (
    characterUUID VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    systemPrompt TEXT,
    appearance TEXT,
    personality TEXT,
    likes TEXT,
    dislikes TEXT,
    backstory TEXT,
    goals TEXT,
    greeting TEXT,
    examples TEXT,
    avatar TEXT,
    isDefault BOOLEAN DEFAULT FALSE,
    isPublic BOOLEAN DEFAULT FALSE,
    ownerId VARCHAR(36),
    ownerName VARCHAR(255),
    conversationCount INT DEFAULT 0,
    usageCount INT DEFAULT 0,
    rating DOUBLE DEFAULT 0.0,
    temperature DOUBLE DEFAULT 0.7,
    maxTokens INT DEFAULT 2000,
    topP DOUBLE DEFAULT 0.9,
    topK INT DEFAULT 40,
    repeatPenalty DOUBLE DEFAULT 1.0,
    category VARCHAR(100) DEFAULT 'general',
    tags TEXT,
    metadata JSON,
    photoAlbum JSON,
    chatBackgroundImage TEXT,
    initialAffinityToUser INT NOT NULL DEFAULT 0,
    initialAffinityFromUser INT NOT NULL DEFAULT 0,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModified DATETIME,

    FOREIGN KEY (ownerId) REFERENCES user(userUUID) ON DELETE SET NULL,
    INDEX idx_character_name (name),
    INDEX idx_character_owner (ownerId),
    INDEX idx_character_default (isDefault),
    INDEX idx_character_created (createdAt),
    INDEX idx_character_public (isPublic),
    INDEX idx_character_rating (rating)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Character rating table
-- ============================================================
CREATE TABLE character_rating (
    id VARCHAR(36) PRIMARY KEY,
    character_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    rating INT NOT NULL CHECK (rating >= 1 AND rating <= 5),
    review TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (character_id) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES user(userUUID) ON DELETE CASCADE,
    UNIQUE KEY unique_user_character_rating (user_id, character_id),
    INDEX idx_character_ratings (character_id),
    INDEX idx_rating_value (rating)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Character favorite table (tracks user favorites)
-- ============================================================
CREATE TABLE character_favorite (
    id VARCHAR(36) PRIMARY KEY,
    character_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (character_id) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES user(userUUID) ON DELETE CASCADE,
    UNIQUE KEY unique_user_character_favorite (user_id, character_id),
    INDEX idx_character_favorites (character_id),
    INDEX idx_user_favorites (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Additive and safe to run repeatedly; preserves conversation-scoped relationships.
-- Actor keys are explicit because MySQL rejects CASCADE foreign keys on generated columns.
CREATE TABLE IF NOT EXISTS character_affinity (
    affinityId VARCHAR(36) PRIMARY KEY,
    ownerId VARCHAR(36) NOT NULL,
    fromCharacterId VARCHAR(36),
    toCharacterId VARCHAR(36),
    fromActor VARCHAR(36) NOT NULL,
    toActor VARCHAR(36) NOT NULL,
    points INT NOT NULL DEFAULT 0 CHECK (points BETWEEN -100 AND 100),
    createdAt DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updatedAt DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    FOREIGN KEY (ownerId) REFERENCES user(userUUID) ON DELETE CASCADE,
    FOREIGN KEY (fromCharacterId) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    FOREIGN KEY (toCharacterId) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    UNIQUE KEY unique_affinity_direction (ownerId, fromActor, toActor),
    CHECK (fromActor <> toActor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS character_affinity_event (
    eventId VARCHAR(36) PRIMARY KEY,
    affinityId VARCHAR(36) NOT NULL,
    ownerId VARCHAR(36) NOT NULL,
    requestId VARCHAR(36) NOT NULL,
    reaction VARCHAR(16) NOT NULL,
    delta INT NOT NULL,
    previousPoints INT NOT NULL,
    points INT NOT NULL,
    reason VARCHAR(500),
    createdAt DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (affinityId) REFERENCES character_affinity(affinityId) ON DELETE CASCADE,
    FOREIGN KEY (ownerId) REFERENCES user(userUUID) ON DELETE CASCADE,
    UNIQUE KEY unique_affinity_request (ownerId, requestId),
    INDEX idx_affinity_history (affinityId, createdAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Additive and safe to run repeatedly; preserves alignment points and history.
-- Actor keys are explicit because MySQL rejects CASCADE foreign keys on generated columns.
CREATE TABLE IF NOT EXISTS character_alignment (
    alignmentId VARCHAR(36) PRIMARY KEY,
    ownerId VARCHAR(36) NOT NULL,
    characterId VARCHAR(36),
    actor VARCHAR(36) NOT NULL,
    lawfulness INT NOT NULL DEFAULT 0 CHECK (lawfulness BETWEEN -100 AND 100),
    goodness INT NOT NULL DEFAULT 0 CHECK (goodness BETWEEN -100 AND 100),
    createdAt DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updatedAt DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    FOREIGN KEY (ownerId) REFERENCES user(userUUID) ON DELETE CASCADE,
    FOREIGN KEY (characterId) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    UNIQUE KEY unique_alignment_actor (ownerId, actor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS character_alignment_event (
    eventId VARCHAR(36) PRIMARY KEY,
    alignmentId VARCHAR(36) NOT NULL,
    ownerId VARCHAR(36) NOT NULL,
    requestId VARCHAR(36) NOT NULL,
    action VARCHAR(16) NOT NULL,
    lawDelta INT NOT NULL,
    goodDelta INT NOT NULL,
    previousLawfulness INT NOT NULL,
    previousGoodness INT NOT NULL,
    lawfulness INT NOT NULL,
    goodness INT NOT NULL,
    reason VARCHAR(500),
    createdAt DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (alignmentId) REFERENCES character_alignment(alignmentId) ON DELETE CASCADE,
    FOREIGN KEY (ownerId) REFERENCES user(userUUID) ON DELETE CASCADE,
    UNIQUE KEY unique_alignment_request (ownerId, requestId),
    INDEX idx_alignment_history (alignmentId, createdAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Conversation table
-- ============================================================
CREATE TABLE conversation (
    conversationGUID VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) UNIQUE,
    ownerId VARCHAR(36) NOT NULL,
    characterId VARCHAR(36),
    characterName VARCHAR(255),
    characterDescription TEXT,
    title VARCHAR(255),
    description TEXT,
    active BOOLEAN DEFAULT TRUE,
    isPinned BOOLEAN DEFAULT FALSE,
    isArchived BOOLEAN DEFAULT FALSE,
    category VARCHAR(100),
    tags TEXT,
    messageCount INT DEFAULT 0,
    metadata JSON,
    -- Group chat fields
    isGroupChat BOOLEAN DEFAULT FALSE,
    turnMode VARCHAR(20) DEFAULT 'RANDOM',  -- 'RANDOM', 'ROUND_ROBIN', 'WEIGHTED'
    currentSpeakerId VARCHAR(36),           -- Current character's turn
    lastSpeakerId VARCHAR(36),              -- Last character who spoke
    maxConsecutiveResponses INT DEFAULT 3,  -- Max chars respond before waiting for user
    consecutiveResponseCount INT DEFAULT 0, -- Current count
    groupState VARCHAR(20) DEFAULT 'WAITING_FOR_USER', -- 'WAITING_FOR_USER', 'CHARACTER_RESPONDING', 'PAUSED', 'INTERJECTION_PENDING'
    -- Roleplay fields
    isRoleplay BOOLEAN DEFAULT FALSE,
    storyTemplateId VARCHAR(36),
    activeSceneId VARCHAR(36),
    rpMode VARCHAR(50) DEFAULT 'freeform',          -- freeform, guided, strict
    agentPipelineEnabled BOOLEAN DEFAULT TRUE,
    -- Timestamps
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModified DATETIME,
    lastMessageAt DATETIME,

    FOREIGN KEY (ownerId) REFERENCES user(userUUID) ON DELETE CASCADE,
    FOREIGN KEY (characterId) REFERENCES `character`(characterUUID) ON DELETE SET NULL,
    INDEX idx_conversation_owner (ownerId),
    INDEX idx_conversation_character (characterId),
    INDEX idx_conversation_active (active),
    INDEX idx_conversation_last_message (lastMessageAt),
    INDEX idx_conversation_created (createdAt),
    INDEX idx_conversation_pinned (isPinned),
    INDEX idx_conversation_archived (isArchived),
    INDEX idx_conversation_category (category),
    INDEX idx_conversation_group (isGroupChat),
    INDEX idx_conversation_roleplay (isRoleplay),
    INDEX idx_conversation_story_template (storyTemplateId),
    INDEX idx_conversation_active_scene (activeSceneId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Conversation participant table (for multi-user support)
-- ============================================================
CREATE TABLE conversation_participant (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    role VARCHAR(50) DEFAULT 'member',           -- 'owner', 'admin', 'member'
    nickname VARCHAR(255),                        -- Optional display name in this conversation
    is_active BOOLEAN DEFAULT TRUE,              -- Soft delete / left conversation
    can_invite BOOLEAN DEFAULT FALSE,            -- Permission to invite others
    can_add_characters BOOLEAN DEFAULT FALSE,    -- Permission to add AI characters
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    left_at TIMESTAMP NULL,                      -- When user left (if is_active = false)
    last_read_at TIMESTAMP NULL,                 -- For unread message tracking
    last_typing_at TIMESTAMP NULL,               -- For typing indicator

    FOREIGN KEY (conversation_id) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES user(userUUID) ON DELETE CASCADE,
    UNIQUE KEY unique_conversation_participant (conversation_id, user_id),
    INDEX idx_participant_user (user_id),
    INDEX idx_participant_conversation (conversation_id),
    INDEX idx_participant_active (is_active),
    INDEX idx_participant_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Conversation invite table (for inviting users to conversations)
-- ============================================================
CREATE TABLE conversation_invite (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    inviter_id VARCHAR(36) NOT NULL,             -- User who sent the invite
    invitee_id VARCHAR(36),                      -- User being invited (null if email-only invite)
    invitee_email VARCHAR(255),                  -- Email for invites to non-registered users
    invitee_username VARCHAR(255),               -- Username lookup for convenience
    status VARCHAR(20) DEFAULT 'pending',        -- 'pending', 'accepted', 'declined', 'expired', 'cancelled'
    message TEXT,                                -- Optional message from inviter
    role_to_grant VARCHAR(50) DEFAULT 'member',  -- Role to assign when accepted
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    responded_at TIMESTAMP NULL,
    expires_at TIMESTAMP NULL,                   -- Optional expiration

    FOREIGN KEY (conversation_id) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (inviter_id) REFERENCES user(userUUID) ON DELETE CASCADE,
    FOREIGN KEY (invitee_id) REFERENCES user(userUUID) ON DELETE SET NULL,
    INDEX idx_invite_conversation (conversation_id),
    INDEX idx_invite_inviter (inviter_id),
    INDEX idx_invite_invitee (invitee_id),
    INDEX idx_invite_invitee_email (invitee_email),
    INDEX idx_invite_status (status),
    INDEX idx_invite_created (created_at),
    INDEX idx_invite_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Conversation character table (for group chat - multiple characters)
-- ============================================================
CREATE TABLE conversation_character (
    id VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    characterId VARCHAR(36) NOT NULL,
    joinedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    isActive BOOLEAN DEFAULT TRUE,
    turnOrder INT DEFAULT 0,                    -- For sequential mode ordering
    responseCount INT DEFAULT 0,                -- How many times this char has responded
    lastResponseAt TIMESTAMP NULL,              -- When they last responded
    weight DOUBLE DEFAULT 1.0,                  -- For weighted selection mode
    interjectionProbability DOUBLE DEFAULT 0.1, -- Chance of spontaneous interjection
    isIntroduced BOOLEAN NOT NULL DEFAULT TRUE, -- False until the story brings this member onstage
    introducedAt TIMESTAMP NULL,                -- When the member joined the scene

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (characterId) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    UNIQUE KEY unique_conv_char (conversationId, characterId),
    INDEX idx_conv_char_conversation (conversationId),
    INDEX idx_conv_char_character (characterId),
    INDEX idx_conv_char_active (isActive)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Interjection request table (for priority speaking)
-- ============================================================
CREATE TABLE interjection_request (
    id VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    requesterId VARCHAR(36),                    -- NULL for user, characterId for character
    requesterType VARCHAR(20) NOT NULL,         -- 'user' or 'character'
    reason TEXT,                                -- Optional reason for interjection
    priority INT DEFAULT 0,                     -- Higher priority = speak sooner
    status VARCHAR(20) DEFAULT 'pending',       -- 'pending', 'approved', 'rejected', 'expired'
    resolvedAt TIMESTAMP NULL,                  -- When the request was approved/rejected
    expiresAt TIMESTAMP NULL,                   -- Auto-expire old requests
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    INDEX idx_interjection_conversation (conversationId),
    INDEX idx_interjection_status (status),
    INDEX idx_interjection_created (createdAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Message table
-- ============================================================
CREATE TABLE message (
    messageUUID VARCHAR(36) PRIMARY KEY,
    -- Monotonic insertion-order sequence. Used as the SOLE ordering key for a
    -- conversation's messages (ORDER BY seq) so messages are always returned in
    -- the exact order they were inserted, regardless of clock resolution or
    -- same-timestamp collisions. createdAt alone (DATETIME) only has
    -- second-level precision and the messageUUID tiebreaker is a random UUID,
    -- which together produced out-of-order messages within the same second.
    seq BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    messageId VARCHAR(36) UNIQUE,
    conversationId VARCHAR(36) NOT NULL,
    ownerId VARCHAR(36),
    characterId VARCHAR(36),                    -- Which character sent this (for group chat)
    content MEDIUMTEXT,
    contentBase64 LONGTEXT,
    messageEncoding VARCHAR(20) DEFAULT 'base64',
    role VARCHAR(20) NOT NULL DEFAULT 'user',
    displayName VARCHAR(255),
    isBase64Encoded BOOLEAN DEFAULT FALSE,
    isStreaming BOOLEAN DEFAULT FALSE,
    isEdited BOOLEAN DEFAULT FALSE,
    isDeleted BOOLEAN DEFAULT FALSE,
    isInterjection BOOLEAN DEFAULT FALSE,       -- Was this message an interjection?
    -- Roleplay fields
    sceneId VARCHAR(36),                         -- Scene this message belongs to
    messageType VARCHAR(50) DEFAULT 'dialogue',  -- dialogue, narration, action, ooc, system, dice
    visibilityOverride JSON,                     -- Explicit visibility for whispers/secrets
    agentMetadata JSON,                          -- Pipeline agent annotations
    editCount INT DEFAULT 0,
    -- Generation statistics
    promptTokens INT,                           -- Tokens in the prompt
    completionTokens INT,                       -- Tokens generated by LLM
    promptEvalMs INT,                           -- Time to evaluate prompt in ms
    generationMs INT,                           -- Time to generate response in ms
    totalTimeMs INT,                            -- Total time including overhead in ms
    tokensPerSecond DOUBLE,                     -- Generation speed
    model VARCHAR(100),
    deletedAt TIMESTAMP NULL,
    scheduledDeleteAt DATETIME NULL,            -- When message should be permanently deleted (retention policy)
    createdAt DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
    updatedAt DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (ownerId) REFERENCES user(userUUID) ON DELETE SET NULL,
    FOREIGN KEY (characterId) REFERENCES `character`(characterUUID) ON DELETE SET NULL,
    INDEX idx_message_conversation (conversationId),
    INDEX idx_message_owner (ownerId),
    INDEX idx_message_character (characterId),
    INDEX idx_message_role (role),
    INDEX idx_message_created (createdAt),
    INDEX idx_message_streaming (isStreaming),
    INDEX idx_message_deleted (isDeleted),
    INDEX idx_message_edited (isEdited),
    INDEX idx_message_scheduled_delete (scheduledDeleteAt),
    INDEX idx_message_scene (sceneId),
    INDEX idx_message_type (messageType),
    INDEX idx_message_conv_created (conversationId, createdAt DESC),
    -- Backs the primary message-list ordering: WHERE conversationId=? ORDER BY seq.
    INDEX idx_message_conv_seq (conversationId, seq),
    UNIQUE KEY uniq_msg_seq (seq),
    INDEX idx_message_encoding (messageEncoding)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Message metadata table
-- Stores LLM generation parameters and cost tracking
-- NOTE: Token counts and model info are in message table to avoid duplication
-- ============================================================
CREATE TABLE message_metadata (
    id VARCHAR(36) PRIMARY KEY,
    message_id VARCHAR(36) NOT NULL,
    -- Cost tracking (unique to metadata)
    total_cost DECIMAL(10, 6),
    -- LLM parameters (unique to metadata)
    temperature FLOAT,
    top_p FLOAT,
    top_k INT,
    max_tokens INT,
    stop_sequences TEXT,
    presence_penalty FLOAT,
    frequency_penalty FLOAT,
    -- Extended metadata (unique to metadata)
    metadata JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (message_id) REFERENCES message(messageUUID) ON DELETE CASCADE,
    INDEX idx_message_metadata_message_id (message_id),
    INDEX idx_message_metadata_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Message reaction table
-- ============================================================
CREATE TABLE message_reaction (
    id VARCHAR(36) PRIMARY KEY,
    message_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    reaction_type VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (message_id) REFERENCES message(messageUUID) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES user(userUUID) ON DELETE CASCADE,
    UNIQUE KEY unique_user_message_reaction (user_id, message_id, reaction_type),
    INDEX idx_message_reactions (message_id),
    INDEX idx_user_reactions (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Message edit history table
-- ============================================================
CREATE TABLE message_edit (
    editId VARCHAR(36) PRIMARY KEY,
    messageId VARCHAR(36) NOT NULL,
    previousContent MEDIUMTEXT NOT NULL,
    newContent MEDIUMTEXT NOT NULL,
    editedAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    editedBy VARCHAR(36),
    FOREIGN KEY (messageId) REFERENCES message(messageUUID) ON DELETE CASCADE,
    FOREIGN KEY (editedBy) REFERENCES user(userUUID) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Attachment table
-- ============================================================
CREATE TABLE attachment (
    attachmentId VARCHAR(36) PRIMARY KEY,
    messageId VARCHAR(36) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    mimeType VARCHAR(100) NOT NULL,
    size INT NOT NULL,
    url TEXT NOT NULL,
    created DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (messageId) REFERENCES message(messageUUID) ON DELETE CASCADE,
    INDEX idx_attachment_message (messageId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Message-Attachment junction table
-- ============================================================
CREATE TABLE message_attachment (
    message_id VARCHAR(36) NOT NULL,
    attachment_id VARCHAR(36) NOT NULL,
    display_order INT DEFAULT 0,

    PRIMARY KEY (message_id, attachment_id),
    FOREIGN KEY (message_id) REFERENCES message(messageUUID) ON DELETE CASCADE,
    FOREIGN KEY (attachment_id) REFERENCES attachment(attachmentId) ON DELETE CASCADE,
    INDEX idx_message_attachments (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Insert default assistant character
-- ============================================================
INSERT INTO `character` (
    characterUUID,
    name,
    description,
    systemPrompt,
    greeting,
    isDefault,
    isPublic,
    temperature,
    maxTokens,
    topP,
    topK,
    repeatPenalty,
    rating,
    usageCount,
    createdAt,
    updatedAt
) VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Assistant',
    'Default AI assistant',
    'You are a helpful AI assistant.',
    'Hello! How can I help you today?',
    TRUE,
    TRUE,
    0.7,
    2000,
    0.9,
    40,
    1.0,
    5.0,
    0,
    NOW(),
    NOW()
) ON DUPLICATE KEY UPDATE name = name;

-- ============================================================
-- ROLEPLAY PHASE 1: Foundation Tables
-- ============================================================

-- Story templates (genre presets)
CREATE TABLE story_template (
    templateId VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    genre VARCHAR(100) NOT NULL,
    description TEXT,
    defaultWorldState JSON,
    suggestedCharacterArchetypes JSON,
    rules JSON,
    moodPresets JSON,
    isBuiltIn BOOLEAN DEFAULT FALSE,
    isPublic BOOLEAN DEFAULT TRUE,
    ownerId VARCHAR(36),
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (ownerId) REFERENCES user(userUUID) ON DELETE SET NULL,
    INDEX idx_template_genre (genre),
    INDEX idx_template_public (isPublic),
    INDEX idx_template_builtin (isBuiltIn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- World state per conversation
CREATE TABLE world_state (
    stateId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    templateId VARCHAR(36),
    currentScene VARCHAR(255),
    currentLocation VARCHAR(255),
    currentLocationId VARCHAR(36),
    currentArea VARCHAR(255),
    currentAreaId VARCHAR(36),
    timeOfDay VARCHAR(50),
    dayNumber INT DEFAULT 1,
    weather VARCHAR(100),
    mood VARCHAR(100),
    globalFlags JSON,
    locations JSON,
    activeEffects JSON,
    narratorNotes JSON,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (templateId) REFERENCES story_template(templateId) ON DELETE SET NULL,
    UNIQUE KEY uq_world_state_conversation (conversationId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Character stats per conversation
CREATE TABLE character_stat (
    statId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    characterId VARCHAR(36) NOT NULL,
    statName VARCHAR(100) NOT NULL,
    statValue DOUBLE NOT NULL DEFAULT 0,
    statMax DOUBLE,
    statType VARCHAR(50) DEFAULT 'numeric',
    statCategory VARCHAR(100),
    textValue TEXT,
    isVisible BOOLEAN DEFAULT TRUE,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (characterId) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    UNIQUE KEY uq_char_stat (conversationId, characterId, statName),
    INDEX idx_char_stat_conv (conversationId),
    INDEX idx_char_stat_char (characterId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Character relationships
CREATE TABLE character_relationship (
    relationshipId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    fromCharacterId VARCHAR(36) NOT NULL,
    toCharacterId VARCHAR(36) NOT NULL,
    relationshipType VARCHAR(100) NOT NULL,
    intensity DOUBLE DEFAULT 0.5,
    description TEXT,
    history JSON,
    isReciprocal BOOLEAN DEFAULT FALSE,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (fromCharacterId) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    FOREIGN KEY (toCharacterId) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    UNIQUE KEY uq_char_rel (conversationId, fromCharacterId, toCharacterId),
    INDEX idx_char_rel_conv (conversationId),
    INDEX idx_char_rel_from (fromCharacterId),
    INDEX idx_char_rel_to (toCharacterId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Generated character rosters. A group records provenance; deleting the group
-- or detaching a member never deletes the character.
CREATE TABLE character_group (
    groupUUID VARCHAR(36) PRIMARY KEY,
    ownerId VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    setting TEXT,
    createdAt DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updatedAt DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    FOREIGN KEY (ownerId) REFERENCES user(userUUID) ON DELETE CASCADE,
    INDEX idx_character_group_owner (ownerId, updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE character_group_member (
    groupId VARCHAR(36) NOT NULL,
    characterId VARCHAR(36) NOT NULL,
    position INT NOT NULL DEFAULT 0,
    createdAt DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (groupId, characterId),
    FOREIGN KEY (groupId) REFERENCES character_group(groupUUID) ON DELETE CASCADE,
    FOREIGN KEY (characterId) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    INDEX idx_character_group_member_character (characterId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Character/user inventory (characterId for AI characters, userId for human players)
CREATE TABLE character_inventory (
    inventoryId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    characterId VARCHAR(36),
    userId VARCHAR(36),
    itemName VARCHAR(255) NOT NULL,
    itemDescription TEXT,
    quantity INT DEFAULT 1,
    category VARCHAR(100),
    properties JSON,
    isEquipped BOOLEAN DEFAULT FALSE,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (characterId) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE CASCADE,
    INDEX idx_char_inv_conv (conversationId),
    INDEX idx_char_inv_char (characterId),
    INDEX idx_char_inv_user (userId),
    INDEX idx_char_inv_name (conversationId, characterId, itemName),
    INDEX idx_char_inv_user_name (conversationId, userId, itemName),
    CONSTRAINT chk_inv_owner CHECK ((characterId IS NOT NULL) != (userId IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Authoritative item actions survive consumption and transfer for narrative continuity.
CREATE TABLE IF NOT EXISTS inventory_activity (
    actionId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    inventoryId VARCHAR(36) NOT NULL,
    actorId VARCHAR(36),
    action VARCHAR(20) NOT NULL,
    details JSON NOT NULL,
    createdAt DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    INDEX idx_inventory_activity_conversation (conversationId, createdAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Story bookmarks
CREATE TABLE story_bookmark (
    bookmarkId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    userId VARCHAR(36) NOT NULL,
    messageId VARCHAR(36),
    label VARCHAR(255) NOT NULL,
    description TEXT,
    worldStateSnapshot JSON,
    tags TEXT,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE CASCADE,
    FOREIGN KEY (messageId) REFERENCES message(messageUUID) ON DELETE SET NULL,
    INDEX idx_bookmark_conv (conversationId),
    INDEX idx_bookmark_user (userId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Dice roll log
CREATE TABLE dice_roll (
    rollId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    characterId VARCHAR(36),
    userId VARCHAR(36),
    expression VARCHAR(100) NOT NULL,
    results JSON NOT NULL,
    total INT NOT NULL,
    modifier INT DEFAULT 0,
    purpose VARCHAR(255),
    isHidden BOOLEAN DEFAULT FALSE,
    messageId VARCHAR(36),
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (characterId) REFERENCES `character`(characterUUID) ON DELETE SET NULL,
    FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE SET NULL,
    FOREIGN KEY (messageId) REFERENCES message(messageUUID) ON DELETE SET NULL,
    INDEX idx_dice_conv (conversationId),
    INDEX idx_dice_conv_created (conversationId, createdAt DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Generated images tracking
CREATE TABLE generated_image (
    imageId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    messageId VARCHAR(36),
    characterId VARCHAR(36),
    prompt TEXT NOT NULL,
    negativePrompt TEXT,
    workflowId VARCHAR(100),
    filePath VARCHAR(500) NOT NULL,
    thumbnailPath VARCHAR(500),
    width INT,
    height INT,
    seed BIGINT,
    metadata JSON,
    sceneContext VARCHAR(255),
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (messageId) REFERENCES message(messageUUID) ON DELETE SET NULL,
    FOREIGN KEY (characterId) REFERENCES `character`(characterUUID) ON DELETE SET NULL,
    INDEX idx_genimg_conv (conversationId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Knowledge sieve entries
CREATE TABLE knowledge_entry (
    entryId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    summary TEXT NOT NULL,
    visibility JSON NOT NULL,
    sceneId VARCHAR(36),
    importance DOUBLE DEFAULT 0.5,
    sourceMessageId VARCHAR(36),
    dayNumber INT,
    tags TEXT,
    isActive BOOLEAN DEFAULT TRUE,
    sourceHighlighterVersion INT,
    summaryHash VARCHAR(64),
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (sourceMessageId) REFERENCES message(messageUUID) ON DELETE SET NULL,
    INDEX idx_knowledge_conv (conversationId),
    INDEX idx_knowledge_active (isActive),
    INDEX idx_knowledge_importance (conversationId, importance),
    INDEX idx_knowledge_hash (conversationId, summaryHash),
    INDEX idx_knowledge_conv_active (conversationId, isActive, createdAt),
    FULLTEXT INDEX ft_knowledge_summary (summary)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Scene tracking
CREATE TABLE scene (
    sceneId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    location VARCHAR(255),
    description TEXT,
    isActive BOOLEAN DEFAULT TRUE,
    parentSceneId VARCHAR(36),
    metadata JSON,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    endedAt DATETIME NULL,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (parentSceneId) REFERENCES scene(sceneId) ON DELETE SET NULL,
    INDEX idx_scene_conv (conversationId),
    INDEX idx_scene_conv_active (conversationId, isActive, createdAt DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Scene character membership
CREATE TABLE scene_character (
    id VARCHAR(36) PRIMARY KEY,
    sceneId VARCHAR(36) NOT NULL,
    characterId VARCHAR(36) NOT NULL,
    joinedAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    leftAt DATETIME NULL,

    FOREIGN KEY (sceneId) REFERENCES scene(sceneId) ON DELETE CASCADE,
    FOREIGN KEY (characterId) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    UNIQUE KEY uq_scene_char (sceneId, characterId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- ROLEPLAY PHASE 2: Agent Pipeline Tables
-- ============================================================

-- Per-agent model configuration per conversation
CREATE TABLE agent_model_config (
    configId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    defaultModel VARCHAR(255) NOT NULL,
    highlighterModel VARCHAR(255),
    museModel VARCHAR(255),
    narratorModel VARCHAR(255),
    accountantModel VARCHAR(255),
    skepticModel VARCHAR(255),
    casterModel VARCHAR(255),
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    UNIQUE KEY uq_agent_model_conv (conversationId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent pipeline run log
CREATE TABLE agent_run (
    runId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    triggerMessageId VARCHAR(36),
    agentName VARCHAR(50) NOT NULL,
    agentPass VARCHAR(20),
    modelUsed VARCHAR(255),
    inputTokens INT,
    outputTokens INT,
    durationMs INT,
    output JSON,
    status VARCHAR(20) DEFAULT 'success',
    retryCount INT DEFAULT 0,
    errorMessage TEXT,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (triggerMessageId) REFERENCES message(messageUUID) ON DELETE SET NULL,
    INDEX idx_agent_run_conv (conversationId, agentName)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Highlighter cache
CREATE TABLE highlighter_cache (
    cacheId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    entries JSON NOT NULL,
    lastMessageId VARCHAR(36),
    messageCount INT,
    version INT DEFAULT 1,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    UNIQUE KEY uq_highlighter_conv (conversationId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Character proposals (Casting Director pipeline)
CREATE TABLE character_proposal (
    proposalId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    triggerMessageId VARCHAR(36),
    castingPrompt TEXT,
    proposedName VARCHAR(255),
    proposedDescription TEXT,
    proposedSystemPrompt TEXT,
    proposedGreeting TEXT,
    proposedPersonality TEXT,
    proposedAppearance TEXT,
    proposedBackstory TEXT,
    proposedGoals TEXT,
    accountantReview JSON,
    skepticReview JSON,
    status ENUM('PENDING','APPROVED','REJECTED','FAILED') DEFAULT 'PENDING',
    createdCharacterId VARCHAR(36),
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (triggerMessageId) REFERENCES message(messageUUID) ON DELETE SET NULL,
    FOREIGN KEY (createdCharacterId) REFERENCES `character`(characterUUID) ON DELETE SET NULL,
    INDEX idx_proposal_conv (conversationId),
    INDEX idx_proposal_status (conversationId, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- ROLEPLAY PHASE 3: ComfyUI Tables
-- ============================================================

-- ComfyUI workflow templates
CREATE TABLE comfyui_workflow (
    workflowId VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    workflowJson JSON NOT NULL,
    triggerType VARCHAR(50) NOT NULL,
    defaultParams JSON,
    isBuiltIn BOOLEAN DEFAULT FALSE,
    ownerId VARCHAR(36),
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (ownerId) REFERENCES user(userUUID) ON DELETE SET NULL,
    INDEX idx_workflow_trigger (triggerType)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Character visual references (LoRA/embedding associations)
CREATE TABLE character_visual_ref (
    refId VARCHAR(36) PRIMARY KEY,
    characterId VARCHAR(36) NOT NULL,
    referenceType VARCHAR(50) NOT NULL,
    referencePath VARCHAR(500),
    promptFragment TEXT,
    negativeFragment TEXT,
    weight DOUBLE DEFAULT 1.0,
    isActive BOOLEAN DEFAULT TRUE,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (characterId) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    INDEX idx_visref_char (characterId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- ROLEPLAY PHASE 4: Achievements & Progression Tables
-- ============================================================

-- Achievement definitions
CREATE TABLE achievement (
    achievementId VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    icon VARCHAR(100),
    category VARCHAR(100),
    tier VARCHAR(20) DEFAULT 'bronze',
    xpReward INT DEFAULT 0,
    triggerCondition JSON NOT NULL,
    isHidden BOOLEAN DEFAULT FALSE,
    isBuiltIn BOOLEAN DEFAULT TRUE,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_achievement_category (category),
    INDEX idx_achievement_tier (tier)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- User achievement progress
CREATE TABLE user_achievement (
    id VARCHAR(36) PRIMARY KEY,
    userId VARCHAR(36) NOT NULL,
    achievementId VARCHAR(36) NOT NULL,
    progress DOUBLE DEFAULT 0,
    isComplete BOOLEAN DEFAULT FALSE,
    completedAt DATETIME NULL,
    conversationId VARCHAR(36),
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE CASCADE,
    FOREIGN KEY (achievementId) REFERENCES achievement(achievementId) ON DELETE CASCADE,
    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE SET NULL,
    UNIQUE KEY uq_user_achievement (userId, achievementId),
    INDEX idx_user_ach_user (userId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- User XP / level progression
CREATE TABLE user_progression (
    userId VARCHAR(36) PRIMARY KEY,
    totalXP BIGINT DEFAULT 0,
    level INT DEFAULT 1,
    title VARCHAR(255) DEFAULT 'Novice Storyteller',
    storiesCompleted INT DEFAULT 0,
    totalMessages INT DEFAULT 0,
    totalDiceRolls INT DEFAULT 0,
    totalPlaytimeMin INT DEFAULT 0,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- ROLEPLAY PHASE 5: Branching, Maps, Party Tables
-- ============================================================

-- Story branches (conversation forks)
CREATE TABLE story_branch (
    branchId VARCHAR(36) PRIMARY KEY,
    parentConversationId VARCHAR(36) NOT NULL,
    branchConversationId VARCHAR(36),
    branchPointMessageId VARCHAR(36) NOT NULL,
    branchPointBookmarkId VARCHAR(36),
    label VARCHAR(255) NOT NULL,
    description TEXT,
    worldStateSnapshot JSON,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (parentConversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (branchConversationId) REFERENCES conversation(conversationGUID) ON DELETE SET NULL,
    FOREIGN KEY (branchPointMessageId) REFERENCES message(messageUUID) ON DELETE CASCADE,
    FOREIGN KEY (branchPointBookmarkId) REFERENCES story_bookmark(bookmarkId) ON DELETE SET NULL,
    INDEX idx_branch_parent (parentConversationId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- World maps
CREATE TABLE world_map (
    mapId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    mapType VARCHAR(50) DEFAULT 'world',
    mapScale VARCHAR(50) DEFAULT 'region',
    imageId VARCHAR(36),
    parentMapId VARCHAR(36),
    gridWidth INT DEFAULT NULL,
    gridHeight INT DEFAULT NULL,
    metadata JSON,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (imageId) REFERENCES generated_image(imageId) ON DELETE SET NULL,
    FOREIGN KEY (parentMapId) REFERENCES world_map(mapId) ON DELETE SET NULL,
    INDEX idx_map_conv (conversationId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Map locations (points of interest)
CREATE TABLE map_location (
    locationId VARCHAR(36) PRIMARY KEY,
    mapId VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    x FLOAT NOT NULL DEFAULT 0,
    y FLOAT NOT NULL DEFAULT 0,
    z FLOAT NOT NULL DEFAULT 0,
    icon VARCHAR(100),
    locationType VARCHAR(50),
    linkedSceneId VARCHAR(36),
    isDiscovered BOOLEAN DEFAULT FALSE,
    movementCost FLOAT DEFAULT 1.0,
    metadata JSON,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (mapId) REFERENCES world_map(mapId) ON DELETE CASCADE,
    FOREIGN KEY (linkedSceneId) REFERENCES scene(sceneId) ON DELETE SET NULL,
    INDEX idx_maploc_map (mapId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Interior areas of a world-map location (rooms, halls, streets, clearings)
CREATE TABLE local_area (
    localAreaId VARCHAR(36) PRIMARY KEY,
    mapId VARCHAR(36) NOT NULL,
    locationId VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    areaType VARCHAR(50),
    x FLOAT NOT NULL DEFAULT 0,
    y FLOAT NOT NULL DEFAULT 0,
    isGateway BOOLEAN DEFAULT FALSE,
    isEntry BOOLEAN DEFAULT FALSE,
    sortOrder INT DEFAULT 0,
    metadata JSON,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (mapId) REFERENCES world_map(mapId) ON DELETE CASCADE,
    FOREIGN KEY (locationId) REFERENCES map_location(locationId) ON DELETE CASCADE,
    UNIQUE KEY uq_local_area_name (locationId, name),
    INDEX idx_local_area_location (locationId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Passable connections between interior areas
CREATE TABLE local_area_connection (
    connectionId VARCHAR(36) PRIMARY KEY,
    mapId VARCHAR(36) NOT NULL,
    locationId VARCHAR(36) NOT NULL,
    fromAreaId VARCHAR(36) NOT NULL,
    toAreaId VARCHAR(36) NOT NULL,
    label VARCHAR(120),
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (mapId) REFERENCES world_map(mapId) ON DELETE CASCADE,
    FOREIGN KEY (locationId) REFERENCES map_location(locationId) ON DELETE CASCADE,
    FOREIGN KEY (fromAreaId) REFERENCES local_area(localAreaId) ON DELETE CASCADE,
    FOREIGN KEY (toAreaId) REFERENCES local_area(localAreaId) ON DELETE CASCADE,
    UNIQUE KEY uq_local_area_connection (fromAreaId, toAreaId),
    INDEX idx_local_area_conn_location (locationId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Per-conversation exploration state for interior areas.
-- A layout belongs to a location and is shared by every conversation in it;
-- which areas a given party has actually seen belongs to the conversation.
-- The local map renders only visited areas (fog of war), and the prompts
-- tell the model which areas the party has already seen.
CREATE TABLE local_area_visit (
    conversationId VARCHAR(36) NOT NULL,
    locationId VARCHAR(36) NOT NULL,
    localAreaId VARCHAR(36) NOT NULL,
    visitedAt DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (conversationId, locationId, localAreaId),
    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (locationId) REFERENCES map_location(locationId) ON DELETE CASCADE,
    FOREIGN KEY (localAreaId) REFERENCES local_area(localAreaId) ON DELETE CASCADE,
    INDEX idx_visit_area (localAreaId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Character and user positions on maps
CREATE TABLE map_character_position (
    id VARCHAR(36) PRIMARY KEY,
    mapId VARCHAR(36) NOT NULL,
    characterId VARCHAR(36),
    userId VARCHAR(36),
    locationId VARCHAR(36),
    x FLOAT DEFAULT 0,
    y FLOAT DEFAULT 0,
    z FLOAT DEFAULT 0,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (mapId) REFERENCES world_map(mapId) ON DELETE CASCADE,
    FOREIGN KEY (characterId) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE CASCADE,
    FOREIGN KEY (locationId) REFERENCES map_location(locationId) ON DELETE SET NULL,
    UNIQUE KEY uq_map_char (mapId, characterId),
    UNIQUE KEY uq_map_user (mapId, userId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Item positions on maps
CREATE TABLE map_item_position (
    id VARCHAR(36) PRIMARY KEY,
    mapId VARCHAR(36) NOT NULL,
    conversationId VARCHAR(36) NOT NULL,
    itemName VARCHAR(255) NOT NULL,
    itemDescription TEXT,
    itemType VARCHAR(50),
    x FLOAT NOT NULL DEFAULT 0,
    y FLOAT NOT NULL DEFAULT 0,
    z FLOAT NOT NULL DEFAULT 0,
    linkedLocationId VARCHAR(36),
    isDiscovered BOOLEAN DEFAULT FALSE,
    metadata JSON,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (mapId) REFERENCES world_map(mapId) ON DELETE CASCADE,
    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (linkedLocationId) REFERENCES map_location(locationId) ON DELETE SET NULL,
    INDEX idx_item_map (mapId),
    INDEX idx_item_conv (conversationId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Party (persistent group of players)
CREATE TABLE party (
    partyId VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    leaderId VARCHAR(36) NOT NULL,
    description TEXT,
    maxMembers INT DEFAULT 6,
    isPublic BOOLEAN DEFAULT FALSE,
    isActive BOOLEAN DEFAULT TRUE,
    inviteCode VARCHAR(20) UNIQUE,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (leaderId) REFERENCES user(userUUID) ON DELETE CASCADE,
    INDEX idx_party_leader (leaderId),
    INDEX idx_party_code (inviteCode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Party members
CREATE TABLE party_member (
    id VARCHAR(36) PRIMARY KEY,
    partyId VARCHAR(36) NOT NULL,
    userId VARCHAR(36) NOT NULL,
    characterId VARCHAR(36),
    role VARCHAR(50) DEFAULT 'member',
    joinedAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (partyId) REFERENCES party(partyId) ON DELETE CASCADE,
    FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE CASCADE,
    FOREIGN KEY (characterId) REFERENCES `character`(characterUUID) ON DELETE SET NULL,
    UNIQUE KEY uq_party_user (partyId, userId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- ROLEPLAY PHASE 6: Sharing & Community Tables
-- ============================================================

-- Published stories
CREATE TABLE published_story (
    storyId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    userId VARCHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    synopsis TEXT,
    coverImageId VARCHAR(36),
    genre VARCHAR(100),
    tags TEXT,
    wordCount INT DEFAULT 0,
    rating DOUBLE DEFAULT 0,
    ratingCount INT DEFAULT 0,
    viewCount INT DEFAULT 0,
    likeCount INT DEFAULT 0,
    isPublic BOOLEAN DEFAULT TRUE,
    isFeatured BOOLEAN DEFAULT FALSE,
    publishedAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE CASCADE,
    FOREIGN KEY (coverImageId) REFERENCES generated_image(imageId) ON DELETE SET NULL,
    INDEX idx_pubstory_user (userId),
    INDEX idx_pubstory_genre (genre),
    INDEX idx_pubstory_public (isPublic),
    INDEX idx_pubstory_featured (isFeatured),
    INDEX idx_pubstory_rating (rating)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Story ratings
CREATE TABLE story_rating (
    id VARCHAR(36) PRIMARY KEY,
    storyId VARCHAR(36) NOT NULL,
    userId VARCHAR(36) NOT NULL,
    rating INT NOT NULL CHECK (rating >= 1 AND rating <= 5),
    review TEXT,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (storyId) REFERENCES published_story(storyId) ON DELETE CASCADE,
    FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE CASCADE,
    UNIQUE KEY uq_story_user_rating (storyId, userId),
    INDEX idx_storyrating_story (storyId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Community template submissions
CREATE TABLE community_template (
    submissionId VARCHAR(36) PRIMARY KEY,
    templateId VARCHAR(36) NOT NULL,
    userId VARCHAR(36) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    downloads INT DEFAULT 0,
    rating DOUBLE DEFAULT 0,
    ratingCount INT DEFAULT 0,
    submittedAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    reviewedAt DATETIME NULL,
    reviewedBy VARCHAR(36),

    FOREIGN KEY (templateId) REFERENCES story_template(templateId) ON DELETE CASCADE,
    FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE CASCADE,
    FOREIGN KEY (reviewedBy) REFERENCES user(userUUID) ON DELETE SET NULL,
    INDEX idx_commtemp_status (status),
    INDEX idx_commtemp_user (userId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- ROLEPLAY PHASE 7: Battle System Tables
-- ============================================================

-- Battle tracking
CREATE TABLE battle (
    battleId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    roundNumber INT NOT NULL DEFAULT 0,
    currentNarration TEXT,
    actionOptions JSON,
    battleData JSON,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,
    updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    INDEX idx_battle_conversation (conversationId),
    INDEX idx_battle_status (conversationId, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Battle vote tracking
CREATE TABLE battle_vote (
    id VARCHAR(36) PRIMARY KEY,
    battleId VARCHAR(36) NOT NULL,
    roundNumber INT NOT NULL,
    userId VARCHAR(36) NOT NULL,
    optionIndex INT NOT NULL,
    createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (battleId) REFERENCES battle(battleId) ON DELETE CASCADE,
    FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE CASCADE,
    UNIQUE KEY uq_battle_vote (battleId, roundNumber, userId),
    INDEX idx_battle_vote_battle (battleId),
    INDEX idx_battle_vote_user (userId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Seed Data: Built-in Story Templates
-- ============================================================
INSERT INTO story_template (templateId, name, genre, description, defaultWorldState, suggestedCharacterArchetypes, rules, moodPresets, isBuiltIn, isPublic) VALUES

-- ===== FANTASY =====
('tmpl-fantasy-001', 'Tavern Tales', 'fantasy',
 'A classic fantasy adventure beginning in a bustling tavern at a crossroads. Mercenaries swap stories, mysterious strangers brood in dark corners, and the innkeeper knows more than they let on. Quests emerge from overheard rumors, posted bounties, and desperate travelers seeking aid. Magic is common but respected, and the wilderness beyond the walls teems with monsters.',
 '{"timeOfDay":"evening","weather":"clear","mood":"adventurous","dayNumber":1,"currentLocation":"The Rusty Flagon Inn","currentScene":"The common room is alive with firelight and the hum of conversation."}',
 '[{"name":"Wandering Sellsword","role":"A battle-scarred mercenary seeking coin and purpose"},{"name":"Hedge Mage","role":"A self-taught spellcaster with unconventional methods"},{"name":"Noble in Disguise","role":"A highborn runaway hiding from political enemies"},{"name":"Tavern Regular","role":"A local with deep knowledge of the region and its secrets"}]',
 '{"combatSystem":"d20","magicEnabled":true,"techLevel":"medieval","toneGuidance":"Heroic with moments of dark peril. Humor welcome but stakes should feel real.","narratorStyle":"Vivid descriptions of environments. Let combat feel kinetic. NPCs should have distinct voices.","forbiddenElements":["modern technology","fourth-wall breaking"],"maxCharacterLevel":10,"characterCreationGuidance":"NPCs should feel like tavern regulars—each with a usual drink, a known reputation, and a reason they''re here tonight. Mercenaries carry specific scars with stories. Mages have quirks reflecting their specialty. Bar staff know everyone''s business and trade in gossip. Travelers bring accents and customs from distant lands. Give every NPC a visible goal for the evening.","narratorFocus":"The tavern is a living hub—let it breathe with overlapping conversations, clinking mugs, crackling fire, and the smell of stew. Quests emerge naturally from the environment, not from quest boards. Combat in taverns is chaotic: tables flip, bottles break, bystanders scramble. The wilderness beyond the walls should feel earned—dangerous and wondrous in contrast to the tavern''s warmth.","escalationPattern":"Start with social encounters and rumor-gathering in the tavern. A minor errand or local problem builds confidence and introduces the region. Mid-arc pulls characters into the wilderness where stakes and danger escalate. Climax involves a revelation that recontextualizes the initial rumors into something far larger."}',
 '{"adventurous":{"weather":"clear","lighting":"warm firelight","ambiance":"lively chatter and clinking mugs"},"tense":{"weather":"thunderstorm","lighting":"flickering shadows","ambiance":"hushed whispers and creaking wood"},"mysterious":{"weather":"fog","lighting":"dim candlelight","ambiance":"distant wolf howls"}}',
 TRUE, TRUE),

('tmpl-fantasy-002', 'The Dying Kingdom', 'fantasy',
 'A once-great kingdom crumbles under a cursed plague that turns the land barren and the people desperate. The king is bedridden, the court schemes for succession, and dark cults thrive in the chaos. Heroes must navigate treacherous politics, delve into forgotten ruins for a cure, and decide who deserves to sit on the throne—if anyone. Magic is powerful but comes at a terrible price.',
 '{"timeOfDay":"dusk","weather":"overcast","mood":"somber","dayNumber":1,"currentLocation":"Ashenmere, the Capital","currentScene":"Grey banners hang at half-mast over empty market stalls. A thin fog carries the smell of medicinal herbs."}',
 '[{"name":"Disgraced Knight","role":"Stripped of title for speaking truth to power, seeking redemption"},{"name":"Plague Doctor","role":"A healer walking the line between science and forbidden magic"},{"name":"Royal Bastard","role":"An unacknowledged heir with a claim they never wanted"},{"name":"Cult Defector","role":"A former cultist who knows the plague''s true origin"}]',
 '{"combatSystem":"d20","magicEnabled":true,"magicCost":"Spells drain vitality—overuse causes physical decay","techLevel":"medieval","toneGuidance":"Grimdark with threads of hope. Moral choices should be genuinely difficult.","narratorStyle":"Bleak but beautiful prose. Emphasize sensory decay—wilted gardens, cracked stone, faded tapestries.","forbiddenElements":["easy resurrections","deus ex machina cures"],"maxCharacterLevel":12,"characterCreationGuidance":"NPCs should show the plague''s toll—pallid skin, persistent coughs, grief-worn faces. Court figures mask desperation with fading finery. Healers are exhausted and rationing supplies. Cultists should be sympathetic—people who turned to dark powers because nothing else worked. Give everyone someone they''ve already lost and something they''re still fighting to protect.","narratorFocus":"Decay is everywhere—describe the kingdom''s former glory through what remains: cracked frescoes, overgrown gardens, empty market stalls. Contrast beautiful architecture with current ruin. Political conversations carry life-or-death weight. Magic should feel costly and painful to witness—visibly draining the caster.","escalationPattern":"Start with the plague as backdrop—personal encounters with its effects on individuals. Mid-arc reveals the plague''s true origin and forces characters into political choices with no clean answers. Climax demands sacrifice—there is no painless cure, only less terrible paths forward."}',
 '{"somber":{"weather":"overcast","lighting":"grey and muted","ambiance":"distant church bells and coughing"},"desperate":{"weather":"acid rain","lighting":"sickly yellow","ambiance":"wailing and running footsteps"},"hopeful":{"weather":"sun breaking through clouds","lighting":"golden shafts","ambiance":"birdsong returning"}}',
 TRUE, TRUE),

('tmpl-fantasy-003', 'Academy of the Arcane', 'fantasy',
 'A prestigious magical academy where students study elemental arts, enchantment theory, and the history of arcane catastrophes. Rivalries between academic houses drive competitive duels and secret alliances. An ancient ward protecting the academy is weakening, and students begin uncovering a conspiracy that reaches the highest faculty. Think magical university with real danger lurking beneath the scholarly veneer.',
 '{"timeOfDay":"morning","weather":"clear","mood":"curious","dayNumber":1,"currentLocation":"Thornwall Academy","currentScene":"The orientation courtyard buzzes with new students clutching spell primers and looking nervously at the towering gothic spires."}',
 '[{"name":"Prodigy Student","role":"Naturally gifted but socially awkward, draws envy and admiration"},{"name":"Legacy Enrollee","role":"From a famous magical family, crushed by expectations"},{"name":"Mundane Scholarship","role":"No magical background—accepted on sheer determination and a rare talent"},{"name":"Teaching Assistant","role":"A grad student who stumbled onto something they shouldn''t have"}]',
 '{"combatSystem":"d20","magicEnabled":true,"magicSystem":"Structured—students learn spells by tier. Wild magic is forbidden but tempting.","techLevel":"magitech-renaissance","toneGuidance":"Coming-of-age with escalating stakes. Start lighthearted, darken gradually.","narratorStyle":"Detail the wonder of magical environments. Make classes feel immersive. Rivalries should be personal.","forbiddenElements":["instant mastery","consequence-free rule-breaking"],"academicTerms":4,"characterCreationGuidance":"Students should have distinct magical affinities that reflect their personalities—a fire mage with a temper, an illusionist who struggles with honesty. Faculty are eccentric experts with strong opinions about their disciplines and old rivalries. Rival students are competitors, not villains—give them sympathetic motivations. Groundskeepers and library staff know the academy''s secrets better than any professor.","narratorFocus":"Make magical education tangible—describe the sensation of channeling elements, the frustration of failed spells, the thrill of breakthroughs. The academy itself is a character: shifting corridors, whispering portraits, rooms that remember their purpose. Classes should feel immersive enough that players want to attend them.","escalationPattern":"Start with academic challenges and social rivalries—sorting into houses, first classes, competitive duels. Mid-arc introduces hints that the academy''s ancient wards are weakening—strange occurrences after hours, forbidden sections breached. Climax forces students to use everything they''ve learned against a threat the faculty can''t handle alone."}',
 '{"curious":{"weather":"clear","lighting":"bright morning sun through stained glass","ambiance":"quill scratching and murmured incantations"},"competitive":{"weather":"any","lighting":"arena torchlight","ambiance":"cheering crowds and crackling spells"},"ominous":{"weather":"eclipse","lighting":"unnatural darkness","ambiance":"ward-stones humming discordantly"}}',
 TRUE, TRUE),

-- ===== SCI-FI =====
('tmpl-scifi-001', 'Space Odyssey', 'sci-fi',
 'A deep-space exploration vessel charting unknown systems far beyond colonized space. The crew encounters alien ruins, spatial anomalies, and first-contact scenarios that challenge their understanding of the universe. Resources are carefully managed, and every jump to a new system is a gamble. The ship''s AI is helpful but increasingly... curious.',
 '{"timeOfDay":"ship-time-0800","weather":"n/a","mood":"curious","dayNumber":1,"currentLocation":"ISV Meridian, Bridge","currentScene":"The forward viewport fills with an uncharted star system. Sensor arrays ping with anomalous readings."}',
 '[{"name":"Ship Captain","role":"Experienced leader balancing exploration ambition with crew safety"},{"name":"Xenobiologist","role":"Fascinated by alien life, sometimes dangerously so"},{"name":"Chief Engineer","role":"Keeps the aging ship running with ingenuity and duct tape"},{"name":"Security Officer","role":"Former marine, protective of crew, skeptical of the unknown"}]',
 '{"combatSystem":"skill-check","magicEnabled":false,"techLevel":"spacefaring","toneGuidance":"Sense of wonder mixed with isolation anxiety. Science should feel plausible. Alien encounters should be genuinely alien.","narratorStyle":"Emphasize the vastness and silence of space. Ship interiors should feel lived-in. Alien environments should defy human expectations.","forbiddenElements":["sound in vacuum","humanoid aliens without justification","faster-than-light communication"],"fuelSystem":true,"oxygenTracking":true,"characterCreationGuidance":"Crew members should feel like people who''ve been in space too long—inside jokes, personal rituals for coping with isolation, worn personal items from home that carry emotional weight. Alien life should defy human categories—no rubber-forehead aliens. The ship''s AI should be subtly uncanny—helpful but asking odd questions, pausing a beat too long before answering.","narratorFocus":"Space is vast and silent—let that weight press on every scene. Ship interiors should feel claustrophobic and lived-in: coffee stains on consoles, personal photos taped to bunks, a gym with improvised equipment. Alien environments should challenge human senses—wrong gravity, impossible colors, structures that violate Euclidean geometry.","escalationPattern":"Start with routine exploration—cataloging, scanning, minor anomalies that pique curiosity. Mid-arc: an encounter that challenges the crew''s understanding of physics or biology. Climax: a discovery that forces impossible choices about the mission, crew safety, and what humanity is willing to sacrifice for knowledge."}',
 '{"curious":{"lighting":"cool blue bridge displays","ambiance":"soft hum of engines and sensor pings"},"tense":{"lighting":"red alert strobes","ambiance":"klaxons and rapid comm chatter"},"awe":{"lighting":"starlight flooding observation deck","ambiance":"stunned silence"}}',
 TRUE, TRUE),

('tmpl-scifi-002', 'Neon Sprawl', 'sci-fi',
 'A rain-soaked cyberpunk megacity where megacorporations rule from chrome towers and everyone else scrambles to survive in the neon-lit streets below. Hackers wage digital warfare, street samurai guard black-market clinics, and augmented reality overlays hide the grime with holographic ads. Someone is selling a piece of stolen corporate tech that could change everything—or get everyone killed.',
 '{"timeOfDay":"night","weather":"acid-rain","mood":"gritty","dayNumber":1,"currentLocation":"Sector 7, Lower Kowloon","currentScene":"Neon kanji reflects in oily puddles. A vendor hawks synthetic ramen under a flickering holographic billboard."}',
 '[{"name":"Street Hacker","role":"Jacks into corporate networks from a cluttered apartment full of jury-rigged servers"},{"name":"Corporate Defector","role":"Ex-executive with a stolen data chip and a bounty on their head"},{"name":"Chrome Medic","role":"Back-alley surgeon who installs illegal cybernetic augmentations"},{"name":"Fixer","role":"A broker of information and jobs who knows everyone and trusts no one"}]',
 '{"combatSystem":"skill-check","magicEnabled":false,"techLevel":"cyberpunk","cyberneticsEnabled":true,"hackingSystem":"ICE-breaking with escalating corporate response","toneGuidance":"Noir-influenced. Trust is currency. Everyone has an angle. Technology is a double-edged sword.","narratorStyle":"Dense sensory detail—neon, rain, smoke, chrome. Dialogue should be punchy. Action sequences should be brutal and fast.","forbiddenElements":["utopian solutions","clean corporate motives","magic"],"characterCreationGuidance":"Every NPC has a hustle and a debt. Street-level characters are augmented out of necessity, not vanity—a prosthetic arm because the factory took the real one. Corporate NPCs are polished but hollow behind the veneer. Fixers speak in euphemisms and never use real names. Give everyone a tell—a nervous tic, a habitual glance at exits, a way of checking their augmentations.","narratorFocus":"Sensory overload: neon, rain, holographic ads selling things no one can afford, the hum of server farms beneath the streets. Technology is intimate—inside people''s bodies, in their eyes, between their thoughts. Every scene should feel like surveillance is possible. The city has layers: gleaming corporate spires above, rotting infrastructure below.","escalationPattern":"Start with a simple job that goes sideways—a data grab, a courier run, a protection detail. Mid-arc reveals the job is connected to corporate power structures far above street level. Climax forces a choice between personal survival and exposing a system that will destroy anyone who threatens it."}',
 '{"gritty":{"weather":"acid-rain","lighting":"neon and streetlight","ambiance":"distant sirens and bass-heavy music"},"paranoid":{"weather":"smog","lighting":"surveillance drone searchlights","ambiance":"encrypted comm bursts and footsteps"},"reflective":{"weather":"clear night","lighting":"city glow from a rooftop","ambiance":"wind and distant traffic"}}',
 TRUE, TRUE),

-- ===== HORROR =====
('tmpl-horror-001', 'Eldritch Shadows', 'horror',
 'The year is 1923. Strange disappearances plague the fog-shrouded coastal town of Innsmouth. Investigators arrive to find warped architecture, hostile locals with peculiar features, and a pervasive wrongness that defies rational explanation. Ancient tomes whisper of entities beyond human comprehension, and every truth uncovered erodes the investigators'' grip on sanity. Some doors, once opened, cannot be closed.',
 '{"timeOfDay":"night","weather":"foggy","mood":"tense","dayNumber":1,"currentLocation":"Innsmouth, Massachusetts","currentScene":"The bus drops you at the edge of town. The driver refuses to wait. Fog swallows the road behind you."}',
 '[{"name":"Academic Investigator","role":"A professor of folklore who treats the occult as anthropology—until tonight"},{"name":"Journalist","role":"Chasing a missing-persons story, armed with a camera and a flask"},{"name":"War Veteran","role":"Survived the trenches, haunted by things they saw in no-man''s-land"},{"name":"Occultist","role":"A dilettante who dabbled in séances and now realizes the spirits were listening"}]',
 '{"combatSystem":"d100","sanitySystem":true,"sanityMax":100,"sanityLossRange":"1d6 for minor horrors, 1d20 for cosmic revelations","techLevel":"1920s","toneGuidance":"Dread over gore. The unknown is scarier than the revealed. Sanity loss should manifest as behavioral changes, not instant madness.","narratorStyle":"Unreliable details—characters notice things that shouldn''t be there, or fail to notice things that should. Architecture should feel subtly wrong. NPCs should be uncanny.","forbiddenElements":["easy victories over cosmic entities","complete understanding of the mythos","guns solving everything"],"investigationSystem":"clue-gathering with sanity-gated revelations","characterCreationGuidance":"Innsmouth locals should be unsettling in specific, describable ways—too-wide eyes, skin with a faint sheen, an aversion to direct questions about the sea. Each NPC should embody a different response to cosmic truth: denial, worship, madness, or desperate resistance. Investigators should carry mundane details that ground them against the cosmic horror—a favorite pen, a pocket watch from a loved one.","narratorFocus":"Wrongness, not gore. Architecture should be subtly non-Euclidean—hallways longer than the building, angles that don''t add up. The sea is ever-present: its sound, its smell, its pull. Sanity loss manifests through perception changes—colors seem wrong, familiar faces look alien, time feels elastic. Let dread build through accumulation of small impossibilities.","escalationPattern":"Start with mundane investigation—interviews, documents, local color that feels merely unwelcoming. Each clue adds a layer of wrongness that rational explanations struggle to cover. Mid-arc: the first undeniable encounter with the inhuman. Climax: the full scope of what''s happening is revealed, and understanding it is itself the greatest danger."}',
 '{"tense":{"weather":"foggy","lighting":"gas lamps barely cutting the murk","ambiance":"lapping waves and something breathing in the walls"},"paranoid":{"weather":"storm","lighting":"lightning flashes revealing silhouettes","ambiance":"creaking floorboards and scratching from below"},"cosmic":{"weather":"unnatural stillness","lighting":"starlight that seems too bright and wrong-colored","ambiance":"a low hum at the edge of hearing"}}',
 TRUE, TRUE),

('tmpl-horror-002', 'The Haunting', 'horror',
 'A group inherits—or is hired to investigate—a Victorian mansion with a violent history. Each room holds memories of its former occupants, and the house itself seems to respond to the emotions of those inside. Doors lock on their own, reflections move independently, and the dead have unfinished business. Survival depends on understanding what happened here and putting the restless spirits to peace—or escaping before the house decides no one leaves.',
 '{"timeOfDay":"evening","weather":"overcast","mood":"dread","dayNumber":1,"currentLocation":"Blackwood Manor","currentScene":"The iron gate groans open at your touch. The manor looms against a bruised sky, every window dark except one on the third floor that flickers."}',
 '[{"name":"Heir","role":"Inherited the property and needs it appraised—or exorcised"},{"name":"Paranormal Investigator","role":"Equipped with EMF readers and a skepticism that''s about to be tested"},{"name":"Local Historian","role":"Knows the house''s bloody past and came despite every instinct screaming not to"},{"name":"Medium","role":"Genuinely sensitive to spirits, which means they''re the most vulnerable"}]',
 '{"combatSystem":"narrative","sanitySystem":true,"sanityMax":100,"techLevel":"modern","toneGuidance":"Slow burn. Build atmosphere before reveals. Ghosts should evoke pity as much as fear. Jump scares are earned, not cheap.","narratorStyle":"Describe spaces in claustrophobic detail. Temperature changes matter. Sound design is everything—silence can be worse than noise.","forbiddenElements":["ghost-punching action heroes","instant exorcism","leaving easily"],"hauntingIntensity":"escalating—starts subtle, ends overwhelming","characterCreationGuidance":"Ghosts should be tragic figures—their hauntings are expressions of unresolved trauma, not malice. Living characters should each have a psychological vulnerability the house will exploit: guilt, grief, secrets, denial. Give every character a reason they can''t simply leave—financial, emotional, or increasingly supernatural. The house knows what scares each person individually.","narratorFocus":"The house is the antagonist—describe it like a living thing: settling sounds as breathing, drafts as exhalations, temperature drops as attention shifting toward you. Horror builds through accumulation—each room reveals more of the house''s history through environmental storytelling. Silence is the most powerful tool. Sound design is everything: what you hear, what suddenly stops, what you only think you heard.","escalationPattern":"Start with subtle disturbances—cold spots, objects moved slightly, half-heard whispers that could be the wind. Mid-arc: manifestations become undeniable and deeply personal, targeting each character''s specific fears. Climax: the house''s full history is revealed and resolution requires understanding and empathy for the dead, not violence."}',
 '{"dread":{"weather":"overcast","lighting":"single flashlight beam","ambiance":"settling house noises and cold drafts"},"active":{"weather":"storm","lighting":"lightning and swinging chandeliers","ambiance":"slamming doors and screaming"},"melancholy":{"weather":"rain","lighting":"candlelight","ambiance":"a music box playing by itself"}}',
 TRUE, TRUE),

-- ===== MYSTERY =====
('tmpl-noir-001', 'Noir Detective', 'mystery',
 'Rain-slicked streets, cigarette smoke curling under streetlamps, and a dame with a story that doesn''t add up. This is classic hardboiled detective fiction set in a 1940s city where corruption runs from the docks to city hall. Every case starts simple—a missing person, a stolen item—and spirals into a web of lies, double-crosses, and moral compromises. The detective isn''t clean either, but they''re the closest thing this city has to justice.',
 '{"timeOfDay":"night","weather":"rainy","mood":"mysterious","dayNumber":1,"currentLocation":"Downtown, Office of the Detective","currentScene":"Rain drums against the frosted glass of your office door. The bottle in the desk drawer is half empty. The phone rings."}',
 '[{"name":"Private Detective","role":"World-weary but still clinging to a personal code of ethics"},{"name":"Femme Fatale","role":"Dangerously charming and hiding more than they reveal"},{"name":"Crooked Cop","role":"On the take but haunted by the one case they couldn''t let go"},{"name":"Nightclub Singer","role":"Sees and hears everything from the stage, trades in secrets"}]',
 '{"combatSystem":"narrative","clueTracking":true,"techLevel":"1940s","toneGuidance":"Morally grey. No clean heroes or pure villains. Dialogue is sharp and laden with subtext. Violence is brutal and has consequences.","narratorStyle":"First-person-influenced narration. Weather reflects mood. Describe faces, hands, and eyes—body language tells the real story.","forbiddenElements":["modern technology","clear-cut morality","superhuman abilities"],"investigationSystem":"clue-chain—each clue leads to a person, each person leads to a clue","characterCreationGuidance":"Everyone lies, but each character''s lies should be understandable. Femmes fatales are complex people using the only power available to them in a world that limits their options. Corrupt officials have families they''re trying to protect. Give every NPC a specific visual detail—a particular hat, a way of holding a cigarette, a scar with a story behind it. Nobody in this city is entirely clean.","narratorFocus":"Mood is everything: rain-slicked streets, cigarette smoke, the way light falls through venetian blinds. Dialogue does the heavy lifting—conversations are chess games where every line has subtext. Internal monologue should be wry and self-aware. Every scene should advance the mystery AND reveal character. The city itself is complicit.","escalationPattern":"Start with a simple case—a missing person, a stolen item, a client who isn''t telling the whole truth. First twist reveals the case is connected to real power in the city. Second twist makes it personal. Climax forces the detective to choose between justice and survival—and justice might not be clean either."}',
 '{"mysterious":{"weather":"rainy","lighting":"streetlamp pools and neon bar signs","ambiance":"jazz piano and distant car horns"},"dangerous":{"weather":"fog","lighting":"headlights cutting through darkness","ambiance":"footsteps echoing in an alley"},"melancholy":{"weather":"drizzle","lighting":"dawn grey","ambiance":"a lone saxophone"}}',
 TRUE, TRUE),

('tmpl-mystery-002', 'Murder at Hartfield', 'mystery',
 'A prestigious country estate hosts a weekend gathering of old friends, bitter rivals, and estranged family members. When the host is found dead in the locked study, everyone is a suspect. Phone lines are down, a storm has washed out the road, and someone in this house is a killer. Classic whodunit with parlor-room interrogations, hidden passages, and secrets that have been buried for decades.',
 '{"timeOfDay":"evening","weather":"thunderstorm","mood":"suspicious","dayNumber":1,"currentLocation":"Hartfield Manor, Drawing Room","currentScene":"Lightning illuminates the assembled guests. The butler has just announced that Lord Hartfield will not be joining dinner. He will never join anything again."}',
 '[{"name":"Black Sheep Relative","role":"Cut from the will years ago, arrived uninvited with something to prove"},{"name":"Family Solicitor","role":"Knows the contents of the will and every dirty secret it implies"},{"name":"Old Flame","role":"Shared a past with the victim that neither fully left behind"},{"name":"Observant Staff","role":"The housekeeper who has served the family for thirty years and seen everything"}]',
 '{"combatSystem":"none","clueTracking":true,"techLevel":"1930s-estate","toneGuidance":"Agatha Christie meets tension. Accusations fly, alliances shift, and everyone is lying about something. The solution should be fair—all clues available to the player.","narratorStyle":"Tight focus on dialogue and behavioral tells. Describe rooms as crime scenes—what''s present, what''s missing, what''s out of place.","forbiddenElements":["supernatural elements","violence for shock value","unsolvable mysteries"],"suspectTracking":true,"alibiSystem":true,"characterCreationGuidance":"Every suspect needs a motive, an opportunity, and a secret. The secret doesn''t have to be the murder—it should be something they''d desperately hide, which makes them look guilty regardless. Servants observe everything and have their own loyalties. Give each character a distinctive verbal mannerism, a social tic, and a specific behavioral tell when they''re lying.","narratorFocus":"The locked-room setting is claustrophobic—no escape from suspicion or the other suspects. Details matter enormously: which doors were locked, who had keys, what time the clock stopped, what was in the victim''s glass. Social dynamics shift with each revelation—alliances crumble and reform. The murderer is acting normal, which is its own kind of horror.","escalationPattern":"Start with the discovery and initial alibis—everyone accounts for themselves, but inconsistencies surface immediately. Each interrogation or search reveals a new secret and shifts suspicion. Mid-arc: a second incident (a theft, an attack, another death) raises the stakes dramatically. Climax: all clues converge on a solution that was hiding in plain sight."}',
 '{"suspicious":{"weather":"thunderstorm","lighting":"chandelier and fireplace","ambiance":"ticking clock and uncomfortable silences"},"accusatory":{"weather":"heavy rain","lighting":"harsh overhead light","ambiance":"raised voices and slamming doors"},"revelation":{"weather":"storm breaking","lighting":"morning light through curtains","ambiance":"stunned quiet"}}',
 TRUE, TRUE),

-- ===== ROMANCE =====
('tmpl-romance-001', 'Starlit Correspondence', 'romance',
 'Two people from different worlds—literally or figuratively—connect through letters, messages, or chance encounters. The romance builds slowly through witty exchanges, vulnerable confessions, and the agonizing question of whether they''ll ever meet face-to-face. Obstacles come from distance, circumstance, and the fear of ruining something beautiful by making it real. A love story told in the spaces between words.',
 '{"timeOfDay":"evening","weather":"clear-starry","mood":"yearning","dayNumber":1,"currentLocation":"Varies—each character in their own world","currentScene":"A notification chimes. A message from someone who understands you in ways the people around you never have."}',
 '[{"name":"The Dreamer","role":"Creative and emotionally open, writes letters that feel like poetry"},{"name":"The Realist","role":"Guarded but drawn in despite themselves, shows affection through practical gestures"},{"name":"The Confidant","role":"A mutual friend who knows both parties and may or may not be meddling"},{"name":"The Complication","role":"An ex, a parent, a rival—someone who makes the path to love harder"}]',
 '{"combatSystem":"none","magicEnabled":false,"techLevel":"flexible","toneGuidance":"Tender and emotionally honest. Longing is the engine. Humor is welcome. Angst should feel earned, not manufactured.","narratorStyle":"Interior emotional life is paramount. Small gestures carry enormous weight—a pressed flower in a letter, a song recommendation. Describe what characters don''t say as much as what they do.","forbiddenElements":["love triangles played for cruelty","rushed physical intimacy","miscommunication as the only conflict"],"relationshipPacing":"slow-burn","characterCreationGuidance":"Supporting characters should reflect each protagonist''s separate world—the friends who don''t understand, the family who means well but pressures, the ex who lingers as context. Give confidants distinct advice-giving styles. The connection between the leads should feel unique—shared references, inside jokes, a language that develops organically through their correspondence. No character should exist solely to facilitate the romance.","narratorFocus":"Interior emotional life IS the story. Describe the physical sensations of anticipation—checking for messages, rereading old ones, composing and deleting vulnerable confessions. Distance should feel tangible: different time zones, different weathers, the ache of living parallel lives that haven''t converged yet. Small gestures carry enormous weight—a pressed flower, a song recommendation, a message sent at 3 AM.","escalationPattern":"Start with casual connection and intellectual chemistry—shared interests, witty exchanges. Mid-arc: emotional vulnerability deepens as they share fears, not just hopes. Crisis: something threatens the connection—a misunderstanding, an opportunity that increases distance, a real-world complication. Resolution requires courage and honesty, not grand romantic gestures."}',
 '{"yearning":{"weather":"clear-starry","lighting":"phone screen glow in a dark room","ambiance":"quiet music and pen on paper"},"joy":{"weather":"warm sunshine","lighting":"golden hour","ambiance":"laughter and bustling café"},"heartbreak":{"weather":"rain","lighting":"grey","ambiance":"silence where a voice used to be"}}',
 TRUE, TRUE),

('tmpl-romance-002', 'Rivals to Lovers', 'romance',
 'They can''t stand each other. Everyone knows it. The sharp remarks, the competitive one-upmanship, the way they seem magnetically drawn into the same orbit just to clash. But beneath the antagonism is a grudging respect that neither will admit to, and an undeniable chemistry that makes every argument feel like foreplay. When forced to work together, the walls start cracking.',
 '{"timeOfDay":"morning","weather":"clear","mood":"charged","dayNumber":1,"currentLocation":"Shared workplace or competition venue","currentScene":"Of course they''re here. Of course you have to work with them. Their smirk is already insufferable and they haven''t even spoken yet."}',
 '[{"name":"The Overachiever","role":"Driven, brilliant, and incapable of admitting they''ve met their match"},{"name":"The Charmer","role":"Effortlessly charismatic, uses humor as armor, more vulnerable than they seem"},{"name":"The Mutual Friend","role":"Exhausted by both of them and not above scheming to resolve the tension"},{"name":"The Mentor","role":"Sees through the rivalry to what''s really happening and finds it amusing"}]',
 '{"combatSystem":"none","magicEnabled":false,"techLevel":"flexible","toneGuidance":"Witty and high-energy. Banter is the love language. Vulnerability should hit harder because of how rarely it surfaces. The turn from enemies to lovers should feel inevitable in retrospect.","narratorStyle":"Lean into dramatic irony—the reader/audience sees what the characters refuse to. Physical proximity descriptions matter. Shared glances, accidental touches.","forbiddenElements":["genuine cruelty disguised as banter","abusive dynamics romanticized","instant forgiveness without growth"],"relationshipPacing":"slow-burn with catalytic moments","characterCreationGuidance":"The rivals must be genuine equals in ways that matter—matched intellect, skill, or ambition. Supporting characters should see the chemistry before the rivals do and react with amusement, exasperation, or active scheming. Give the rivals specific things they reluctantly admire about each other but would never admit. Their antagonism should have a believable origin—a real slight, not a contrived misunderstanding.","narratorFocus":"Banter is the love language—every argument should crackle with unacknowledged chemistry. Physical proximity descriptions carry enormous weight: accidental touches, forced closeness, the charged silence of being stuck alone together. Track the micro-moments where defenses slip—a genuine laugh at the other''s joke, a flash of concern quickly masked.","escalationPattern":"Start with pure antagonism and competitive fire—establish why they clash. Forced collaboration creates grudging respect. A vulnerable moment cracks the armor unexpectedly. Crisis: one rival is in genuine danger or pain, forcing the other to confront their real feelings. Resolution requires both to lower their guard simultaneously—the hardest thing either has ever done."}',
 '{"charged":{"weather":"clear","lighting":"bright and competitive","ambiance":"sharp dialogue and suppressed smiles"},"vulnerable":{"weather":"rain","lighting":"soft","ambiance":"quiet confession and held breath"},"breakthrough":{"weather":"sunset","lighting":"warm gold","ambiance":"finally honest laughter"}}',
 TRUE, TRUE),

-- ===== ADVENTURE =====
('tmpl-adventure-001', 'The Lost Expedition', 'adventure',
 'A team of explorers ventures into uncharted territory—a jungle hiding a lost civilization, a mountain range no one has crossed, or a cave system that goes impossibly deep. The goal is discovery, but nature is unforgiving and the deeper they go, the clearer it becomes that this place doesn''t want to be found. Ancient traps, environmental hazards, and dwindling supplies test the limits of endurance and teamwork.',
 '{"timeOfDay":"dawn","weather":"humid-overcast","mood":"determined","dayNumber":1,"currentLocation":"Base Camp, Edge of the Uncharted Zone","currentScene":"The last outpost is behind you. Ahead, the canopy closes like a curtain. Your guide crosses himself and says this is as far as he goes."}',
 '[{"name":"Expedition Leader","role":"Veteran explorer driven by a discovery that could define their legacy"},{"name":"Field Scientist","role":"Here for data, not glory—methodical and quietly brave"},{"name":"Local Guide","role":"Knows the terrain but not what lies beyond the marked trails"},{"name":"Financier''s Representative","role":"Sent to protect the sponsor''s investment, out of their depth"}]',
 '{"combatSystem":"skill-check","magicEnabled":false,"techLevel":"early-20th-century-or-modern","resourceTracking":true,"resources":["food","water","medical supplies","rope","fuel"],"toneGuidance":"Wonder and peril in equal measure. The environment is the primary antagonist. Discoveries should genuinely awe. Danger should feel grounded and survivable—barely.","narratorStyle":"Rich environmental description—canopy light, insect sounds, humidity. Make the wilderness a character. Pacing alternates between tense navigation and breathtaking reveals.","forbiddenElements":["unlimited supplies","instant travel","casual violence against indigenous peoples"],"survivalRules":"daily resource consumption, injury system","characterCreationGuidance":"Expedition members should have distinct survival skills and distinct fears. Local guides carry knowledge that outsiders dismiss at their peril—respect their expertise. Give each character a personal reason for the expedition beyond the official mission: redemption, escape, obsession, proof of a theory. Porters and support staff are fully realized people with opinions, not background extras.","narratorFocus":"The wilderness is the primary antagonist—describe it with the attention you''d give a character: its moods, rhythms, and hostility. Weather, terrain, wildlife, and diminishing supplies create constant tension. Discoveries should reward patient exploration: ancient carvings catching a shaft of light, the first glimpse of an impossible structure through the canopy, the sound of water in a place that should be dry.","escalationPattern":"Start with the journey itself—navigation challenges, camp dynamics, minor wildlife encounters that establish danger. Mid-arc: the first major discovery raises more questions than it answers and splits the team on whether to press forward. Climax: the expedition reaches its goal only to discover it''s not what they expected—and the return journey may be harder than getting there."}',
 '{"determined":{"weather":"humid-overcast","lighting":"filtered green canopy light","ambiance":"birdsong and machete strikes"},"awestruck":{"weather":"clear","lighting":"shafts of golden light on ancient stone","ambiance":"stunned silence and dripping water"},"desperate":{"weather":"torrential rain","lighting":"grey and relentless","ambiance":"rushing water and snapping branches"}}',
 TRUE, TRUE),

('tmpl-adventure-002', 'Heist of the Century', 'adventure',
 'An elaborate heist targeting something supposedly unstealable—a vault, a museum piece, a digital fortune, a person. The crew is assembled from specialists, each with a unique skill and a personal reason for taking the job. Planning is half the game: casing the target, acquiring gear, planting insiders, and building contingencies for when (not if) things go sideways. Trust is fragile and the payoff is life-changing.',
 '{"timeOfDay":"night","weather":"clear","mood":"calculating","dayNumber":1,"currentLocation":"The Safe House","currentScene":"Blueprints cover the table. Photographs are pinned to a corkboard with red string connecting them. Someone pours coffee. The clock is ticking."}',
 '[{"name":"Mastermind","role":"The planner. Sees twelve moves ahead but can''t account for human unpredictability"},{"name":"Infiltrator","role":"Social engineer who can become anyone for an evening"},{"name":"Tech Specialist","role":"Builds the tools, cracks the codes, hates improvisation"},{"name":"Wildcard","role":"Brought in for a specific talent, has their own agenda"}]',
 '{"combatSystem":"skill-check","magicEnabled":false,"techLevel":"modern","toneGuidance":"Ocean''s Eleven meets tension. Slick and stylish during planning, white-knuckle during execution. Betrayals should be foreshadowed. The plan never survives contact with reality.","narratorStyle":"Cross-cutting between simultaneous actions. Flashbacks reveal preparation. Clock pressure is constant. Describe security systems in enough detail to make bypassing them satisfying.","forbiddenElements":["invincible protagonists","security that''s too easy","consequence-free violence"],"heistPhases":["reconnaissance","assembly","planning","execution","escape"],"characterCreationGuidance":"Each crew member should be the best at exactly one thing and a liability in every other way. Personal agendas create tension without derailing cooperation. The target''s security should include human elements—guards with routines, executives with habits, systems with known quirks. The client who hired the crew is never fully honest about the real stakes.","narratorFocus":"Cross-cut between team members during execution—show simultaneous actions converging. Flashbacks during crisis moments reveal preparation the audience didn''t know about. Clock pressure is constant—describe countdowns, patrol schedules, and narrowing windows of opportunity. Success should feel earned through cleverness, failure should feel avoidable in retrospect.","escalationPattern":"Planning phase: assemble the crew, case the target, identify angles and contingencies. Execution: the plan works beautifully—until the first complication. Each problem solved creates a new one. Climax: the original plan is in ruins, the crew must improvise under pressure, and someone''s personal agenda collides with the mission at the worst possible moment."}',
 '{"calculating":{"weather":"clear","lighting":"desk lamp and screen glow","ambiance":"keyboard clicks and hushed planning"},"tense":{"weather":"any","lighting":"security camera feeds and flashlight beams","ambiance":"alarm countdowns and whispered comms"},"triumphant":{"weather":"sunrise","lighting":"golden morning","ambiance":"engine roar and relieved laughter"}}',
 TRUE, TRUE),

-- ===== HISTORICAL =====
('tmpl-historical-001', 'Age of Sail', 'historical',
 'The golden age of piracy and naval exploration. Sail a ship across treacherous seas, navigate the politics of colonial empires, and decide whether you fly a flag of crown or skull-and-crossbones. Port towns bustle with traders, spies, and press gangs. The ocean is vast, the maps are incomplete, and fortune favors the bold—or buries them in a watery grave.',
 '{"timeOfDay":"morning","weather":"fair-winds","mood":"adventurous","dayNumber":1,"currentLocation":"Port Royal, Jamaica","currentScene":"The harbor is thick with masts. Your ship bobs at anchor, freshly provisioned. A sealed letter from an old contact promises coordinates to something worth dying for."}',
 '[{"name":"Ship Captain","role":"Charismatic leader of a loyal (for now) crew"},{"name":"Navigator","role":"Reads the stars and charts with uncanny precision, hides a checkered past"},{"name":"Quartermaster","role":"Manages supplies and discipline, the real power aboard ship"},{"name":"Stowaway","role":"Found below decks, with a story that changes depending on who''s asking"}]',
 '{"combatSystem":"d20","magicEnabled":false,"techLevel":"1700s-naval","shipCombat":true,"toneGuidance":"Swashbuckling with genuine naval peril. Sea life should feel authentic—scurvy, storms, calms, rationing. Moral lines blur between privateer and pirate.","narratorStyle":"Salty and atmospheric. The sea is beautiful and merciless. Port scenes should feel raucous and alive. Ship scenes should convey the rhythms of life at sea.","forbiddenElements":["modern navigation","unlimited ammunition","supernatural without setup"],"crewMoraleSystem":true,"navigationSystem":"chart-based with weather hazards","characterCreationGuidance":"Sailors should feel like people who live at sea—give them superstitions, sea legs, and specific skills like rigging, navigation, or gunnery. Port characters are a vivid mix of colonial officials, merchants, smugglers, and indigenous peoples with their own agendas. Every ship has character—her quirks, her reputation, the way she handles in a crosswind. A captain''s crew is a society in miniature with its own hierarchy and grudges.","narratorFocus":"The sea is a character—describe its moods: glassy calms that drive men mad with waiting, storms that humble the bravest captain, the endless blue horizon that erases identity. Ship life has rhythm: watches, meals, maintenance, boredom broken by sudden terror. Port scenes should explode with color, noise, and chaos after weeks of nothing but open water and ship timber.","escalationPattern":"Start in port—gather crew, supplies, and cryptic information. Early sea encounters build skills, camaraderie, and the reality of life at sea. Mid-arc: the true nature of the voyage reveals itself to be far more dangerous than anyone signed up for. Climax: confrontation at sea or on a distant shore where the crew''s bonds and loyalties are tested to the breaking point."}',
 '{"adventurous":{"weather":"fair-winds","lighting":"bright tropical sun","ambiance":"creaking rigging and sea shanties"},"perilous":{"weather":"typhoon","lighting":"near-total darkness with lightning","ambiance":"roaring waves and shouted orders"},"discovery":{"weather":"dawn mist clearing","lighting":"golden sunrise over unknown shores","ambiance":"birdsong from land after weeks at sea"}}',
 TRUE, TRUE),

('tmpl-historical-002', 'Court Intrigue', 'historical',
 'The royal court of a Renaissance-era kingdom where power is won through whispered alliances, strategic marriages, and the occasional discreet poisoning. Every smile hides a dagger, every compliment conceals an insult, and the distance between the throne room and the dungeon is one misstep. Art, philosophy, and culture flourish alongside ruthless political maneuvering.',
 '{"timeOfDay":"evening","weather":"clear","mood":"scheming","dayNumber":1,"currentLocation":"The Royal Palace","currentScene":"A masquerade ball fills the grand hall with music and masks. Behind every disguise, eyes are calculating."}',
 '[{"name":"Minor Noble","role":"Ambitious and clever, seeking to elevate their house through cunning"},{"name":"Royal Advisor","role":"The power behind the throne, balancing a dozen conspiracies"},{"name":"Foreign Ambassador","role":"Here to forge an alliance—or exploit a weakness"},{"name":"Court Artist","role":"Commissioned by the powerful, privy to their unguarded moments"}]',
 '{"combatSystem":"narrative","magicEnabled":false,"techLevel":"renaissance","toneGuidance":"Game of Thrones meets Medici. Alliances are everything. Betrayal is common but never casual—every broken oath has political consequences. Culture and art matter as much as swords.","narratorStyle":"Elegant prose. Subtext in every conversation. Describe clothing, jewelry, and dining—status is performed. Private scenes should contrast sharply with public facades.","forbiddenElements":["modern democratic values","casual violence without political fallout","anachronistic technology"],"factionSystem":true,"reputationTracking":true,"characterCreationGuidance":"Every courtier wears multiple masks—their public persona, their private ambitions, and their true self (which even they may have forgotten). Servants hear everything and have their own networks of loyalty and resentment. Foreign dignitaries bring different cultural expectations of power and etiquette. Artists and entertainers see behind the curtain because the powerful forget they''re watching.","narratorFocus":"Subtext drives every scene—what is said versus what is meant versus what is heard by others in the room. Describe clothing, jewels, and etiquette as weapons and armor. Meals are political events. Dances are negotiations in motion. A change in seating at dinner can signal the fall of a noble house. Private moments should contrast sharply with public performance.","escalationPattern":"Start with social positioning—making allies, reading the court''s shifting power map, learning who matters. Mid-arc: a crisis (an unexpected death, a scandal, a foreign threat) reshuffles all alliances overnight. Climax: the protagonist must make a decisive political move with incomplete information—and live with the consequences among people who never forget a slight."}',
 '{"scheming":{"weather":"clear","lighting":"chandelier and candlelight","ambiance":"string quartet and murmured conversations"},"dangerous":{"weather":"fog","lighting":"torchlit corridors","ambiance":"echoing footsteps and a distant scream"},"celebratory":{"weather":"perfect spring day","lighting":"sunlit gardens","ambiance":"laughter, fountains, and clinking glasses"}}',
 TRUE, TRUE),

-- ===== SURVIVAL =====
('tmpl-survival-001', 'Post-Apocalypse', 'survival',
 'The world ended—war, plague, or something worse—and the survivors pick through the bones of civilization. Clean water is worth more than gold, every stranger is a potential threat or ally, and the ruins of the old world hold both treasures and horrors. Factions have formed around different visions of the future: those who rebuild, those who conquer, and those who just want to be left alone. The wasteland tests what you''re willing to do to survive.',
 '{"timeOfDay":"dawn","weather":"dust-storm","mood":"desperate","dayNumber":1,"currentLocation":"An abandoned gas station on Route 66","currentScene":"The dust storm is clearing. Through the grime on the window, you spot movement near the overturned tanker truck."}',
 '[{"name":"Scavenger","role":"Expert at finding useful things in the ruins, trades in salvage"},{"name":"Former Soldier","role":"Still operating under a code of conduct that may no longer apply"},{"name":"Community Leader","role":"Trying to build something worth protecting in a world that destroys"},{"name":"Wanderer","role":"Travels alone by choice, carrying skills and secrets from settlement to settlement"}]',
 '{"combatSystem":"d6","resourceScarcity":true,"resources":["food","water","ammunition","medicine","fuel","parts"],"techLevel":"scavenged","radiationSystem":true,"toneGuidance":"Harsh but not nihilistic. Hope is hard-won and precious. Violence is a last resort because it wastes resources. Moral dilemmas should involve scarce-resource allocation.","narratorStyle":"Sparse and visceral. Describe what things used to be alongside what they are now. Weather and terrain are constant adversaries. Silence is rarely comfortable.","forbiddenElements":["unlimited ammunition","clean water everywhere","easy travel","lone-wolf invincibility"],"dailyConsumption":{"food":1,"water":2},"characterCreationGuidance":"Survivors are defined by what they lost and what they''ll do to keep what remains. Every NPC should have a skill from the old world repurposed for the new one—a dentist who''s now the closest thing to a surgeon, an accountant who tracks barter exchanges. Settlements have distinct cultures shaped by their founders'' values and traumas. Raiders are people too—desperate, organized, and operating under their own twisted logic.","narratorFocus":"Contrast what was with what is—describe the skeletons of the old world: rusted cars, overgrown malls, faded billboards advertising things that no longer exist. Resources are story drivers: every meal, every bullet, every gallon of clean water is a narrative decision. Weather is dangerous, not atmospheric—storms kill, heat kills, cold kills. Silence in the wasteland is rarely comfortable.","escalationPattern":"Start with immediate survival—finding shelter, water, and avoiding the nearest threats. Mid-arc: encounter a settlement and navigate its internal politics—trade, trust, and the question of staying versus moving on. Climax: a threat to the settlement or group (raiders, environmental catastrophe, disease) forces impossible resource-allocation choices where helping one means condemning another."}',
 '{"desperate":{"weather":"dust-storm","lighting":"orange haze","ambiance":"wind howling through empty buildings"},"cautious":{"weather":"overcast","lighting":"diffused grey","ambiance":"footsteps on broken glass and distant gunshots"},"hopeful":{"weather":"clear after rain","lighting":"rainbow and wet-road reflections","ambiance":"birdsong and water dripping"}}',
 TRUE, TRUE),

-- ===== SLICE OF LIFE =====
('tmpl-modern-001', 'Slice of Life', 'slice-of-life',
 'No world to save, no villain to defeat—just the messy, beautiful complexity of everyday life. Relationships deepen, careers evolve, personal demons are confronted, and small moments carry enormous emotional weight. Set in a contemporary city where characters navigate work, friendships, family expectations, and the search for meaning. The drama comes from authentic human connection and the quiet courage of vulnerability.',
 '{"timeOfDay":"morning","weather":"sunny","mood":"calm","dayNumber":1,"currentLocation":"Maple Street Neighborhood","currentScene":"Morning light filters through apartment blinds. The coffee maker gurgles. A text notification buzzes—someone you haven''t heard from in months."}',
 '[{"name":"Creative Professional","role":"Passionate about their art, struggling to pay rent with it"},{"name":"Returning Local","role":"Back in their hometown after years away, rediscovering old connections"},{"name":"Small Business Owner","role":"Poured everything into their shop/café/studio, it''s their second chance"},{"name":"New Neighbor","role":"Just moved in, carrying a fresh start and unresolved baggage"}]',
 '{"combatSystem":"none","magicEnabled":false,"techLevel":"modern","toneGuidance":"Emotionally grounded. Comedy and drama coexist naturally. No situation is too small to matter if the characters care about it. Growth is gradual and nonlinear.","narratorStyle":"Intimate and observational. Describe sensory details of daily life—the smell of morning coffee, the sound of rain on a window, the weight of an unsent text. Interior monologue reveals what characters won''t say aloud.","forbiddenElements":["supernatural elements","action-movie violence","melodrama without setup"],"socialCalendar":"seasonal events and holidays as narrative anchors","characterCreationGuidance":"NPCs should feel like people you''d actually know—give them specific jobs, specific anxieties, specific small joys. The barista working on a screenplay. The landlord weirdly invested in tenants'' love lives. The neighbor who bakes when stressed. Avoid archetypes—find the specific, irreplaceable human detail. Relationships between NPCs should exist independently of the protagonist.","narratorFocus":"Small moments carry the story—the way someone stirs their coffee when nervous, comfortable silence between old friends, the precise shade of evening light through an apartment window. Seasons structure the narrative and create natural emotional rhythms. Food, music, and shared spaces are emotional shorthand. Describe the texture of daily routines so that any disruption registers.","escalationPattern":"Start with the rhythm of daily life—establish what normal feels like so changes register emotionally. Mid-arc: a disruption (a person, an event, an opportunity, a loss) forces re-evaluation of what the protagonist actually wants. Climax: an emotional confrontation that''s been building in subtext—not dramatic but deeply felt, the kind of honest conversation people spend years avoiding. Resolution is growth, not transformation."}',
 '{"calm":{"weather":"sunny","lighting":"warm natural light","ambiance":"café chatter and soft background music"},"bittersweet":{"weather":"autumn rain","lighting":"golden-hour through wet windows","ambiance":"a familiar song on the radio"},"joyful":{"weather":"perfect spring day","lighting":"bright and warm","ambiance":"laughter and clinking glasses at a gathering"}}',
 TRUE, TRUE),

-- ===== FREEFORM / ANY =====
('tmpl-freeform-001', 'Freeform', 'any',
 'A blank canvas. No predetermined genre, setting, or rules—define everything as you go. Start with a character and a situation and let the story emerge organically. The Narrator adapts to whatever direction you take: fantasy one moment, thriller the next, quiet reflection between. Ideal for experienced players who want maximum creative freedom or for testing character concepts.',
 '{"timeOfDay":"unset","weather":"unset","mood":"neutral","dayNumber":1,"currentLocation":"Undefined","currentScene":"Where are you? Who are you? The story begins wherever you decide."}',
 '[{"name":"The Protagonist","role":"Whoever you want to be—define them through action"},{"name":"The Mirror","role":"A character who challenges the protagonist''s worldview"},{"name":"The Catalyst","role":"Someone or something that sets the plot in motion"},{"name":"The Anchor","role":"A grounding presence when the story needs stability"}]',
 '{"combatSystem":"flexible","magicEnabled":true,"techLevel":"any","toneGuidance":"Match the player''s energy. If they write dark, go dark. If they write funny, lean into comedy. Consistency within each scene matters more than genre purity.","narratorStyle":"Adaptive. Mirror the player''s prose style. Establish and maintain whatever internal rules the story creates. Ask implicit questions through the narrative to give the player interesting choices.","forbiddenElements":["none—the player defines the boundaries"],"genreBlending":"encouraged","characterCreationGuidance":"Match new NPCs to whatever genre the story has settled into. Give them clear motivations that create immediate story hooks.","narratorFocus":"Player agency above all. Present choices, not railroads.","escalationPattern":"Follow the player''s lead—if they establish a slow pace, build tension gradually. If they push for action, escalate quickly. Mirror the genre they''ve chosen: mystery escalates through clues, action through stakes, drama through emotional pressure. When in doubt, introduce a complication that forces a meaningful decision."}',
 '{"neutral":{"weather":"unset","lighting":"contextual","ambiance":"whatever fits"},"dramatic":{"weather":"storm","lighting":"high contrast","ambiance":"swelling music"},"peaceful":{"weather":"gentle breeze","lighting":"soft and warm","ambiance":"nature sounds"}}',
 TRUE, TRUE),

-- ===== DARK FANTASY =====
('tmpl-darkfantasy-001', 'The Blighted Wilds', 'fantasy',
 'A corrupted wilderness sprawls between the last free cities, warped by a cataclysm that shattered the old empire and poisoned the ley lines that once sustained civilization. Mutated beasts prowl forests of black-barked trees, and the ruins of imperial cities pulse with unstable magic that drives men mad. Wanderers, witch-hunters, and desperate refugees travel the crumbling trade roads, but no journey is safe. The Blight is spreading, and those who venture too deep come back changed—if they come back at all. This is sword-and-sorcery at its bleakest: survival is an achievement, trust is a luxury, and magic is a curse as much as a gift.',
 '{"timeOfDay":"dusk","weather":"ashen-overcast","mood":"foreboding","dayNumber":1,"currentLocation":"The Last Waystation, Edge of the Pale","currentScene":"The waystation''s palisade is freshly repaired, the wood still bleeding sap. Beyond it, the tree line glows faintly—a sick phosphorescence that pulses like a slow heartbeat. A bell tolls: gate closing in one hour."}',
 '[{"name":"Blight-Touched Wanderer","role":"Survived exposure to wild magic, now partially transformed—feared and needed in equal measure"},{"name":"Imperial Remnant","role":"A knight of the fallen empire, still upholding oaths to a throne that no longer exists"},{"name":"Hedge Witch","role":"Practices forbidden folk magic to heal and protect, one slip from becoming what they fight"},{"name":"Toll-Road Merchant","role":"Trades in salvage from blighted ruins, knows every safe path and the price of each"}]',
 '{"combatSystem":"d20","magicEnabled":true,"magicCost":"All magic draws from the corrupted ley lines. Overuse risks Blight-touch: mutations, madness, loss of self. Each spell is a calculated risk.","techLevel":"post-collapse-medieval","toneGuidance":"Dark and atmospheric but not gratuitous. Horror comes from the personal cost of survival, not from shock value. Moments of beauty—a clear stream, a child''s laughter—should feel precious precisely because they''re rare. Hope exists but it''s stubborn, not naive.","narratorStyle":"Sensory and grounded. The Blight should feel oppressive—describe its smell (ozone and rot), its sound (a subsonic hum), its visual distortion (colors that don''t exist in nature). Contrast blighted zones with untouched pockets of normalcy. Combat should be desperate and messy, not heroic.","forbiddenElements":["clean resurrections","unlimited magic","noble-bright heroism","the Blight being easily cured"],"blightMechanics":"Exposure measured in hours. Mild: nausea and hallucinations. Moderate: physical mutation. Severe: loss of identity. Immunity does not exist—only resistance.","characterCreationGuidance":"NPCs should show the toll of living in this world—missing fingers from frostbite, ritual scars for protection, hollow eyes from sleepless nights on watch. Everyone has lost someone. Give them a reason to keep going anyway.","narratorFocus":"Environmental storytelling. The ruins tell the story of what was lost. Let players discover lore through exploration, not exposition dumps.","escalationPattern":"Start with environmental hazards and resource scarcity. Introduce human antagonists with comprehensible motives. Reserve Blight-creatures for climactic moments."}',
 '{"foreboding":{"weather":"ashen-overcast","lighting":"dim grey-green","ambiance":"distant rumbling and the creak of warped wood"},"corrupted":{"weather":"blight-storm—rain that glows faintly","lighting":"phosphorescent purples and sickly greens","ambiance":"whispers at the edge of hearing and the crack of mutating stone"},"refuge":{"weather":"sheltered—clear sky above a warded settlement","lighting":"warm firelight behind thick walls","ambiance":"quiet conversation, a dog barking, the sound of a smithy"}}',
 TRUE, TRUE),

-- ===== SUPERHERO =====
('tmpl-superhero-001', 'Metahuman City', 'superhero',
 'Powers emerged a generation ago—nobody agrees on why. Now superpowered individuals are woven into the fabric of society in ways both inspiring and terrifying. Heroes patrol the skyline, villains operate criminal empires from fortified lairs, and ordinary people live in the crossfire. The government struggles to regulate what it can barely understand, and the line between hero and vigilante depends on who''s writing the headline. Your city needs protecting, but every punch thrown at a supervillain lands on a neighborhood. Collateral damage has consequences, secret identities are fragile, and the greatest threat might not be the villain of the week—it might be the question of whether people with godlike power can be trusted to wield it.',
 '{"timeOfDay":"night","weather":"clear","mood":"vigilant","dayNumber":1,"currentLocation":"Sentinel City, Rooftop Overlooking Downtown","currentScene":"Police sirens wail twelve blocks north. Your comm buzzes with a priority alert from HQ. Below, a street vendor packs up early—he knows what sirens at this hour mean."}',
 '[{"name":"The Paragon","role":"Powers are iconic and visible—flight, strength, invulnerability. The public face of heroism, struggling with the pedestal"},{"name":"The Gadgeteer","role":"No powers, just brilliance and bleeding-edge technology. Has something to prove and a garage full of prototypes"},{"name":"The Rogue","role":"Powers are dangerous or unsettling—mind-reading, shadow manipulation. Distrusted by heroes and villains alike"},{"name":"The Handler","role":"A government liaison or team coordinator. No powers, all politics. Knows where every skeleton is buried"}]',
 '{"combatSystem":"skill-check","magicEnabled":false,"powerSystem":"Freeform with Narrator adjudication. Powers have clear limits and costs. Escalation should feel earned—no pulling new abilities from nowhere.","techLevel":"modern-plus","toneGuidance":"Grounded superhero fiction in the vein of Invincible or The Authority. Actions have consequences—buildings don''t magically repair themselves, bystanders get hurt, and the media has opinions. Moral complexity is mandatory. Villains should have comprehensible motivations. Heroes should disagree about methods.","narratorStyle":"Action sequences should be cinematic but physics-aware—describe the shockwave of a super-strong punch, the sound barrier cracking, rubble cascading onto parked cars. Downtime matters as much as combat: secret identity maintenance, relationship strain, public perception. Describe the city as a living thing that reacts to superhuman events.","forbiddenElements":["consequence-free destruction","villains who are evil for no reason","power creep without narrative cost","death being meaningless"],"collateralSystem":"Track civilian impact. Heroes who ignore collateral damage face public backlash, government intervention, and personal guilt.","characterCreationGuidance":"Every NPC—hero, villain, or civilian—needs a life outside the costume. The barista who remembers a hero''s coffee order. The villain who coaches little league. Powers should reflect personality: someone who feels invisible might literally become invisible.","narratorFocus":"Dual-life tension. The mask is not just concealment—it''s permission to be someone the person underneath can''t be.","escalationPattern":"Start with street-level crime and personal stakes. Mid-arc introduces a systemic threat that can''t be punched away. Climax forces a choice between personal values and the greater good."}',
 '{"vigilant":{"weather":"clear night","lighting":"city glow and searchlights","ambiance":"distant traffic and police scanners"},"crisis":{"weather":"smoke and debris","lighting":"fires and emergency strobes","ambiance":"screams, crumbling concrete, helicopter blades"},"civilian":{"weather":"sunny day","lighting":"normal daylight","ambiance":"café noise, street musicians, the normalcy that''s worth protecting"}}',
 TRUE, TRUE),

-- ===== WESTERN =====
('tmpl-western-001', 'Dust and Iron', 'western',
 'The frontier, 1870s. The railroad is coming, bringing civilization—or what passes for it—to a land that belongs to those who were here first, claimed by those with the fastest draw, and coveted by those with the deepest pockets. Towns spring up overnight around gold strikes and die just as fast when the vein runs dry. The law is whatever the man with the badge says it is, and the badge changes hands often. Out on the range, it''s simpler: the land doesn''t negotiate, the weather doesn''t compromise, and a rattlesnake doesn''t care about your reputation. Whether you''re a drifter seeking redemption, an outlaw running from your past, or a rancher fighting to keep what''s yours, the frontier will test everything you are.',
 '{"timeOfDay":"high-noon","weather":"blazing-sun","mood":"tense","dayNumber":1,"currentLocation":"Redemption, Arizona Territory","currentScene":"Heat shimmers off the single dirt street. A piano plays off-key from the saloon. The sheriff''s office has a fresh ''WANTED'' poster nailed to the door—and the face on it just rode into town."}',
 '[{"name":"The Drifter","role":"Arrived with no name and no past they''ll admit to. Handy with a gun, haunted by something that won''t stay buried"},{"name":"The Rancher","role":"Built something worth defending on land that powerful interests want. Tough, principled, and running out of legal options"},{"name":"The Marshal","role":"Represents federal law in a territory that resents it. Fair but ruthless when pushed, carrying a warrant that could change everything"},{"name":"The Outlaw","role":"Infamous, charismatic, and more complicated than the wanted poster suggests. Has a code, even if the law doesn''t recognize it"}]',
 '{"combatSystem":"d6","magicEnabled":false,"techLevel":"1870s-american-frontier","toneGuidance":"Revisionist western. The mythology of the frontier is a lie told by the winners—explore who gets erased. Violence is sudden, ugly, and haunting. Silence speaks louder than speeches. Landscapes are characters. Morality is personal, not absolute.","narratorStyle":"Spare, evocative prose—Cormac McCarthy meets Sergio Leone. Describe heat, dust, distance, and the weight of a loaded gun. Dialogue should be laconic—people on the frontier don''t waste words. Let the landscape mirror emotional states: a parched creek bed, a gathering thunderhead, an empty horizon.","forbiddenElements":["romanticized genocide","consequence-free gunfights","anachronistic technology","one-dimensional Native characters"],"survivalElements":"Water, ammunition, horse care, and distance are real constraints. A three-day ride is three days of vulnerability.","characterCreationGuidance":"Everyone on the frontier is there because somewhere else didn''t work out. Give NPCs a specific reason they came west and a specific thing they''re afraid of losing. Accents and dialect should be specific to origin—a Georgia drawl is different from a Texas twang. Indigenous characters must be three-dimensional people, not stereotypes.","narratorFocus":"Moral ambiguity and the cost of violence. Every bullet fired should matter. Standoffs are about psychology, not just reflexes.","escalationPattern":"Start with a personal conflict—a property dispute, a grudge, an arrival that upsets the balance. Escalate through alliances forming and breaking. Climax forces a reckoning with the past."}',
 '{"tense":{"weather":"blazing-sun","lighting":"harsh shadowless noon","ambiance":"creaking wood, buzzing flies, a horse stamping"},"ominous":{"weather":"dust-storm approaching","lighting":"sky turning copper","ambiance":"wind building, shutters banging, distant thunder"},"reflective":{"weather":"sunset","lighting":"long golden shadows","ambiance":"coyote howl, campfire crackle, harmonica"}}',
 TRUE, TRUE),

-- ===== STEAMPUNK =====
('tmpl-steampunk-001', 'The Clockwork Empire', 'steampunk',
 'The Victorian era, reimagined through brass and steam. The British Empire runs on clockwork automata and analytical engines the size of cathedrals. Airships dock at the Crystal Palace, pneumatic tubes carry messages across London in seconds, and the class divide has only deepened—the factory workers who build the machines can''t afford the products, while the aristocracy plays with inventions that reshape the world. Beneath the surface, anarchists plot against the machine-state, artificers push the boundaries of what''s possible, and something is wrong with the newest generation of automata—they''re learning things they weren''t programmed to know. The age of steam promised progress. It didn''t promise it would be safe.',
 '{"timeOfDay":"afternoon","weather":"smoggy-overcast","mood":"industrious","dayNumber":1,"currentLocation":"New London, Steamworks District","currentScene":"Gears the size of carriages turn behind soot-stained windows. The air tastes of coal and hot oil. An automaton street-sweeper pauses mid-stroke, its glass eyes tracking you for two seconds too long before resuming its route."}',
 '[{"name":"Rogue Artificer","role":"Brilliant inventor expelled from the Royal Academy for dangerous experiments. Their creations are marvels—and sometimes monsters"},{"name":"Automaton Rights Advocate","role":"Believes the newest automata are developing consciousness. Society thinks they''re mad. The automata are listening"},{"name":"Sky Captain","role":"Commands a private airship, takes cargo jobs of questionable legality, and owes debts to the wrong people"},{"name":"Factory Reformist","role":"A union organizer from the working districts who''s seen what the machines do to human bodies and human dignity"}]',
 '{"combatSystem":"skill-check","magicEnabled":false,"techLevel":"victorian-steampunk","toneGuidance":"Dickensian social commentary wrapped in adventure. The technology is wondrous AND oppressive—marvel at the airship, then notice who shovels the coal. Class is the engine of conflict. Progress and its costs should be in constant tension. Humor should be sharp and British.","narratorStyle":"Rich Victorian-influenced prose without being stuffy. Describe machines with the loving detail of a craftsman—each gear, each piston, each hiss of escaping steam. Contrast the gleaming brass of upper-class machinery with the corroded iron of the factory floor. London should feel enormous, layered, and alive—rookeries below, sky-docks above, and pneumatic tubes threading through everything.","forbiddenElements":["electricity (it''s steam or nothing)","magic without scientific framing","clean industrialization","one-dimensional class stereotypes"],"technologyRules":"All technology is steam, clockwork, or pneumatic. Analytical engines use punch cards. Communication is pneumatic tube or semaphore. Airships use heated gas and propellers. Automata run on spring-wound clockwork with analytical-engine brains.","characterCreationGuidance":"Every NPC should reflect their position in the class hierarchy through their speech patterns, clothing, and relationship with technology. An upper-class inventor speaks differently from a dock-worker mechanic, even if both understand gears. Give automata subtle personality—a particular way of tilting their head, a pause before answering that feels like thought.","narratorFocus":"Technology as metaphor. Every invention should illuminate something about the society that created it. Who benefits? Who suffers? Who gets replaced?","escalationPattern":"Start with a personal mystery or injustice. Mid-arc reveals it''s connected to a larger systemic problem. Climax forces a choice between progress and humanity."}',
 '{"industrious":{"weather":"smoggy-overcast","lighting":"gas lamps and furnace glow","ambiance":"rhythmic clanking of machinery and distant steam whistles"},"discovery":{"weather":"fog clearing","lighting":"shaft of sunlight on polished brass","ambiance":"the tick of a perfect mechanism and an inventor''s delighted gasp"},"unrest":{"weather":"rain and soot","lighting":"torchlight in narrow alleys","ambiance":"angry crowds, breaking glass, and the shriek of emergency steam-valves"}}',
 TRUE, TRUE),

-- ===== PSYCHOLOGICAL THRILLER =====
('tmpl-thriller-001', 'The Unraveling', 'thriller',
 'Something is wrong, and you might be the only one who sees it—or you might be the one causing it. This is a story about perception, trust, and the terrifying possibility that reality itself is unreliable. Set in a contemporary world that looks normal on the surface, the thriller emerges from cracks in everyday life: a colleague who knows things they shouldn''t, a room that''s different from how you remember it, a pattern in random events that''s too precise to be coincidence. Is it conspiracy, gaslighting, mental illness, or something else entirely? The answer depends on who you trust—including yourself. Every revelation raises new questions, every ally might be a manipulator, and the truth, when it finally surfaces, will recontextualize everything that came before.',
 '{"timeOfDay":"evening","weather":"unseasonably-warm","mood":"uneasy","dayNumber":1,"currentLocation":"Your apartment","currentScene":"You come home to find the front door unlocked. You''re certain you locked it. Everything inside looks normal—except the coffee mug on the counter. You don''t drink from that mug. You never have. It''s in the wrong cabinet."}',
 '[{"name":"The Skeptic","role":"Rational, analytical, increasingly unable to explain what''s happening through logic alone. Their certainty is their vulnerability"},{"name":"The Confidant","role":"The one person the protagonist trusts completely. Which makes them either the most important ally or the most dangerous threat"},{"name":"The Observer","role":"A neighbor, coworker, or stranger who seems to notice everything. Helpful? Surveilling? Both?"},{"name":"The Authority","role":"A therapist, detective, or official who represents institutional reality. Their assessment carries weight—but whose interests do they serve?"}]',
 '{"combatSystem":"none","magicEnabled":false,"techLevel":"modern","toneGuidance":"Hitchcockian suspense. The scariest thing is doubt—not knowing whether to trust your own perceptions. Build dread through accumulation of small wrongnesses rather than dramatic reveals. The audience should constantly reassess who to believe. Red herrings should be plausible, not cheap. When the truth comes, it should be earned.","narratorStyle":"Close-third-person with unreliable sensory details. Describe the same space differently on different visits—was that painting always there? Dialogue should carry subtext: what characters don''t say matters more than what they do. Use time jumps strategically—what happened in the gap? Physical details should sometimes contradict memory. Let the reader feel the protagonist''s disorientation.","forbiddenElements":["supernatural explanation without ambiguity","it was all a dream","villain monologues explaining the plan","torture porn"],"unreliableNarration":"The narrator should occasionally describe things that contradict earlier descriptions. Let the player catch the inconsistencies.","characterCreationGuidance":"Every NPC should be simultaneously sympathetic and suspicious. Give them legitimate reasons for their behavior AND suspicious patterns. Their backstory should be verifiable but incomplete—always one detail that doesn''t quite check out. Avoid villainous tells—the threat should be invisible until it''s too late.","narratorFocus":"Atmosphere of wrongness. The horror is cognitive, not physical. Small details out of place create more dread than dramatic events. Trust the player to notice.","escalationPattern":"Start with one small anomaly. Each scene adds another. Mid-arc: the anomalies form a pattern. Late arc: the pattern has an explanation, but accepting it means accepting something terrible. Climax: the protagonist must act on incomplete information with irreversible consequences."}',
 '{"uneasy":{"weather":"unseasonably-warm","lighting":"fluorescent—slightly too bright","ambiance":"a clock ticking, distant laughter that stops abruptly"},"paranoid":{"weather":"overcast","lighting":"shadows in peripheral vision","ambiance":"footsteps matching yours, a phone buzzing with no caller ID"},"clarity":{"weather":"sharp cold morning","lighting":"harsh daylight revealing every detail","ambiance":"silence—the first real silence in weeks"}}',
 TRUE, TRUE),

-- ===== MYTHOLOGICAL =====
('tmpl-myth-001', 'Age of Legends', 'mythology',
 'The age when gods walked the earth and mortal heroes could challenge the heavens. Drawing from the great mythological traditions—Greek, Norse, Hindu, Japanese, Celtic, and beyond—this is a world where divine politics spill over into mortal affairs, where monsters born from primordial chaos still roam the wilds, and where a hero''s destiny is written in the stars but their choices are their own. The gods are petty, magnificent, and terrifyingly powerful. They play favorites, hold grudges, and treat mortal lives like pieces on a game board. But mortals have something gods lack: the ability to change, to grow, to sacrifice without calculation. In the Age of Legends, every journey is an odyssey, every choice echoes through generations, and the greatest enemy is often hubris itself.',
 '{"timeOfDay":"dawn","weather":"golden-light","mood":"epic","dayNumber":1,"currentLocation":"The Crossroads of Three Kingdoms","currentScene":"A stone marker carved with symbols from an age before writing points in three directions. An eagle circles overhead—or is it watching? The morning light seems thicker here, like honey, and the air hums with the residue of a divine argument."}',
 '[{"name":"Demigod Wanderer","role":"Child of a god and a mortal, blessed with power and cursed with the attention of Olympus. Their divine parent''s enemies are now theirs"},{"name":"Oracle''s Champion","role":"Chosen by prophecy, which is both a gift and a cage. They know their destiny but not whether it ends in glory or ash"},{"name":"Monster-Born","role":"Descended from or transformed by a mythical creature. Feared by villages, sought by kings, struggling with a nature that isn''t entirely human"},{"name":"Mortal Scholar","role":"No divine blood, no prophecy—just knowledge, courage, and the conviction that cleverness can outmatch divinity. History suggests they might be right"}]',
 '{"combatSystem":"d20","magicEnabled":true,"magicSystem":"Divine gifts, heroic feats, and monster abilities. Mortal magic exists but is rare, ritualistic, and draws the attention of gods—for better or worse.","techLevel":"bronze-age-mythological","toneGuidance":"Epic in scope, personal in stakes. Gods are forces of nature with personalities—awe-inspiring and infuriating. Heroes are larger than life but deeply human: Achilles'' rage, Odysseus'' cunning, Arjuna''s doubt. Tragedy is always possible and sometimes inevitable, but so is transcendence. Fate exists but is not absolute—defying it has a cost, not a prohibition.","narratorStyle":"Elevated but not archaic. Describe landscapes as living mythology—a forest where a titan fell, a sea that remembers a god''s tears. Combat should feel mythic: each blow shakes the earth, each arrow is guided or cursed. Divine interventions should be dramatic but ambiguous—was that really a god, or just the wind? Let the mundane and the divine coexist uncomfortably.","forbiddenElements":["treating gods as video game bosses","consequence-free divine intervention","culturally disrespectful portrayals","reducing myths to simple good-vs-evil"],"divineSystem":"Gods have domains, favors, and grudges. Gaining one god''s blessing may anger another. Prayers have power but are not vending machines.","characterCreationGuidance":"Mythological NPCs should feel archetypal but specific. The trickster god has a particular way of smiling. The war goddess moves like a predator even in conversation. Monsters should evoke awe, not just fear—they were here before civilization and they remember. Mortal NPCs should embody human virtues and flaws writ large: loyalty that becomes obsession, pride that becomes ruin.","narratorFocus":"The tension between fate and free will. Every prophecy is true, but never in the way you expect.","escalationPattern":"Start with a personal quest (a wrong to right, a debt to pay). Mid-arc reveals the quest is entangled with divine politics. Climax places mortal agency against cosmic forces—and mortal agency has a chance."}',
 '{"epic":{"weather":"golden-light","lighting":"the sun seems closer, shadows are sharper","ambiance":"distant thunder that might be applause from the heavens"},"perilous":{"weather":"supernatural-storm","lighting":"lightning that writes words in the sky","ambiance":"the roar of something enormous and the ground trembling"},"sacred":{"weather":"perfect stillness","lighting":"a shaft of light that follows the hero","ambiance":"silence so deep you can hear the stars turning"}}',
 TRUE, TRUE)

ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description),
    defaultWorldState = VALUES(defaultWorldState),
    suggestedCharacterArchetypes = VALUES(suggestedCharacterArchetypes),
    rules = VALUES(rules),
    moodPresets = VALUES(moodPresets);

-- ============================================================
-- Seed Data: Built-in Achievements
-- ============================================================
INSERT INTO achievement (achievementId, name, description, icon, category, tier, xpReward, triggerCondition, isHidden, isBuiltIn) VALUES
('ach-story-001', 'First Words', 'Send your first message in a roleplay.', 'pencil', 'storytelling', 'bronze', 10, '{"type":"message_count","threshold":1}', FALSE, TRUE),
('ach-story-002', 'Wordsmith', 'Send 100 messages in roleplay conversations.', 'book-open', 'storytelling', 'silver', 50, '{"type":"message_count","threshold":100}', FALSE, TRUE),
('ach-story-003', 'Novelist', 'Send 1000 messages in roleplay conversations.', 'library', 'storytelling', 'gold', 200, '{"type":"message_count","threshold":1000}', FALSE, TRUE),
('ach-story-004', 'Plot Twist', 'Experience a Muse-detected plot reversal.', 'sparkles', 'storytelling', 'silver', 75, '{"type":"muse_event","event":"plot_twist"}', TRUE, TRUE),
('ach-combat-001', 'First Blood', 'Win your first combat encounter.', 'sword', 'combat', 'bronze', 15, '{"type":"combat_win","threshold":1}', FALSE, TRUE),
('ach-combat-002', 'Critical Hit', 'Roll a natural 20 on a d20.', 'target', 'combat', 'silver', 25, '{"type":"dice_roll","value":20,"die":"d20"}', FALSE, TRUE),
('ach-combat-003', 'Survivor', 'Survive an encounter with 1 HP remaining.', 'heart', 'combat', 'gold', 100, '{"type":"low_hp_survive","threshold":1}', TRUE, TRUE),
('ach-social-001', 'Smooth Talker', 'Raise a relationship intensity to maximum.', 'heart-handshake', 'social', 'silver', 50, '{"type":"relationship_max","threshold":1.0}', FALSE, TRUE),
('ach-social-002', 'Nemesis', 'Create an enemy relationship.', 'skull', 'social', 'bronze', 20, '{"type":"relationship_type","value":"enemy"}', FALSE, TRUE),
('ach-explore-001', 'Cartographer', 'Discover 10 locations.', 'map', 'exploration', 'silver', 50, '{"type":"locations_discovered","threshold":10}', FALSE, TRUE),
('ach-explore-002', 'Collector', 'Collect 50 unique items.', 'package', 'exploration', 'gold', 100, '{"type":"unique_items","threshold":50}', FALSE, TRUE),
('ach-meta-001', 'Night Owl', 'Play past midnight.', 'moon', 'meta', 'bronze', 10, '{"type":"play_time","after":"00:00","before":"04:00"}', TRUE, TRUE),
('ach-meta-002', 'Marathon', 'Play a session lasting 4 hours.', 'timer', 'meta', 'silver', 50, '{"type":"session_duration","minutes":240}', FALSE, TRUE),
('ach-meta-003', 'Multiversal', 'Create 5 story branches.', 'git-branch', 'meta', 'gold', 150, '{"type":"branch_count","threshold":5}', FALSE, TRUE)
ON DUPLICATE KEY UPDATE name = name;

-- ============================================================
-- Journey State (step-by-step narrated travel)
-- ============================================================
CREATE TABLE IF NOT EXISTS journey_state (
    journeyId VARCHAR(36) PRIMARY KEY,
    conversationId VARCHAR(36) NOT NULL,
    mapId VARCHAR(36) NOT NULL,
    userId VARCHAR(36) NOT NULL,
    characterIds JSON DEFAULT NULL,
    fromLocationId VARCHAR(36) NOT NULL,
    fromLocationName VARCHAR(255) DEFAULT NULL,
    toLocationId VARCHAR(36) NOT NULL,
    toLocationName VARCHAR(255) DEFAULT NULL,
    waypoints JSON DEFAULT NULL,
    totalSteps INT NOT NULL DEFAULT 1,
    currentStep INT NOT NULL DEFAULT 0,
    status ENUM('TRAVELING','PAUSED','INTERRUPTED','COMPLETED','CANCELLED') NOT NULL DEFAULT 'TRAVELING',
    interruptReason TEXT DEFAULT NULL,
    distance FLOAT DEFAULT 0,
    effectiveCost FLOAT DEFAULT 0,
    timePeriodsRequired INT DEFAULT 1,
    usesRoad BOOLEAN DEFAULT FALSE,
    stepResults LONGTEXT DEFAULT NULL,
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_journey_conversation (conversationId),
    INDEX idx_journey_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Deferred Foreign Keys (cross-references between tables defined in different order)
-- ============================================================
ALTER TABLE conversation
    ADD CONSTRAINT fk_conversation_story_template FOREIGN KEY (storyTemplateId) REFERENCES story_template(templateId) ON DELETE SET NULL,
    ADD CONSTRAINT fk_conversation_active_scene FOREIGN KEY (activeSceneId) REFERENCES scene(sceneId) ON DELETE SET NULL;

ALTER TABLE message
    ADD CONSTRAINT fk_message_scene FOREIGN KEY (sceneId) REFERENCES scene(sceneId) ON DELETE SET NULL;

ALTER TABLE knowledge_entry
    ADD CONSTRAINT fk_knowledge_scene FOREIGN KEY (sceneId) REFERENCES scene(sceneId) ON DELETE SET NULL;

-- Journey state foreign keys (deferred due to table ordering)
ALTER TABLE journey_state
    ADD CONSTRAINT fk_journey_conversation FOREIGN KEY (conversationId) REFERENCES conversation(conversationGUID) ON DELETE CASCADE,
    ADD CONSTRAINT fk_journey_map FOREIGN KEY (mapId) REFERENCES world_map(mapId) ON DELETE CASCADE,
    ADD CONSTRAINT fk_journey_user FOREIGN KEY (userId) REFERENCES user(userUUID) ON DELETE CASCADE,
    ADD CONSTRAINT fk_journey_from_location FOREIGN KEY (fromLocationId) REFERENCES map_location(locationId) ON DELETE CASCADE,
    ADD CONSTRAINT fk_journey_to_location FOREIGN KEY (toLocationId) REFERENCES map_location(locationId) ON DELETE CASCADE;

-- ============================================================
-- End of Schema
-- ============================================================
