-- Additive and safe to run repeatedly; preserves conversation-scoped relationships.
-- fromActor and toActor are maintained by the service. A stored generated column
-- cannot feed a CASCADE foreign key on MySQL, so they are explicit columns.
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
