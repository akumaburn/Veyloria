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
