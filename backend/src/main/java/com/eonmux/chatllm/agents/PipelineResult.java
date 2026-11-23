package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.models.CharacterProposal;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineResult {
    private io.vertx.core.json.JsonObject arrivalResident;
    private String narration;          // Narrator output (may be null if no scene change)
    private String characterResponse;  // Character's response text
    private AgentResult highlighterResult;
    private AgentResult museResult;
    private AgentResult accountantResult;
    private AgentResult narratorResult;
    private AgentResult skepticNarrationResult;
    private AgentResult skepticCharacterResult;
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    @Builder.Default
    private List<String> castingTriggers = new ArrayList<>();
    @Builder.Default
    private List<CharacterProposal> castingProposals = new ArrayList<>();
    private Long totalDurationMs;
    private boolean failed;
    private String errorMessage;

    public boolean hasNarration() {
        return narration != null && !narration.isEmpty();
    }

    public boolean isSuccess() {
        return !failed;
    }

    public void addWarning(String warning) {
        if (warnings == null) warnings = new ArrayList<>();
        warnings.add(warning);
    }
}
