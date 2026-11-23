package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.*;
import com.eonmux.chatllm.models.inputs.*;
import com.eonmux.chatllm.services.InviteService;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Source;

/**
 * GraphQL resolver for invite and participant management.
 * Handles multi-user conversations, invitations, and participant operations.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class InviteResolver {

    @Inject
    InviteService inviteService;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    jakarta.enterprise.inject.Instance<io.smallrye.graphql.api.Context> graphqlInputContext;

    @Inject
    Pool mysqlPool;

    @Inject
    com.eonmux.chatllm.utils.ServiceHelper serviceHelper;

    // ==========================================
    // QUERY RESOLVERS
    // ==========================================

    /**
     * Get pending invites for the current user.
     * Schema: myInvites(filter: InviteFilter, limit: Int, offset: Int): InvitePagination!
     */
    @Query
    public Uni<InvitePagination> myInvites(@Name("filter") InviteFilter filter, @Name("limit") Integer limit, @Name("offset") Integer offset) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String status = filter != null ? filter.getStatus() : null;
        final int effectiveLimit = limit != null ? limit : 20;
        final int effectiveOffset = offset != null ? offset : 0;

        return inviteService.getMyInvites(auth.getUserId().toString(), status, effectiveLimit, effectiveOffset)
                .chain(invites -> inviteService.getPendingInviteCount(auth.getUserId().toString())
                        .map(count -> InvitePagination.builder()
                                .items(invites)
                                .totalCount(count)
                                .hasMore(effectiveOffset + invites.size() < count)
                                .build()));
    }

    /**
     * Get participants for a conversation.
     * Schema: conversationParticipants(conversationId: ID!): [ConversationParticipant!]!
     */
    @Query
    public Uni<List<ConversationParticipant>> conversationParticipants(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return serviceHelper.verifyConversationAccess(conversationId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                }
                return inviteService.getParticipants(conversationId);
            });
    }

    /**
     * Search users for invite autocomplete.
     * Schema: searchUsers(query: String!, limit: Int): [User!]!
     */
    @Query
    public Uni<List<User>> searchUsers(@Name("query") String query, @Name("limit") Integer limit) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        if (limit == null) limit = 10;

        return inviteService.searchUsers(query, limit)
                .map(users -> users.stream()
                        .filter(user -> !user.getId().equals(auth.getUserId()))
                        .collect(Collectors.toList()));
    }

    /**
     * Get invites for a specific conversation.
     * Schema: conversationInvites(conversationId: ID!): [ConversationInvite!]!
     */
    @Query
    public Uni<List<ConversationInvite>> conversationInvites(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        // Invites contain invitee identity/status — only the conversation
        // owner or an admin participant may list them.
        return serviceHelper.verifyConversationAccess(conversationId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                }
                return inviteService.getConversationInvites(conversationId);
            });
    }

    /**
     * Check if a user can be invited to a conversation.
     * Schema: canInviteUser(conversationId: ID!, usernameOrEmail: String!): Boolean!
     */
    @Query
    public Uni<Boolean> canInviteUser(@Name("conversationId") String conversationId, @Name("usernameOrEmail") String usernameOrEmail) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return serviceHelper.verifyConversationAccess(conversationId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                }
                return inviteService.canInviteUser(conversationId, usernameOrEmail);
            });
    }

    // ==========================================
    // MUTATION RESOLVERS
    // ==========================================

    /**
     * Invite a user to a conversation.
     * Schema: inviteUserToConversation(input: InviteUserInput!): ConversationInvite!
     */
    @Mutation
    public Uni<ConversationInvite> inviteUserToConversation(@Name("input") InviteUserInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String usernameOrEmail = (String) input.get("usernameOrEmail");
        String message = (String) input.get("message");
        String roleToGrant = (String) input.get("roleToGrant");

        return inviteService.inviteUser(
                auth.getUserId().toString(),
                conversationId,
                usernameOrEmail,
                message,
                roleToGrant
        );
    }

    /**
     * Respond to an invite (accept or decline).
     * Schema: respondToInvite(input: RespondToInviteInput!): ConversationInvite!
     */
    @Mutation
    public Uni<ConversationInvite> respondToInvite(@Name("input") RespondToInviteInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String inviteId = (String) input.get("inviteId");
        Boolean accept = (Boolean) input.get("accept");

        if (accept) {
            return inviteService.acceptInvite(inviteId, auth.getUserId().toString());
        } else {
            return inviteService.declineInvite(inviteId, auth.getUserId().toString());
        }
    }

    /**
     * Cancel an invite.
     * Schema: cancelInvite(inviteId: ID!): Boolean!
     */
    @Mutation
    public Uni<Boolean> cancelInvite(@Name("inviteId") String inviteId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        return inviteService.cancelInvite(inviteId, auth.getUserId().toString());
    }

    /**
     * Resend an invite (resets status to pending and refreshes expiration).
     * Schema: resendInvite(inviteId: ID!): ConversationInvite!
     */
    @Mutation
    public Uni<ConversationInvite> resendInvite(@Name("inviteId") String inviteId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        return inviteService.resendInvite(inviteId, auth.getUserId().toString());
    }

    /**
     * Update a participant's settings.
     * Schema: updateParticipant(input: UpdateParticipantInput!): ConversationParticipant!
     */
    @Mutation
    public Uni<ConversationParticipant> updateParticipant(@Name("input") UpdateParticipantInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String userId = (String) input.get("userId");
        String role = (String) input.get("role");
        Boolean canInvite = (Boolean) input.get("canInvite");
        Boolean canAddCharacters = (Boolean) input.get("canAddCharacters");
        String nickname = (String) input.get("nickname");

        return inviteService.updateParticipant(
                conversationId,
                userId,
                auth.getUserId().toString(),
                role,
                canInvite,
                canAddCharacters,
                nickname
        );
    }

    /**
     * Remove a participant from a conversation.
     * Schema: removeParticipant(input: RemoveParticipantInput!): Boolean!
     */
    @Mutation
    public Uni<Boolean> removeParticipant(@Name("input") RemoveParticipantInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String userId = (String) input.get("userId");

        return inviteService.removeParticipant(conversationId, userId, auth.getUserId().toString());
    }

    /**
     * Leave a conversation.
     * Schema: leaveConversation(conversationId: ID!): Boolean!
     */
    @Mutation
    public Uni<Boolean> leaveConversation(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        return inviteService.leaveConversation(conversationId, auth.getUserId().toString());
    }

    /**
     * Transfer ownership of a conversation.
     * Schema: transferOwnership(input: TransferOwnershipInput!): Conversation!
     */
    @Mutation
    public Uni<Conversation> transferOwnership(@Name("input") TransferOwnershipInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String newOwnerId = (String) input.get("newOwnerId");

        return inviteService.transferOwnership(conversationId, auth.getUserId().toString(), newOwnerId);
    }

    /**
     * Mark conversation as read.
     * Schema: markConversationRead(conversationId: ID!): ConversationParticipant!
     */
    @Mutation
    public Uni<ConversationParticipant> markConversationRead(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        return inviteService.markAsRead(conversationId, auth.getUserId().toString());
    }

    // ==========================================
    // FIELD RESOLVERS (ConversationInvite)
    // ==========================================

    /**
     * Resolve the conversation for an invite.
     * The service layer's row mapper populates this field via JOIN, so we
     * normally return it directly. Fallback query keeps field resolution
     * correct if the invite was produced outside that path.
     */
    public Uni<Conversation> conversation(@Source ConversationInvite invite) {
        if (invite.getConversation() != null) {
            return Uni.createFrom().item(invite.getConversation());
        }
        if (invite.getConversationId() == null) {
            return Uni.createFrom().nullItem();
        }
        return mysqlPool.preparedQuery(
                "SELECT conversationGUID, title FROM conversation WHERE conversationGUID = ?")
                .execute(Tuple.of(invite.getConversationId().toString()))
                .map(rows -> {
                    if (rows.size() == 0) return null;
                    Row row = rows.iterator().next();
                    Conversation conv = new Conversation();
                    conv.setId(UUID.fromString(row.getString("conversationGUID")));
                    conv.setTitle(row.getString("title"));
                    return conv;
                });
    }

    /**
     * Resolve the user who sent the invite.
     */
    public Uni<User> inviter(@Source ConversationInvite invite) {
        if (invite.getInviter() != null) {
            return Uni.createFrom().item(invite.getInviter());
        }
        if (invite.getInviterId() == null) {
            return Uni.createFrom().nullItem();
        }
        return lookupUserSummary(invite.getInviterId());
    }

    /**
     * Resolve the user being invited. Nullable — email-only invites set only
     * {@code inviteeEmail}/{@code inviteeUsername} and leave this null until
     * the target registers and claims the invite.
     */
    public Uni<User> invitee(@Source ConversationInvite invite) {
        if (invite.getInvitee() != null) {
            return Uni.createFrom().item(invite.getInvitee());
        }
        if (invite.getInviteeId() == null) {
            return Uni.createFrom().nullItem();
        }
        return lookupUserSummary(invite.getInviteeId());
    }

    private Uni<User> lookupUserSummary(UUID userId) {
        return mysqlPool.preparedQuery(
                "SELECT userUUID, username, displayName, profilePicture FROM user WHERE userUUID = ?")
                .execute(Tuple.of(userId.toString()))
                .map(rows -> {
                    if (rows.size() == 0) return null;
                    Row row = rows.iterator().next();
                    User u = new User();
                    u.setId(UUID.fromString(row.getString("userUUID")));
                    u.setUsername(row.getString("username"));
                    u.setDisplayName(row.getString("displayName"));
                    u.setProfilePicture(row.getString("profilePicture"));
                    return u;
                });
    }
}
