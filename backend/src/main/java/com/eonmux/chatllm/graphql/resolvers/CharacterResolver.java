package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.utils.ServiceHelper;
import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.models.User;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Source;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Field resolver for Character type in GraphQL.
 * Uses @Source annotation for lazy loading of related entities.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class CharacterResolver {

    @Inject
    Pool mysqlPool;

    /**
     * Resolve creator (owner) for a character.
     */
    @Name("creator")
    public Uni<User> owner(@Source Character character) {
        if (character.getOwnerId() == null) {
            return Uni.createFrom().nullItem();
        }
        if (character.getOwner() != null) {
            return Uni.createFrom().item(character.getOwner());
        }

        String query = "SELECT * FROM user WHERE userUUID = ?";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(character.getOwnerId().toString()))
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
            });
    }

    /**
     * Resolve photo album for a character.
     */
    public Uni<List<String>> photoAlbum(@Source Character character) {
        if (character.getPhotoAlbum() != null) {
            try {
                JsonArray array = new JsonArray(character.getPhotoAlbum());
                List<String> photos = new ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    photos.add(array.getString(i));
                }
                return Uni.createFrom().item(photos);
            } catch (Exception e) {
                log.warn("Failed to parse photo album for character {}: {}", character.getId(), e.getMessage());
                return Uni.createFrom().item(new ArrayList<>());
            }
        }

        String query = "SELECT metadata FROM `character` WHERE characterUUID = ?";

        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(character.getId().toString()))
            .map(rows -> {
                if (rows.size() > 0) {
                    String metadataJson = ServiceHelper.safeGetJsonText(rows.iterator().next(), "metadata");
                    if (metadataJson != null) {
                        try {
                            JsonObject metadata = new JsonObject(metadataJson);
                            JsonArray array = metadata.getJsonArray("photoAlbum");
                            if (array != null) {
                                List<String> photos = new ArrayList<>();
                                for (int i = 0; i < array.size(); i++) {
                                    photos.add(array.getString(i));
                                }
                                return photos;
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse photo album for character {}: {}", character.getId(), e.getMessage());
                        }
                    }
                }
                return new ArrayList<String>();
            });
    }
}
