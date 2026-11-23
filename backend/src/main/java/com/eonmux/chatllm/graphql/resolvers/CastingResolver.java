package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.models.CharacterProposal;
import com.eonmux.chatllm.services.CastingService;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
@Slf4j
@GraphQLApi
@ApplicationScoped
public class CastingResolver {

    @Inject
    Vertx vertx;
    @Inject
    Pool mysqlPool;
    @Inject
    CastingService castingService;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    com.eonmux.chatllm.utils.ServiceHelper serviceHelper;

    /**
     * Verify the caller owns or actively participates in the conversation a
     * proposal belongs to, before approving or rejecting it. Previously any
     * authenticated user could approve/reject any proposal.
     */
    private Uni<Boolean> canActOnProposal(String proposalId, String userId) {
        return castingService.getProposal(proposalId)
            .chain(proposal -> {
                if (proposal == null) {
                    return Uni.createFrom().failure(new RuntimeException("Proposal not found: " + proposalId));
                }
                return serviceHelper.verifyConversationAccess(proposal.getConversationId(), userId);
            });
    }


    /**
     * Query: characterProposals(conversationId: ID!): [CharacterProposal!]!
     */
    @Query
    public Uni<List<CharacterProposal>> characterProposals(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return serviceHelper.verifyConversationAccess(conversationId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                }
                return castingService.getProposalsForConversation(conversationId);
            })
            .onFailure().invoke(err -> log.error("Failed to fetch character proposals for conversation {}", conversationId, err));
    }

    /**
     * Mutation: approveCharacterProposal(proposalId: ID!): Character
     */
    @Mutation
    public Uni<Character> approveCharacterProposal(@Name("proposalId") String proposalId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return canActOnProposal(proposalId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }
                return castingService.approveProposal(proposalId, userId)
                    .map(this::jsonToCharacter);
            })
            .onFailure().invoke(err -> log.error("Failed to approve character proposal {}", proposalId, err));
    }

    /**
     * Mutation: rejectCharacterProposal(proposalId: ID!): Boolean!
     */
    @Mutation
    public Uni<Boolean> rejectCharacterProposal(@Name("proposalId") String proposalId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        return canActOnProposal(proposalId, auth.getUserId().toString())
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }
                return castingService.rejectProposal(proposalId).map(v -> true);
            })
            .onFailure().invoke(err -> log.error("Failed to reject character proposal {}", proposalId, err));
    }

    /**
     * Convert a character JsonObject from the event bus into a Character model.
     */
    private Character jsonToCharacter(JsonObject json) {
        String idStr = json.getString("id", json.getString("characterUUID"));
        return Character.builder()
            .id(idStr != null ? UUID.fromString(idStr) : null)
            .name(json.getString("name"))
            .description(json.getString("description"))
            .systemPrompt(json.getString("systemPrompt"))
            .greeting(json.getString("greeting"))
            .personality(json.getString("personality"))
            .appearance(json.getString("appearance"))
            .gender(json.getString("gender"))
            .pronouns(json.getString("pronouns"))
            .ageYears(json.getInteger("ageYears"))
            .height(json.getString("height"))
            .build(json.getString("build"))
            .hair(json.getString("hair"))
            .eyes(json.getString("eyes"))
            .strength(json.getInteger("strength"))
            .dexterity(json.getInteger("dexterity"))
            .constitution(json.getInteger("constitution"))
            .intelligence(json.getInteger("intelligence"))
            .wisdom(json.getInteger("wisdom"))
            .charisma(json.getInteger("charisma"))
            .backstory(json.getString("backstory"))
            .goals(json.getString("goals"))
            .isPublic(json.getBoolean("isPublic", false))
            .createdAt(LocalDateTime.now(ZoneOffset.UTC).toString())
            .build();
    }
}
