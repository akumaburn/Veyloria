package com.eonmux.chatllm.services;

import com.eonmux.chatllm.testing.SqlTestSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AchievementIdentifierTest {
    @Test
    void catalogKeepsSeededStringIdentifiers() {
        AchievementService service = new AchievementService();
        service.pool = SqlTestSupport.pool((sql, parameters) -> SqlTestSupport.rows(
                SqlTestSupport.row(Map.of(
                        "achievementId", "ach-combat-002", "name", "Critical Hit",
                        "description", "Roll a natural 20 on a d20.", "icon", "target",
                        "category", "combat", "tier", "silver", "xpReward", 25,
                        "isHidden", false, "isBuiltIn", true,
                        "createdAt", LocalDateTime.of(2026, 1, 1, 0, 0)))));
        var achievements = service.getAllAchievements().await().indefinitely();
        assertEquals(1, achievements.size());
        assertEquals("ach-combat-002", achievements.getFirst().getAchievementId());
        assertEquals("Critical Hit", achievements.getFirst().getName());
    }
}
