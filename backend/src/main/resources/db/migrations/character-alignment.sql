-- Additive and safe to run repeatedly; preserves alignment points and history.
-- actor is maintained by the service. A stored generated column cannot feed a
-- CASCADE foreign key on MySQL, so it is an explicit column.
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
