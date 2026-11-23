package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.Conversation;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.models.ConversationCharacter;
import com.eonmux.chatllm.models.ConversationInvite;
import com.eonmux.chatllm.models.ConversationParticipant;
import com.eonmux.chatllm.models.InterjectionRequest;
import com.eonmux.chatllm.models.Message;
import com.eonmux.chatllm.models.MessageRole;
import com.eonmux.chatllm.models.User;
import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.core.Future;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
 * Field resolver for Conversation type in GraphQL.
 * Handles lazy loading of related entities.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class ConversationResolver {

    @Inject
    Pool mysqlPool;

    @Inject com.eonmux.chatllm.services.ConversationProgressService progressService;
    public Boolean processingOnly(@Source Conversation conversation) {
        return Boolean.TRUE.equals(conversation.getProcessingOnly());
    }

    public com.eonmux.chatllm.models.ConversationProgress processingStatus(@Source Conversation conversation) {
        return conversation.getProcessingStatus() != null ? conversation.getProcessingStatus()
            : conversation.getId() == null ? null : progressService.get(conversation.getId().toString());
    }


    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    com.eonmux.chatllm.utils.ServiceHelper serviceHelper;

    /**
     * Resolve messages for a conversation.
     */
    public Uni<List<Message>> messages(@Source Conversation conversation, @Name("limit") Integer limit, @Name("offset") Integer offset) {

        List<Object> params = new ArrayList<>();
        params.add(conversation.getId().toString());

        String query = "SELECT * FROM message WHERE conversationId = ? ORDER BY seq ASC";
        if (limit != null) {
            query += " LIMIT ?";
            params.add(limit);
            if (offset != null) {
                query += " OFFSET ?";
                params.add(offset);
            }
        }

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.from(params))
            .map(rows -> {
                List<Message> messages = new ArrayList<>();
                for (Row row : rows) {
                    Message message = new Message();
                    message.setId(UUID.fromString(row.getString("messageUUID")));
                    message.setSeq(row.getLong("seq"));
                    message.setConversationId(UUID.fromString(row.getString("conversationId")));
                    String ownerId = row.getString("ownerId");
                    if (ownerId != null) {
                        message.setOwnerId(UUID.fromString(ownerId));
                    }
                    message.setContent(row.getString("content"));
                    message.setRole(MessageRole.fromString(row.getString("role")));
                    message.setDisplayName(row.getString("displayName"));
                    message.setIsBase64Encoded(row.getBoolean("isBase64Encoded"));
                    message.setEditCount(row.getInteger("editCount"));
                    message.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                    message.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
                    messages.add(message);
                }
                return messages;
            })

            ;
    }

    /**
     * Resolve message count for a conversation.
     */
    public Uni<Integer> messageCount(@Source Conversation conversation) {

        // If already set, return it
        if (conversation.getMessageCount() != null) {
            return Uni.createFrom().item(conversation.getMessageCount());
        }

        String query = "SELECT COUNT(*) as count FROM message WHERE conversationId = ?";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversation.getId().toString()))
            .map(rows -> {
                if (rows.size() > 0) {
                    return rows.iterator().next().getInteger("count");
                }
                return 0;
            })

            ;
    }

    /**
     * Resolve last message for a conversation.
     */
    public Uni<Message> lastMessage(@Source Conversation conversation) {

        // If already set, return it
        if (conversation.getLastMessage() != null) {
            return Uni.createFrom().item(conversation.getLastMessage());
        }

        String query = "SELECT * FROM message WHERE conversationId = ? ORDER BY seq DESC LIMIT 1";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversation.getId().toString()))
            .map(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    Message message = new Message();
                    message.setId(UUID.fromString(row.getString("messageUUID")));
                    message.setConversationId(UUID.fromString(row.getString("conversationId")));
                    String ownerId = row.getString("ownerId");
                    if (ownerId != null) {
                        message.setOwnerId(UUID.fromString(ownerId));
                    }
                    message.setContent(row.getString("content"));
                    message.setRole(MessageRole.fromString(row.getString("role")));
                    message.setDisplayName(row.getString("displayName"));
                    message.setIsBase64Encoded(row.getBoolean("isBase64Encoded"));
                    message.setEditCount(row.getInteger("editCount"));
                    message.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                    message.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
                    return message;
                }
                return null;
            })

            ;
    }

    /**
     * Resolve owner for a conversation.
     */
    public Uni<User> owner(@Source Conversation conversation) {

        // If no owner ID, return null
        if (conversation.getOwnerId() == null) {
            return Uni.createFrom().nullItem();
        }

        // If already set, return it
        if (conversation.getOwner() != null) {
            return Uni.createFrom().item(conversation.getOwner());
        }

        String query = "SELECT * FROM user WHERE userUUID = ?";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversation.getOwnerId().toString()))
            .map(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    User user = new User();
                    user.setId(UUID.fromString(row.getString("userUUID")));
                    user.setUsername(row.getString("username"));
                    user.setEmail(row.getString("email"));
                    user.setDisplayName(row.getString("displayName"));
                    user.setAssistantName(row.getString("assistantName"));
                    user.setLanguage(row.getString("language"));
                    user.setNotifications(row.getBoolean("notifications"));
                    user.setSounds(row.getBoolean("sounds"));
                    user.setFontSize(row.getString("fontSize"));
                    user.setCodeTheme(row.getString("codeTheme"));
                    user.setAnalytics(row.getBoolean("analytics"));
                    user.setImprovement(row.getBoolean("improvement"));
                    user.setPreferredModelPath(row.getString("preferredModelPath"));
                    user.setPreferredGpuLayers(row.getInteger("preferredGpuLayers"));
                    user.setApiLimits(row.getLong("apiLimits"));
                    user.setChatLimits(row.getLong("chatLimits"));
                    user.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                    user.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
                    return user;
                }
                return null;
            })

            ;
    }

    /**
     * Resolve character for a conversation.
     */
    public Uni<com.eonmux.chatllm.models.Character> character(@Source Conversation conversation) {

        // If no character ID, return null
        if (conversation.getCharacterId() == null) {
            return Uni.createFrom().nullItem();
        }

        // If already set, return it
        if (conversation.getCharacter() != null) {
            return Uni.createFrom().item(conversation.getCharacter());
        }

        String query = "SELECT * FROM `character` WHERE characterUUID = ?";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversation.getCharacterId().toString()))
            .map(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    com.eonmux.chatllm.models.Character character = new com.eonmux.chatllm.models.Character();
                    character.setId(UUID.fromString(row.getString("characterUUID")));
                    String ownerId = row.getString("ownerId");
                    if (ownerId != null) {
                        character.setOwnerId(UUID.fromString(ownerId));
                    }
                    character.setName(row.getString("name"));
                    character.setDescription(row.getString("description"));
                    character.setPersonality(row.getString("personality"));
                    character.setGender(row.getString("gender"));
                    character.setPronouns(row.getString("pronouns"));
                    character.setAgeYears(row.getInteger("ageYears"));
                    character.setSystemPrompt(row.getString("systemPrompt"));
                    character.setGreeting(row.getString("greeting"));
                    character.setAvatar(row.getString("avatar"));
                    // Extended fields
                    character.setAppearance(row.getString("appearance"));
                    character.setGender(row.getString("gender"));
                    character.setPronouns(row.getString("pronouns"));
                    character.setAgeYears(row.getInteger("ageYears"));
                    character.setHeight(row.getString("height"));
                    character.setBuild(row.getString("build"));
                    character.setHair(row.getString("hair"));
                    character.setEyes(row.getString("eyes"));
                    character.setStrength(row.getInteger("strength"));
                    character.setDexterity(row.getInteger("dexterity"));
                    character.setConstitution(row.getInteger("constitution"));
                    character.setIntelligence(row.getInteger("intelligence"));
                    character.setWisdom(row.getInteger("wisdom"));
                    character.setCharisma(row.getInteger("charisma"));
                    character.setLikes(row.getString("likes"));
                    character.setDislikes(row.getString("dislikes"));
                    character.setBackstory(row.getString("backstory"));
                    character.setGoals(row.getString("goals"));
                    character.setExamples(row.getString("examples"));
                    character.setCategory(row.getString("category"));
                    character.setInitialAffinityToUser(row.getInteger("initialAffinityToUser"));
                    character.setInitialAffinityFromUser(row.getInteger("initialAffinityFromUser"));
                    character.setIsDefault(row.getBoolean("isDefault"));
                    character.setIsPublic(row.getBoolean("isPublic"));
                    character.setTemperature(row.getDouble("temperature"));
                    character.setMaxTokens(row.getInteger("maxTokens"));
                    character.setTopP(row.getDouble("topP"));
                    character.setTopK(row.getInteger("topK"));
                    character.setRepeatPenalty(row.getDouble("repeatPenalty"));
                    character.setRating(row.getDouble("rating"));
                    character.setUsageCount(row.getInteger("usageCount"));
                    character.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                    character.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
                    // Parse tags
                    String tagsStr = row.getString("tags");
                    if (tagsStr != null && !tagsStr.isEmpty()) {
                        character.setTagsFromString(tagsStr);
                    }
                    return character;
                }
                return null;
            })

            ;
    }

    /**
     * Resolve characters for a group conversation.
     */
    public Uni<List<ConversationCharacter>> characters(@Source Conversation conversation) {

        // Only for group conversations
        if (!Boolean.TRUE.equals(conversation.getIsGroupChat())) {
            return Uni.createFrom().item(new ArrayList<>());
        }

        // If already set, return it
        if (conversation.getCharacters() != null && !conversation.getCharacters().isEmpty()) {
            return Uni.createFrom().item(conversation.getCharacters());
        }

        // Select all character fields to populate the nested Character object
        String query = """
            SELECT cc.*,
                   ch.characterUUID as char_id, ch.name as char_name, ch.description as char_description,
                   ch.systemPrompt as char_systemPrompt, ch.greeting as char_greeting, ch.avatar as char_avatar,
                   ch.isPublic as char_isPublic, ch.ownerId as char_ownerId,
                   ch.personality as char_personality, ch.appearance as char_appearance,
                   ch.gender as char_gender, ch.pronouns as char_pronouns, ch.ageYears as char_ageYears,
                   ch.height as char_height, ch.build as char_build, ch.hair as char_hair, ch.eyes as char_eyes,
                   ch.strength as char_strength, ch.dexterity as char_dexterity,
                   ch.constitution as char_constitution, ch.intelligence as char_intelligence,
                   ch.wisdom as char_wisdom, ch.charisma as char_charisma,
                   ch.backstory as char_backstory, ch.goals as char_goals, ch.likes as char_likes,
                   ch.dislikes as char_dislikes, ch.examples as char_examples
            FROM conversation_character cc
            JOIN `character` ch ON cc.characterId = ch.characterUUID
            WHERE cc.conversationId = ? AND cc.isActive = true
            ORDER BY cc.turnOrder
            """;

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversation.getId().toString()))
            .map(rows -> {
                List<ConversationCharacter> result = new ArrayList<>();
                for (Row row : rows) {
                    ConversationCharacter cc = new ConversationCharacter();
                    cc.setId(UUID.fromString(row.getString("id")));
                    cc.setConversationId(UUID.fromString(row.getString("conversationId")));
                    cc.setCharacterId(UUID.fromString(row.getString("characterId")));
                    cc.setJoinedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("joinedAt")));
                    cc.setIsActive(row.getBoolean("isActive"));
                    cc.setTurnOrder(row.getInteger("turnOrder"));
                    cc.setResponseCount(row.getInteger("responseCount"));
                    cc.setLastResponseAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("lastResponseAt")));
                    cc.setWeight(row.getDouble("weight"));
                    cc.setInterjectionProbability(row.getDouble("interjectionProbability"));

                    // Populate the nested Character object
                    Character character = new Character();
                    character.setId(UUID.fromString(row.getString("char_id")));
                    character.setName(row.getString("char_name"));
                    character.setDescription(row.getString("char_description"));
                    character.setSystemPrompt(row.getString("char_systemPrompt"));
                    character.setGreeting(row.getString("char_greeting"));
                    character.setAvatar(row.getString("char_avatar"));
                    character.setIsPublic(row.getBoolean("char_isPublic"));
                    character.setIsFavorite(false); // Default to false
                    character.setPersonality(row.getString("char_personality"));
                    character.setAppearance(row.getString("char_appearance"));
                    character.setGender(row.getString("char_gender"));
                    character.setPronouns(row.getString("char_pronouns"));
                    character.setAgeYears(row.getInteger("char_ageYears"));
                    character.setHeight(row.getString("char_height"));
                    character.setBuild(row.getString("char_build"));
                    character.setHair(row.getString("char_hair"));
                    character.setEyes(row.getString("char_eyes"));
                    character.setStrength(row.getInteger("char_strength"));
                    character.setDexterity(row.getInteger("char_dexterity"));
                    character.setConstitution(row.getInteger("char_constitution"));
                    character.setIntelligence(row.getInteger("char_intelligence"));
                    character.setWisdom(row.getInteger("char_wisdom"));
                    character.setCharisma(row.getInteger("char_charisma"));
                    character.setBackstory(row.getString("char_backstory"));
                    character.setGoals(row.getString("char_goals"));
                    character.setLikes(row.getString("char_likes"));
                    character.setDislikes(row.getString("char_dislikes"));
                    character.setExamples(row.getString("char_examples"));
                    String ownerId = row.getString("char_ownerId");
                    if (ownerId != null) {
                        character.setOwnerId(UUID.fromString(ownerId));
                    }
                    cc.setCharacter(character);

                    result.add(cc);
                }
                return result;
            })

            ;
    }

    /**
     * Resolve current speaker for a group conversation.
     */
    public Uni<Character> currentSpeaker(@Source Conversation conversation) {

        // If no current speaker ID, return null
        if (conversation.getCurrentSpeakerId() == null) {
            return Uni.createFrom().nullItem();
        }

        // If already set, return it
        if (conversation.getCurrentSpeaker() != null) {
            return Uni.createFrom().item(conversation.getCurrentSpeaker());
        }

        String query = "SELECT * FROM `character` WHERE characterUUID = ?";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversation.getCurrentSpeakerId().toString()))
            .map(rows -> {
                if (rows.size() > 0) {
                    return mapRowToCharacter(rows.iterator().next());
                }
                return null;
            })

            ;
    }

    /**
     * Resolve last speaker for a group conversation.
     */
    public Uni<Character> lastSpeaker(@Source Conversation conversation) {

        // If no last speaker ID, return null
        if (conversation.getLastSpeakerId() == null) {
            return Uni.createFrom().nullItem();
        }

        // If already set, return it
        if (conversation.getLastSpeaker() != null) {
            return Uni.createFrom().item(conversation.getLastSpeaker());
        }

        String query = "SELECT * FROM `character` WHERE characterUUID = ?";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversation.getLastSpeakerId().toString()))
            .map(rows -> {
                if (rows.size() > 0) {
                    return mapRowToCharacter(rows.iterator().next());
                }
                return null;
            })

            ;
    }

    /**
     * Resolve pending interjections for a group conversation.
     */
    public Uni<List<InterjectionRequest>> pendingInterjections(@Source Conversation conversation) {

        // Only for group conversations
        if (!Boolean.TRUE.equals(conversation.getIsGroupChat())) {
            return Uni.createFrom().item(new ArrayList<>());
        }

        String query = """
            SELECT * FROM interjection_request
            WHERE conversationId = ? AND status = 'pending'
            ORDER BY priority DESC, createdAt ASC
            """;

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversation.getId().toString()))
            .map(rows -> {
                List<InterjectionRequest> result = new ArrayList<>();
                for (Row row : rows) {
                    InterjectionRequest ir = new InterjectionRequest();
                    ir.setId(UUID.fromString(row.getString("id")));
                    ir.setConversationId(UUID.fromString(row.getString("conversationId")));
                    String requesterId = row.getString("requesterId");
                    if (requesterId != null) {
                        ir.setRequesterId(UUID.fromString(requesterId));
                    }
                    ir.setRequesterType(row.getString("requesterType"));
                    ir.setReason(row.getString("reason"));
                    ir.setPriority(row.getInteger("priority"));
                    ir.setStatus(row.getString("status"));
                    ir.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                    ir.setResolvedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("resolvedAt")));
                    result.add(ir);
                }
                return result;
            })

            ;
    }

    /**
     * Helper to map a database row to a Character object.
     */
    private Character mapRowToCharacter(Row row) {
        Character character = new Character();
        character.setId(UUID.fromString(row.getString("characterUUID")));
        String ownerId = row.getString("ownerId");
        if (ownerId != null) {
            character.setOwnerId(UUID.fromString(ownerId));
        }
        character.setName(row.getString("name"));
        character.setDescription(row.getString("description"));
        character.setPersonality(row.getString("personality"));
        character.setGender(row.getString("gender"));
        character.setPronouns(row.getString("pronouns"));
        character.setAgeYears(row.getInteger("ageYears"));
        character.setSystemPrompt(row.getString("systemPrompt"));
        character.setGreeting(row.getString("greeting"));
        character.setAvatar(row.getString("avatar"));
        character.setIsDefault(row.getBoolean("isDefault"));
        character.setIsPublic(row.getBoolean("isPublic"));
        character.setIsFavorite(false); // Default to false for conversation-attached characters
        character.setTemperature(row.getDouble("temperature"));
        character.setMaxTokens(row.getInteger("maxTokens"));
        character.setTopP(row.getDouble("topP"));
        character.setTopK(row.getInteger("topK"));
        character.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        character.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return character;
    }

    // ==========================================
    // MULTI-USER FIELD RESOLVERS
    // ==========================================

    /**
     * Resolve participants for a conversation.
     */
    public Uni<List<ConversationParticipant>> participants(@Source Conversation conversation) {
        graphQLAuthContext.requireAuth();

        // If already set, return it
        if (conversation.getParticipants() != null && !conversation.getParticipants().isEmpty()) {
            return Uni.createFrom().item(conversation.getParticipants());
        }

        String query = """
            SELECT cp.*, u.username, u.displayName, u.profilePicture, u.email
            FROM conversation_participant cp
            JOIN user u ON cp.user_id = u.userUUID
            WHERE cp.conversation_id = ? AND cp.is_active = true
            ORDER BY cp.joined_at ASC
            """;

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversation.getId().toString()))
            .map(rows -> {
                List<ConversationParticipant> result = new ArrayList<>();
                for (Row row : rows) {
                    ConversationParticipant cp = mapRowToParticipant(row);
                    result.add(cp);
                }
                return result;
            })

            ;
    }

    /**
     * Resolve pending invites for a conversation.
     */
    public Uni<List<ConversationInvite>> pendingInvites(@Source Conversation conversation) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        // Only owners/admins can see pending invites
        if (auth == null || !auth.isAuthenticated()) {
            return Uni.createFrom().item(new ArrayList<>());
        }

        // If already set, return it
        if (conversation.getPendingInvites() != null && !conversation.getPendingInvites().isEmpty()) {
            return Uni.createFrom().item(conversation.getPendingInvites());
        }

        // Verify the caller owns this conversation (admins bypass)
        String userId = auth.getUserId().toString();
        String convId = conversation.getId().toString();
        boolean isOwner = conversation.getOwnerId() != null
            && conversation.getOwnerId().toString().equals(userId);
        if (!auth.isAdmin() && !isOwner) {
            return Uni.createFrom().item(new ArrayList<>());
        }

        String query = """
            SELECT ci.*,
                   inviter.username as inviterUsername, inviter.displayName as inviterDisplayName,
                   invitee.username as inviteeUsername, invitee.displayName as inviteeDisplayName
            FROM conversation_invite ci
            LEFT JOIN user inviter ON ci.inviter_id = inviter.userUUID
            LEFT JOIN user invitee ON ci.invitee_id = invitee.userUUID
            WHERE ci.conversation_id = ? AND ci.status = 'pending'
            ORDER BY ci.created_at DESC
            """;

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversation.getId().toString()))
            .map(rows -> {
                List<ConversationInvite> result = new ArrayList<>();
                for (Row row : rows) {
                    ConversationInvite invite = mapRowToInvite(row);
                    result.add(invite);
                }
                return result;
            })

            ;
    }

    /**
     * Resolve current user's participation in a conversation.
     */
    public Uni<ConversationParticipant> myParticipation(@Source Conversation conversation) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        // Must be authenticated
        if (auth == null || !auth.isAuthenticated()) {
            return Uni.createFrom().nullItem();
        }

        // If already set, return it
        if (conversation.getMyParticipation() != null) {
            return Uni.createFrom().item(conversation.getMyParticipation());
        }

        String query = """
            SELECT cp.*, u.username, u.displayName, u.profilePicture, u.email
            FROM conversation_participant cp
            JOIN user u ON cp.user_id = u.userUUID
            WHERE cp.conversation_id = ? AND cp.user_id = ?
            """;

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversation.getId().toString(), auth.getUserId().toString()))
            .map(rows -> {
                if (rows.size() > 0) {
                    return mapRowToParticipant(rows.iterator().next());
                }
                return null;
            })

            ;
    }

    /**
     * Helper to map a database row to a ConversationParticipant object.
     */
    private ConversationParticipant mapRowToParticipant(Row row) {
        ConversationParticipant cp = new ConversationParticipant();
        cp.setId(UUID.fromString(row.getString("id")));
        cp.setConversationId(UUID.fromString(row.getString("conversation_id")));
        cp.setUserId(UUID.fromString(row.getString("user_id")));
        cp.setRole(ConversationParticipant.ParticipantRole.fromString(row.getString("role")));
        cp.setNickname(row.getString("nickname"));
        cp.setIsActive(row.getBoolean("is_active"));
        cp.setCanInvite(row.getBoolean("can_invite"));
        cp.setCanAddCharacters(row.getBoolean("can_add_characters"));
        cp.setJoinedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("joined_at")));
        cp.setLeftAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("left_at")));
        cp.setLastReadAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("last_read_at")));
        cp.setLastTypingAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("last_typing_at")));

        // Set user info if available from join
        try {
            String username = row.getString("username");
            if (username != null) {
                User user = new User();
                user.setId(cp.getUserId());
                user.setUsername(username);
                user.setDisplayName(row.getString("displayName"));
                user.setProfilePicture(row.getString("profilePicture"));
                user.setEmail(row.getString("email"));
                cp.setUser(user);
            }
        } catch (Exception e) {
            // User info not included in this query
        }

        return cp;
    }

    /**
     * Helper to map a database row to a ConversationInvite object.
     */
    private ConversationInvite mapRowToInvite(Row row) {
        ConversationInvite invite = new ConversationInvite();
        invite.setId(UUID.fromString(row.getString("id")));
        invite.setConversationId(UUID.fromString(row.getString("conversation_id")));
        invite.setInviterId(UUID.fromString(row.getString("inviter_id")));

        String inviteeId = row.getString("invitee_id");
        if (inviteeId != null) {
            invite.setInviteeId(UUID.fromString(inviteeId));
        }

        invite.setInviteeEmail(row.getString("invitee_email"));
        invite.setInviteeUsername(row.getString("invitee_username"));
        invite.setStatus(ConversationInvite.InviteStatus.fromString(row.getString("status")));
        invite.setMessage(row.getString("message"));
        invite.setRoleToGrant(ConversationParticipant.ParticipantRole.fromString(row.getString("role_to_grant")));
        invite.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("created_at")));
        invite.setRespondedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("responded_at")));
        invite.setExpiresAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("expires_at")));

        // Set inviter info if available from join
        try {
            String inviterUsername = row.getString("inviterUsername");
            if (inviterUsername != null) {
                User inviter = new User();
                inviter.setId(invite.getInviterId());
                inviter.setUsername(inviterUsername);
                inviter.setDisplayName(row.getString("inviterDisplayName"));
                invite.setInviter(inviter);
            }
        } catch (Exception e) {
            // Inviter info not included in this query
        }

        // Set invitee info if available from join
        try {
            String inviteeUsername = row.getString("inviteeUsername");
            if (inviteeUsername != null && invite.getInviteeId() != null) {
                User invitee = new User();
                invitee.setId(invite.getInviteeId());
                invitee.setUsername(inviteeUsername);
                invitee.setDisplayName(row.getString("inviteeDisplayName"));
                invite.setInvitee(invitee);
            }
        } catch (Exception e) {
            // Invitee info not included in this query
        }

        return invite;
    }
}