package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.eonmux.chatllm.config.RoleplayEventAddresses;
import com.eonmux.chatllm.models.CharacterProposal;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Slf4j
public class CastingService {

    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    // EventBus obtained from vertx.eventBus()

    /**
     * Persist a new character proposal.
     */
    public Uni<CharacterProposal> createProposal(CharacterProposal proposal) {
        if (proposal.getProposalId() == null) {
            proposal.setProposalId(UUID.randomUUID().toString());
        }
        if (proposal.getStatus() == null) {
            proposal.setStatus("PENDING");
        }

        String insert = """
            INSERT INTO character_proposal (proposalId, conversationId, triggerMessageId, castingPrompt,
                proposedName, proposedDescription, proposedSystemPrompt, proposedGreeting,
                proposedPersonality, proposedAppearance, proposedBackstory, proposedGoals,
                accountantReview, skepticReview, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        return pool.preparedQuery(insert)
            .execute(Tuple.tuple()
                .addValue(proposal.getProposalId())
                .addValue(proposal.getConversationId())
                .addValue(proposal.getTriggerMessageId())
                .addValue(proposal.getCastingPrompt())
                .addValue(proposal.getProposedName())
                .addValue(proposal.getProposedDescription())
                .addValue(proposal.getProposedSystemPrompt())
                .addValue(proposal.getProposedGreeting())
                .addValue(proposal.getProposedPersonality())
                .addValue(proposal.getProposedAppearance())
                .addValue(proposal.getProposedBackstory())
                .addValue(proposal.getProposedGoals())
                .addValue(proposal.getAccountantReview())
                .addValue(proposal.getSkepticReview())
                .addValue(proposal.getStatus()))
            .map(rows -> {
                log.info("[casting] Created proposal {} for conversation {}",
                    proposal.getProposalId(), proposal.getConversationId());

                // Publish event for characterProposalCreated subscription
                JsonObject event = new JsonObject()
                    .put("proposalId", proposal.getProposalId())
                    .put("conversationId", proposal.getConversationId())
                    .put("triggerMessageId", proposal.getTriggerMessageId())
                    .put("castingPrompt", proposal.getCastingPrompt())
                    .put("proposedName", proposal.getProposedName())
                    .put("proposedDescription", proposal.getProposedDescription())
                    .put("proposedSystemPrompt", proposal.getProposedSystemPrompt())
                    .put("proposedGreeting", proposal.getProposedGreeting())
                    .put("proposedPersonality", proposal.getProposedPersonality())
                    .put("proposedAppearance", proposal.getProposedAppearance())
                    .put("proposedBackstory", proposal.getProposedBackstory())
                    .put("proposedGoals", proposal.getProposedGoals())
                    .put("status", proposal.getStatus());
                vertx.eventBus().publish(RoleplayEventAddresses.CASTING_PROPOSAL + proposal.getConversationId(), event);

                return proposal;
            });
    }

    /**
     * Get all proposals for a conversation.
     */
    public Uni<List<CharacterProposal>> getProposalsForConversation(String conversationId) {
        String query = "SELECT * FROM character_proposal WHERE conversationId = ? ORDER BY createdAt DESC";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .map(this::mapRows);
    }

    /**
     * Get only pending proposals for a conversation.
     */
    public Uni<List<CharacterProposal>> getPendingProposals(String conversationId) {
        String query = "SELECT * FROM character_proposal WHERE conversationId = ? AND status = 'PENDING' ORDER BY createdAt DESC";
        return pool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .map(this::mapRows);
    }

    /**
     * Get a single proposal by ID.
     */
    public Uni<CharacterProposal> getProposal(String proposalId) {
        String query = "SELECT * FROM character_proposal WHERE proposalId = ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(proposalId))
            .map(rows -> {
                if (rows.size() == 0) return null;
                return CharacterProposal.fromRow(rows.iterator().next());
            });
    }

    /**
     * Update proposal status and optionally set createdCharacterId.
     */
    public Uni<Void> updateProposalStatus(String proposalId, String status, String createdCharacterId) {
        String update = "UPDATE character_proposal SET status = ?, createdCharacterId = ? WHERE proposalId = ?";
        return pool.preparedQuery(update)
            .execute(Tuple.of(status, createdCharacterId, proposalId))
            .replaceWithVoid();
    }

    /**
     * Approve a proposal: atomically claim the PENDING->APPROVED transition,
     * then create the character and add it to the group conversation.
     *
     * The status claim is a single guarded UPDATE (WHERE status='PENDING') so
     * two concurrent approvals can't both pass the check and create duplicate
     * characters. If the claim affects 0 rows the proposal was already
     * approved/rejected and we fail instead of proceeding.
     */
    public Uni<JsonObject> approveProposal(String proposalId, String userId) {
        return getProposal(proposalId)
            .chain(proposal -> {
                if (proposal == null) {
                    return Uni.createFrom().failure(new RuntimeException("Proposal not found: " + proposalId));
                }

                // Atomic claim: only one caller can move PENDING -> APPROVED.
                String claim = "UPDATE character_proposal SET status = ? WHERE proposalId = ? AND status = 'PENDING'";
                return pool.preparedQuery(claim)
                    .execute(Tuple.of("APPROVED", proposalId))
                    .chain(claimResult -> {
                        if (claimResult.rowCount() == 0) {
                            return Uni.createFrom().failure(new RuntimeException(
                                "Proposal is not pending: " + proposal.getStatus()));
                        }

                        // Create character via event bus (CharacterServiceVerticle handles this)
                        JsonObject createReq = new JsonObject()
                            .put("userId", userId)
                            .put("name", proposal.getProposedName())
                            .put("description", proposal.getProposedDescription())
                            .put("systemPrompt", proposal.getProposedSystemPrompt())
                            .put("greeting", proposal.getProposedGreeting())
                            .put("personality", proposal.getProposedPersonality())
                            .put("appearance", proposal.getProposedAppearance())
                            .put("backstory", proposal.getProposedBackstory())
                            .put("goals", proposal.getProposedGoals())
                            .put("isPublic", false);

                        return vertx.eventBus().<JsonObject>request("character.create", createReq)
                            .chain(charReply -> {
                                JsonObject character = charReply.body();
                                String characterId = character.getString("id", character.getString("characterUUID"));

                                // Add character to the group conversation
                                JsonObject addReq = new JsonObject()
                                    .put("conversationId", proposal.getConversationId())
                                    .put("characterId", characterId)
                                    .put("userId", userId);

                                return vertx.eventBus().<JsonObject>request("groupchat.addCharacter", addReq)
                                    .chain(addReply -> {
                                        // Record the created character id on the (already claimed) row.
                                        return updateProposalStatus(proposalId, "APPROVED", characterId)
                                            .map(v -> {
                                                log.info("[casting] Approved proposal {} → character {}",
                                                    proposalId, characterId);
                                                return character;
                                            });
                                    })
                                    .onFailure().recoverWithUni(addErr -> {
                                        // Character was created but adding to conversation failed.
                                        // Still mark as approved so the character isn't orphaned in limbo.
                                        log.error("[casting] Failed to add character {} to conversation {}, marking proposal as approved anyway",
                                            characterId, proposal.getConversationId(), addErr);
                                        return updateProposalStatus(proposalId, "APPROVED", characterId)
                                            .map(v -> character);
                                    });
                            })
                            .onFailure().recoverWithUni(createErr -> {
                                // Character creation itself failed AFTER we claimed the
                                // proposal. Roll the status back to PENDING so the user
                                // can retry, instead of leaving it stuck as APPROVED.
                                log.error("[casting] Failed to create character for proposal {}, rolling back status", proposalId, createErr);
                                return updateProposalStatus(proposalId, "PENDING", null)
                                    .replaceWith(Uni.createFrom().<JsonObject>failure(createErr));
                            });
                    });
            });
    }

    /**
     * Reject a proposal. Only succeeds on a still-pending proposal; a guarded
     * UPDATE prevents a concurrent approve from being clobbered by a reject
     * (and vice versa) and reports failure instead of a no-op success.
     */
    public Uni<Void> rejectProposal(String proposalId) {
        String reject = "UPDATE character_proposal SET status = ?, createdCharacterId = NULL WHERE proposalId = ? AND status = 'PENDING'";
        return pool.preparedQuery(reject)
            .execute(Tuple.of("REJECTED", proposalId))
            .chain(result -> {
                if (result.rowCount() == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Proposal is not pending: " + proposalId));
                }
                log.info("[casting] Rejected proposal {}", proposalId);
                return Uni.createFrom().voidItem();
            });
    }

    private List<CharacterProposal> mapRows(RowSet<Row> rows) {
        List<CharacterProposal> list = new ArrayList<>();
        for (Row row : rows) {
            list.add(CharacterProposal.fromRow(row));
        }
        return list;
    }
}
