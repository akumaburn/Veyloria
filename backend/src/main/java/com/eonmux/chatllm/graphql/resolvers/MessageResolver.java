package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.models.Attachment;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.models.Message;
import com.eonmux.chatllm.models.Conversation;
import com.eonmux.chatllm.models.User;
import com.eonmux.chatllm.models.MessageEdit;
import com.eonmux.chatllm.models.Character;
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
 * Field resolver for Message type in GraphQL.
 * Uses @Source annotation for lazy loading of related entities.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class MessageResolver {

    @Inject
    Pool mysqlPool;

    /**
     * Resolve conversation for a message.
     */
    public Uni<Conversation> conversation(@Source Message message) {
        if (message.getConversationId() == null) {
            log.error("Message {} has no conversationId - data integrity issue", message.getId());
            return Uni.createFrom().failure(new RuntimeException("Message has no associated conversation"));
        }

        if (message.getConversation() != null) {
            return Uni.createFrom().item(message.getConversation());
        }

        String query = "SELECT * FROM conversation WHERE conversationGUID = ?";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(message.getConversationId().toString()))
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
                    message.setConversation(conversation);
                    return conversation;
                }
                log.error("Conversation {} not found for message {} - data integrity issue",
                    message.getConversationId(), message.getId());
                throw new RuntimeException("Conversation not found for message: " + message.getId());
            });
    }

    /**
     * Resolve owner for a message.
     */
    public Uni<User> owner(@Source Message message) {
        if (message.getOwnerId() == null) {
            return Uni.createFrom().nullItem();
        }
        if (message.getOwner() != null) {
            return Uni.createFrom().item(message.getOwner());
        }
        return fetchUserById(message.getOwnerId());
    }

    /**
     * Resolve author for a message.
     */
    public Uni<User> author(@Source Message message) {
        if (message.getOwnerId() == null) {
            return Uni.createFrom().nullItem();
        }
        if (message.getAuthor() != null) {
            return Uni.createFrom().item(message.getAuthor());
        }
        return fetchUserById(message.getOwnerId())
            .onItem().invoke(user -> {
                if (user != null) {
                    message.setAuthor(user);
                }
            });
    }

    /**
     * Resolve edit history for a message.
     */
    public Uni<List<MessageEdit>> editHistory(@Source Message message) {
        if (message.getEditHistory() != null && !message.getEditHistory().isEmpty()) {
            return Uni.createFrom().item(message.getEditHistory());
        }

        String query = "SELECT * FROM message_edit WHERE messageId = ? ORDER BY editedAt ASC";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(message.getId().toString()))
            .map(rows -> {
                List<MessageEdit> edits = new ArrayList<>();
                for (Row row : rows) {
                    MessageEdit edit = new MessageEdit();
                    edit.setId(UUID.fromString(row.getString("editId")));
                    edit.setMessageId(UUID.fromString(row.getString("messageId")));
                    edit.setPreviousContent(row.getString("previousContent"));
                    edit.setNewContent(row.getString("newContent"));
                    edit.setEditedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("editedAt")));
                    // editedBy can be NULL for system edits / legacy rows; guard it
                    // like every other nullable UUID parse in this file.
                    String editedBy = row.getString("editedBy");
                    edit.setEditorId(editedBy != null ? UUID.fromString(editedBy) : null);
                    edits.add(edit);
                }
                return edits;
            });
    }

    /**
     * Resolve edit count for a message.
     */
    public Uni<Integer> editCount(@Source Message message) {
        if (message.getEditCount() != null) {
            return Uni.createFrom().item(message.getEditCount());
        }

        String query = "SELECT COUNT(*) as count FROM message_edit WHERE messageId = ?";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(message.getId().toString()))
            .map(rows -> {
                if (rows.size() > 0) {
                    return rows.iterator().next().getInteger("count");
                }
                return 0;
            });
    }

    /**
     * Resolve character for a message.
     */
    public Uni<Character> character(@Source Message message) {
        if (message.getCharacterId() == null) {
            return Uni.createFrom().nullItem();
        }
        if (message.getCharacter() != null) {
            return Uni.createFrom().item(message.getCharacter());
        }

        String query = "SELECT * FROM `character` WHERE characterUUID = ?";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(message.getCharacterId().toString()))
            .map(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
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
                    character.setIsFavorite(false);
                    character.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                    character.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
                    return character;
                }
                return null;
            });
    }

    /**
     * Resolve attachments for a message.
     * Attachments live in a separate table and are loaded lazily.
     */
    public Uni<List<Attachment>> attachments(@Source Message message) {
        if (message.getId() == null) {
            return Uni.createFrom().item(new ArrayList<>());
        }

        // If already eagerly loaded, return it
        if (message.getAttachments() != null && !message.getAttachments().isEmpty()) {
            return Uni.createFrom().item(message.getAttachments());
        }

        String query = "SELECT * FROM attachment WHERE messageId = ? ORDER BY created ASC";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(message.getId().toString()))
            .map(rows -> {
                List<Attachment> attachments = new ArrayList<>();
                for (Row row : rows) {
                    Attachment attachment = new Attachment();
                    attachment.setId(UUID.fromString(row.getString("attachmentId")));
                    attachment.setMessageId(UUID.fromString(row.getString("messageId")));
                    attachment.setFilename(row.getString("filename"));
                    attachment.setMimeType(row.getString("mimeType"));
                    Integer sizeInt = row.getInteger("size");
                    if (sizeInt != null) {
                        attachment.setFileSize(sizeInt.longValue());
                    }
                    attachment.setUrl(row.getString("url"));
                    attachment.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("created")));
                    // Derive type from mimeType
                    attachment.setType(Attachment.AttachmentType.fromMimeType(attachment.getMimeType()));
                    attachments.add(attachment);
                }
                return attachments;
            });
    }

    /**
     * Helper method to fetch user by ID.
     */
    private Uni<User> fetchUserById(UUID userId) {
        String query = "SELECT * FROM user WHERE userUUID = ?";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(userId.toString()))
            .map(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    User user = new User();
                    user.setId(UUID.fromString(row.getString("userUUID")));
                    user.setUsername(row.getString("username"));
                    user.setEmail(row.getString("email"));
                    user.setDisplayName(row.getString("displayName"));
                    user.setProfilePicture(row.getString("profilePicture"));
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
            });
    }
}
