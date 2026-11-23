package com.eonmux.chatllm.agents;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkepticVerdict {
    private boolean approved;
    private String reason;
    private String severity;    // "minor", "moderate", "critical"
    private String category;    // "metagaming", "ooc", "world_rule", "deus_ex_machina", etc.
    private String suggestion;  // Suggested correction

    public static SkepticVerdict approve() {
        return SkepticVerdict.builder().approved(true).build();
    }

    public static SkepticVerdict reject(String reason, String category, String severity) {
        return SkepticVerdict.builder()
            .approved(false)
            .reason(reason)
            .category(category)
            .severity(severity)
            .build();
    }
}
