package com.eonmux.chatllm.agents;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import io.vertx.core.json.JsonObject;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {
    private String agentName;
    private String content;          // Main text output
    private JsonObject structuredOutput; // Parsed JSON output (for Highlighter, Accountant, etc.)
    private String status;           // "success", "error", "skipped", "rejected"
    private String modelUsed;
    private Integer inputTokens;
    private Integer outputTokens;
    private Long durationMs;
    private Integer retryCount;
    @Builder.Default
    private Map<String, String> annotations = new HashMap<>();

    public void addAnnotation(String key, String value) {
        if (annotations == null) annotations = new HashMap<>();
        annotations.put(key, value);
    }

    public boolean isSuccess() {
        return "success".equals(status);
    }

    public static AgentResult skipped(String agentName, String reason) {
        return AgentResult.builder()
            .agentName(agentName)
            .status("skipped")
            .content(reason)
            .build();
    }

    public static AgentResult error(String agentName, String message) {
        return AgentResult.builder()
            .agentName(agentName)
            .status("error")
            .content(message)
            .build();
    }
}
