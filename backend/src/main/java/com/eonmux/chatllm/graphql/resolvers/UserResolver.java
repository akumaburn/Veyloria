package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.User;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.models.Conversation;
import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.models.UserSettings;
import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Source;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Field resolver for User type in GraphQL.
 * Uses @Source annotation for lazy loading of related entities.
 *
 * The private-data field resolvers (conversations, characters, settings,
 * currentConversation) are only available for the caller's own user object
 * (or to admins). Otherwise a User object obtained via any other resolver
 * (message owner, participants, search results, ...) would leak another
 * user's private conversations/characters/settings.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class UserResolver {

    @Inject
    Pool mysqlPool;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    private boolean canAccessPrivateData(User user) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return auth.isAdmin() || auth.getUserId().equals(user.getId());
    }

    /**
     * Resolve conversations for a user (self or admin only).
     */
    public Uni<List<Conversation>> conversations(@Source User user) {
        if (!canAccessPrivateData(user)) {
            return Uni.createFrom().failure(new RuntimeException("Access denied"));
        }

        String query = "SELECT * FROM conversation WHERE ownerId = ? ORDER BY updatedAt DESC";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(user.getId().toString()))
            .map(rows -> {
                List<Conversation> conversations = new ArrayList<>();
                for (Row row : rows) {
                    Conversation conversation = new Conversation();
                    conversation.setId(UUID.fromString(row.getString("conversationGUID")));
                    conversation.setOwnerId(UUID.fromString(row.getString("ownerId")));
                    conversation.setTitle(row.getString("title"));
                    if (row.getString("characterId") != null) {
                        conversation.setCharacterId(UUID.fromString(row.getString("characterId")));
                    }
                    conversation.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                    conversation.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
                    conversations.add(conversation);
                }
                return conversations;
            });
    }

    /**
     * Resolve characters for a user (self or admin only).
     */
    public Uni<List<Character>> characters(@Source User user) {
        if (!canAccessPrivateData(user)) {
            return Uni.createFrom().failure(new RuntimeException("Access denied"));
        }

        String query = "SELECT * FROM `character` WHERE ownerId = ? ORDER BY createdAt DESC";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(user.getId().toString()))
            .map(rows -> {
                List<Character> characters = new ArrayList<>();
                for (Row row : rows) {
                    Character character = new Character();
                    character.setId(UUID.fromString(row.getString("characterUUID")));
                    String ownerId = row.getString("ownerId");
                    if (ownerId != null) {
                        character.setOwnerId(UUID.fromString(ownerId));
                    }
                    character.setName(row.getString("name"));
                    character.setDescription(row.getString("description"));
                    character.setPersonality(row.getString("personality"));
                    character.setSystemPrompt(row.getString("systemPrompt"));
                    character.setGreeting(row.getString("greeting"));
                    character.setAvatar(row.getString("avatar"));
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
                    character.setIsFavorite(false);
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
                    String tagsStr = row.getString("tags");
                    if (tagsStr != null && !tagsStr.isEmpty()) {
                        character.setTagsFromString(tagsStr);
                    }
                    characters.add(character);
                }
                return characters;
            });
    }

    /**
     * Resolve settings for a user (self or admin only).
     */
    public Uni<UserSettings> settings(@Source User user) {
        if (!canAccessPrivateData(user)) {
            return Uni.createFrom().failure(new RuntimeException("Access denied"));
        }

        if (user.getSettings() != null) {
            return Uni.createFrom().item(user.getSettings());
        }

        String query = "SELECT * FROM user_settings WHERE fk_userUUID = ?";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(user.getId().toString()))
            .map(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    UserSettings settings = new UserSettings();
                    settings.setUserId(row.getString("fk_userUUID"));
                    settings.setTheme(row.getString("theme"));
                    settings.setLanguage(row.getString("language"));
                    settings.setTemperature(row.getFloat("llmTemperature"));
                    settings.setMaxTokens(row.getInteger("llmMaxTokens"));
                    settings.setTopP(row.getFloat("llmTopP"));
                    settings.setTopK(row.getInteger("llmTopK"));
                    settings.setContextSize(row.getInteger("llmContextSize"));
                    settings.setNotificationsEnabled(row.getBoolean("notificationsEnabled"));
                    settings.setSoundEnabled(row.getBoolean("soundEnabled"));
                    return settings;
                } else {
                    return UserSettings.createDefaultSettings(user.getId());
                }
            });
    }

    /**
     * Resolve current conversation for a user (self or admin only).
     */
    public Uni<Conversation> currentConversation(@Source User user) {
        if (!canAccessPrivateData(user)) {
            return Uni.createFrom().failure(new RuntimeException("Access denied"));
        }

        if (user.getCurrentConversationId() == null) {
            return Uni.createFrom().nullItem();
        }

        String query = "SELECT * FROM conversation WHERE conversationGUID = ?";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(user.getCurrentConversationId().toString()))
            .map(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    Conversation conversation = new Conversation();
                    conversation.setId(UUID.fromString(row.getString("conversationGUID")));
                    conversation.setOwnerId(UUID.fromString(row.getString("ownerId")));
                    conversation.setTitle(row.getString("title"));
                    if (row.getString("characterId") != null) {
                        conversation.setCharacterId(UUID.fromString(row.getString("characterId")));
                    }
                    conversation.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                    conversation.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
                    return conversation;
                }
                return null;
            });
    }
}
