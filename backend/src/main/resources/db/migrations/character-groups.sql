-- Roster provenance. A group records which characters were generated together;
-- deleting a group never deletes its characters.
CREATE TABLE IF NOT EXISTS character_group (
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

CREATE TABLE IF NOT EXISTS character_group_member (
    groupId VARCHAR(36) NOT NULL,
    characterId VARCHAR(36) NOT NULL,
    position INT NOT NULL DEFAULT 0,
    createdAt DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (groupId, characterId),
    FOREIGN KEY (groupId) REFERENCES character_group(groupUUID) ON DELETE CASCADE,
    FOREIGN KEY (characterId) REFERENCES `character`(characterUUID) ON DELETE CASCADE,
    INDEX idx_character_group_member_character (characterId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
