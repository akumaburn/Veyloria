package com.eonmux.chatllm.models;

import io.vertx.mutiny.sqlclient.Row;
import com.eonmux.chatllm.utils.Time4JHelper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharacterProposal {
    private String proposalId;
    private String conversationId;
    private String triggerMessageId;
    private String castingPrompt;
    private String proposedName;
    private String proposedDescription;
    private String proposedSystemPrompt;
    private String proposedGreeting;
    private String proposedPersonality;
    private String proposedAppearance;
    private String proposedBackstory;
    private String proposedGoals;
    private String accountantReview;   // JSON string
    private String skepticReview;      // JSON string
    private String status;             // PENDING, APPROVED, REJECTED, FAILED
    private String createdCharacterId;
    private String createdAt;
    private String updatedAt;

    public static CharacterProposal fromRow(Row row) {
        CharacterProposal p = new CharacterProposal();
        p.setProposalId(row.getString("proposalId"));
        p.setConversationId(row.getString("conversationId"));
        p.setTriggerMessageId(row.getString("triggerMessageId"));
        p.setCastingPrompt(row.getString("castingPrompt"));
        p.setProposedName(row.getString("proposedName"));
        p.setProposedDescription(row.getString("proposedDescription"));
        p.setProposedSystemPrompt(row.getString("proposedSystemPrompt"));
        p.setProposedGreeting(row.getString("proposedGreeting"));
        p.setProposedPersonality(row.getString("proposedPersonality"));
        p.setProposedAppearance(row.getString("proposedAppearance"));
        p.setProposedBackstory(row.getString("proposedBackstory"));
        p.setProposedGoals(row.getString("proposedGoals"));
        // JSON columns come back as strings
        Object accountantReviewObj = row.getValue("accountantReview");
        p.setAccountantReview(accountantReviewObj != null ? accountantReviewObj.toString() : null);
        Object skepticReviewObj = row.getValue("skepticReview");
        p.setSkepticReview(skepticReviewObj != null ? skepticReviewObj.toString() : null);
        p.setStatus(row.getString("status"));
        p.setCreatedCharacterId(row.getString("createdCharacterId"));
        p.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        p.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return p;
    }
}
