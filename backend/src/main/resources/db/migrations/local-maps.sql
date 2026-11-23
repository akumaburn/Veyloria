-- Interior layouts for world-map nodes. A node such as a castle holds a
-- connected graph of areas (great hall, kitchen, courtyard, gatehouse) so the
-- scene has one authoritative place and a finite set of exits.
CREATE TABLE IF NOT EXISTS local_area (
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

CREATE TABLE IF NOT EXISTS local_area_connection (
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
