package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.models.Conversation;
import com.eonmux.chatllm.models.ConversationCharacter;
import com.eonmux.chatllm.models.InterjectionRequest;
import com.eonmux.chatllm.models.Message;
import com.eonmux.chatllm.models.MessageRole;
import com.eonmux.chatllm.models.inputs.*;
import com.eonmux.chatllm.services.GroupChatService;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Source;
import io.vertx.mutiny.sqlclient.Row;
import java.time.ZoneOffset;

/**
 * GraphQL resolver for group chat mutations.
 * Handles multi-character conversations, turn management, and interjections.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class GroupChatResolver {

    @Inject
    Vertx vertx;
    @Inject
    Pool mysqlPool;
    @Inject
    GroupChatService groupChatService;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    jakarta.enterprise.inject.Instance<io.smallrye.graphql.api.Context> graphqlInputContext;

    /**
     * Create a new group conversation.
     * Schema: createGroupConversation(input: CreateGroupConversationInput!): Conversation!
     */
    @Mutation
    public Uni<Conversation> createGroupConversation(@Name("input") CreateGroupConversationInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String title = (String) input.get("title");
        @SuppressWarnings("unchecked")
        List<String> characterIds = (List<String>) input.get("characterIds");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> charactersWithWeights = (List<Map<String, Object>>) input.get("charactersWithWeights");
        String turnMode = (String) input.get("turnMode");
        Integer maxConsecutiveResponses = input.get("maxConsecutiveResponses") instanceof Number number
                ? number.intValue() : null;
        String category = (String) input.get("category");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) input.get("tags");

        // Build weight map from charactersWithWeights if provided
        Map<String, Double> weightMap = new HashMap<>();
        if (charactersWithWeights != null) {
            for (Map<String, Object> cw : charactersWithWeights) {
                String charId = (String) cw.get("characterId");
                Object weightObj = cw.get("weight");
                if (charId != null && weightObj != null) {
                    weightMap.put(charId, ((Number) weightObj).doubleValue());
                }
            }
        }

        return groupChatService.createGroupConversation(
                auth.getUserId().toString(),
                title,
                characterIds,
                weightMap,
                turnMode,
                maxConsecutiveResponses,
                category,
                tags
        );
    }

    /**
     * Add a character to a group conversation.
     * Schema: addCharacterToGroup(input: AddCharacterToGroupInput!): ConversationCharacter!
     */
    @Mutation
    public Uni<ConversationCharacter> addCharacterToGroup(@Name("input") AddCharacterToGroupInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String characterId = (String) input.get("characterId");
        Integer turnOrder = input.get("turnOrder") instanceof Number number
                ? number.intValue() : null;
        Double weight = input.get("weight") != null ? ((Number) input.get("weight")).doubleValue() : null;

        return groupChatService.addCharacterToGroup(
                conversationId,
                characterId,
                auth.getUserId().toString(),
                turnOrder,
                weight
        );
    }

    /**
     * Remove a character from a group conversation.
     * Schema: removeCharacterFromGroup(input: RemoveCharacterFromGroupInput!): Boolean!
     */
    @Mutation
    public Uni<Boolean> removeCharacterFromGroup(@Name("input") RemoveCharacterFromGroupInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String characterId = (String) input.get("characterId");

        return groupChatService.removeCharacterFromGroup(
                conversationId,
                characterId,
                auth.getUserId().toString()
        );
    }

    /**
     * Update group conversation settings.
     * Schema: updateGroupSettings(conversationId: ID!, input: UpdateGroupSettingsInput!): Conversation!
     */
    @Mutation
    public Uni<Conversation> updateGroupSettings(@Name("conversationId") String conversationId, @Name("input") UpdateGroupSettingsInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String turnMode = (String) input.get("turnMode");
        Integer maxConsecutiveResponses = input.get("maxConsecutiveResponses") instanceof Number number
                ? number.intValue() : null;

        return groupChatService.updateGroupSettings(
                conversationId,
                auth.getUserId().toString(),
                turnMode,
                maxConsecutiveResponses
        );
    }

    /**
     * Send a message in a group conversation.
     * This triggers the next character response after processing.
     * Schema: sendGroupMessage(input: SendGroupMessageInput!): Message!
     */
    @Mutation
    public Uni<Message> sendGroupMessage(@Name("input") SendGroupMessageInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String content = (String) input.get("content");
        String userId = auth.getUserId().toString();

        // Validate content is not empty
        if (content == null || content.trim().isEmpty()) {
            return Uni.createFrom().failure(new RuntimeException("Message content cannot be empty"));
        }
        final String trimmedContent = content.trim();

        // Verify conversation access (owner OR active participant). Owner-only
        // here blocked invited/group participants from sending messages.
        String verifyQuery = """
            SELECT c.groupState FROM conversation c
            LEFT JOIN conversation_participant cp
                ON c.conversationGUID = cp.conversation_id AND cp.user_id = ? AND cp.is_active = true
            WHERE c.conversationGUID = ? AND (c.isGroupChat = true OR c.isRoleplay = true)
              AND (c.ownerId = ? OR cp.user_id = ?)
            """;
        final String messageId = UUID.randomUUID().toString();
        final java.time.LocalDateTime now = java.time.LocalDateTime.now(ZoneOffset.UTC);
        final String displayName = auth.getEffectiveDisplayName();

        return mysqlPool.withTransaction(client -> client.preparedQuery(verifyQuery)
            .execute(Tuple.of(userId, conversationId, userId, userId))
            .chain(verifyResult -> {
                if (verifyResult.size() == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation not found or access denied"));
                }
                // A paused or still-initializing conversation cannot answer, and
                // accepting the row would leave the message unanswered forever.
                String groupState = verifyResult.iterator().next().getString("groupState");
                if ("PAUSED".equals(groupState)) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation is paused"));
                }
                if ("INITIALIZING".equals(groupState)) {
                    return Uni.createFrom().failure(new RuntimeException("Conversation is still starting"));
                }

                // Insert user message with displayName for group chat attribution
                String insertQuery = """
                    INSERT INTO message (messageUUID, conversationId, ownerId, content, role, displayName, createdAt)
                    VALUES (?, ?, ?, ?, 'user', ?, ?)
                    """;

                return client.preparedQuery(insertQuery)
                    .execute(Tuple.of(
                        messageId,
                        conversationId,
                        userId,
                        trimmedContent,
                        displayName,
                        now
                    ));
            })
            .chain(result -> {
                // Update the timestamp and message count. The response budget
                // resets when a turn is claimed, not here: a message that lands
                // during an active chain must not extend that chain.
                return client.preparedQuery("""
                    UPDATE conversation
                    SET lastModified = ?,
                        updatedAt = ?,
                        messageCount = IFNULL(messageCount, 0) + 1
                    WHERE conversationGUID = ?
                    """)
                    .execute(Tuple.of(now, now, conversationId));
            })
            .chain(result -> {
                // A user request for the floor is answered by this message, so
                // retire it in the same transaction.
                return client.preparedQuery("""
                    UPDATE interjection_request
                    SET status = 'consumed', resolvedAt = ?
                    WHERE conversationId = ? AND requesterType = 'user'
                      AND status IN ('pending', 'approved')
                    """)
                    .execute(Tuple.of(now, conversationId));
            })
            .chain(result -> {
                // Get the inserted message
                return client.preparedQuery("SELECT * FROM message WHERE messageUUID = ?")
                    .execute(Tuple.of(messageId));
            })
            .map(rows -> {
                if (rows.size() == 0) {
                    throw new RuntimeException("Failed to create message");
                }
                io.vertx.mutiny.sqlclient.Row row = rows.iterator().next();
                Message message = new Message();
                message.setId(UUID.fromString(row.getString("messageUUID")));
                message.setConversationId(UUID.fromString(row.getString("conversationId")));
                message.setContent(row.getString("content"));
                message.setRole(MessageRole.fromString(row.getString("role")));
                message.setDisplayName(row.getString("displayName"));
                message.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                java.time.LocalDateTime updatedAt = row.getLocalDateTime("updatedAt");
                message.setUpdatedAt(updatedAt != null ? updatedAt.toString() : Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                Integer editCount = row.getInteger("editCount");
                message.setEditCount(editCount != null ? editCount : 0);
                message.setOwnerId(UUID.fromString(row.getString("ownerId")));
                return message;
            }))
            .invoke(message -> {
                // A named offstage character comes onstage; an ordinary
                // message does not add a stranger. The character chain then
                // runs within its own turn budget.
                groupChatService.revealAddressedCastMember(conversationId)
                        .onFailure().recoverWithItem((String) null)
                        .subscribe().with(ignored -> triggerCharacterResponseAsync(conversationId));
            });
    }

    /**
     * Trigger the next character to respond in a group conversation.
     * Schema: triggerNextCharacterResponse(conversationId: ID!): Message!
     */
    @Mutation
    public Uni<Message> triggerNextCharacterResponse(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String userId = auth.getUserId().toString();

        // Only the owner may spend a manual turn. GroupChatService then claims
        // the same way a typed message does, so a continue cannot start a
        // second speaker beside an existing turn.
        return mysqlPool.preparedQuery(
                "SELECT COUNT(*) AS count FROM conversation WHERE conversationGUID = ? AND ownerId = ?")
            .execute(Tuple.of(conversationId, userId))
            .chain(owned -> {
                if (owned.iterator().next().getLong("count") == 0) {
                    return Uni.createFrom().failure(new RuntimeException(
                        "Cannot trigger response - conversation not found, access denied, or not in WAITING_FOR_USER state"));
                }
                // A manual continue is a user action. It retries a named
                // offstage character if the message did not bring them in, and
                // starts a fresh turn budget.
                return groupChatService.revealAddressedCastMember(conversationId)
                    .onFailure().recoverWithItem((String) null)
                    .chain(ignored -> groupChatService.startCharacterTurn(conversationId, userId));
            })
            .chain(character -> {
                if (character == null) {
                    return Uni.createFrom().failure(new RuntimeException(
                        "Cannot trigger response - conversation not found, access denied, or not in WAITING_FOR_USER state"));
                }
                log.debug("Manually triggered character response: conversationId={}, character={}",
                        conversationId, character.getName());

                // Return a stub message indicating the response is being generated
                Message message = new Message();
                message.setId(UUID.randomUUID());
                message.setConversationId(UUID.fromString(conversationId));
                message.setContent("");
                message.setRole(MessageRole.ASSISTANT);
                message.setDisplayName(character.getName());
                message.setCreatedAt(java.time.LocalDateTime.now(ZoneOffset.UTC).toString());

                return Uni.createFrom().item(message);
            });
    }

    /**
     * Request an interjection in a group conversation.
     * Schema: requestInterjection(input: RequestInterjectionInput!): InterjectionRequest!
     */
    @Mutation
    public Uni<InterjectionRequest> requestInterjection(@Name("input") RequestInterjectionInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        Map<String, Object> input = com.eonmux.chatllm.graphql.GraphQLInputs.suppliedInput(inputObj, graphqlInputContext);
        String conversationId = (String) input.get("conversationId");
        String characterId = (String) input.get("characterId");
        String reason = (String) input.get("reason");
        Integer priority = input.get("priority") instanceof Number number
                ? number.intValue() : null;

        return groupChatService.requestInterjection(
                conversationId,
                characterId,
                auth.getUserId().toString(),
                reason,
                priority
        );
    }

    /**
     * Cancel an interjection request.
     * Schema: cancelInterjection(interjectionId: ID!): Boolean!
     */
    @Mutation
    public Uni<Boolean> cancelInterjection(@Name("interjectionId") String interjectionId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        return groupChatService.cancelInterjection(interjectionId, auth.getUserId().toString());
    }

    /**
     * Approve an interjection request.
     * Schema: approveInterjection(interjectionId: ID!): InterjectionRequest!
     */
    @Mutation
    public Uni<InterjectionRequest> approveInterjection(@Name("interjectionId") String interjectionId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        return groupChatService.approveInterjection(interjectionId, auth.getUserId().toString());
    }

    /**
     * Reject an interjection request.
     * Schema: rejectInterjection(interjectionId: ID!): InterjectionRequest!
     */
    @Mutation
    public Uni<InterjectionRequest> rejectInterjection(@Name("interjectionId") String interjectionId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        return groupChatService.rejectInterjection(interjectionId, auth.getUserId().toString());
    }

    /**
     * Pause a group conversation.
     * Schema: pauseGroupConversation(conversationId: ID!): Conversation!
     */
    @Mutation
    public Uni<Conversation> pauseGroupConversation(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        return groupChatService.pauseGroupConversation(conversationId, auth.getUserId().toString());
    }

    /**
     * Resume a group conversation.
     * Schema: resumeGroupConversation(conversationId: ID!): Conversation!
     */
    @Mutation
    public Uni<Conversation> resumeGroupConversation(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        return groupChatService.resumeGroupConversation(conversationId, auth.getUserId().toString());
    }

    /**
     * Starts the character turn for a user message.
     *
     * The deferred context gives the message transaction time to commit, then
     * GroupChatService owns the claim, speaker commit and response event. A
     * turn that loses the claim changes nothing, so a message sent while a
     * character is already answering cannot start a second speaker.
     */
    private void triggerCharacterResponseAsync(String conversationId) {
        vertx.runOnContext(() -> mysqlPool.preparedQuery(
                        "SELECT ownerId FROM conversation WHERE conversationGUID = ?")
                .execute(Tuple.of(conversationId))
                .chain(rows -> {
                    if (rows.size() == 0) return Uni.createFrom().item((com.eonmux.chatllm.models.Character) null);
                    String ownerId = rows.iterator().next().getString("ownerId");
                    return groupChatService.startCharacterTurn(conversationId, ownerId);
                })
                .subscribe().with(
                        character -> log.debug("Character turn for conversation {}: {}",
                                conversationId, character == null ? "not started (conversation busy)" : character.getName()),
                        err -> log.error("Failed to start character turn for conversation {}: {}",
                                conversationId, err.getMessage())));
    }

    // ==========================================
    // FIELD RESOLVERS (ConversationCharacter)
    // ==========================================

    /**
     * Resolve character for a ConversationCharacter join row.
     * The service layer populates {@code character} via a JOIN at fetch time,
     * so we return the pre-populated value. If absent we look it up by ID.
     */
    public Uni<Character> character(@Source ConversationCharacter cc) {
        if (cc.getCharacter() != null) {
            return Uni.createFrom().item(cc.getCharacter());
        }
        if (cc.getCharacterId() == null) {
            return Uni.createFrom().nullItem();
        }
        return mysqlPool.preparedQuery(
                "SELECT characterUUID, name, description, avatar, ownerId, personality, gender, pronouns, ageYears FROM `character` WHERE characterUUID = ?")
                .execute(Tuple.of(cc.getCharacterId().toString()))
                .map(rows -> {
                    if (rows.size() == 0) return null;
                    Row row = rows.iterator().next();
                    Character c = new Character();
                    c.setId(UUID.fromString(row.getString("characterUUID")));
                    c.setName(row.getString("name"));
                    c.setDescription(row.getString("description"));
                    c.setPersonality(row.getString("personality"));
                    c.setGender(row.getString("gender"));
                    c.setPronouns(row.getString("pronouns"));
                    c.setAgeYears(row.getInteger("ageYears"));
                    c.setAvatar(row.getString("avatar"));
                    String ownerId = row.getString("ownerId");
                    c.setOwnerId(ownerId != null ? UUID.fromString(ownerId) : null);
                    return c;
                });
    }

    /**
     * Resolve conversation for a ConversationCharacter join row.
     */
    public Uni<Conversation> conversation(@Source ConversationCharacter cc) {
        if (cc.getConversation() != null) {
            return Uni.createFrom().item(cc.getConversation());
        }
        if (cc.getConversationId() == null) {
            return Uni.createFrom().nullItem();
        }
        return mysqlPool.preparedQuery(
                "SELECT conversationGUID, title FROM conversation WHERE conversationGUID = ?")
                .execute(Tuple.of(cc.getConversationId().toString()))
                .map(rows -> {
                    if (rows.size() == 0) return null;
                    Row row = rows.iterator().next();
                    Conversation conv = new Conversation();
                    conv.setId(UUID.fromString(row.getString("conversationGUID")));
                    conv.setTitle(row.getString("title"));
                    return conv;
                });
    }
}
