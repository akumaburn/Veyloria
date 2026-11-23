package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.models.CharacterGroup;
import com.eonmux.chatllm.utils.Time4JHelper;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;

import java.util.*;

/** Queries and management for generated character rosters. */
@GraphQLApi
@ApplicationScoped
public class CharacterGroupResolver {
    @Inject Pool mysqlPool;
    @Inject GraphQLAuthContext graphQLAuthContext;

    @Query
    @Description("Generated character rosters owned by the current user")
    public Uni<List<CharacterGroup>> characterGroups() {
        var auth = graphQLAuthContext.requireAuth();
        String owner = auth.getUserId().toString();
        return mysqlPool.preparedQuery("""
                SELECT g.*, (SELECT COUNT(*) FROM character_group_member m
                             WHERE m.groupId = g.groupUUID) AS characterCount
                FROM character_group g
                WHERE g.ownerId = ?
                ORDER BY g.updatedAt DESC, g.name
                """).execute(Tuple.of(owner))
                .chain(rows -> {
                    List<CharacterGroup> groups = new ArrayList<>();
                    Map<String, CharacterGroup> byId = new LinkedHashMap<>();
                    for (Row row : rows) {
                        CharacterGroup group = new CharacterGroup();
                        group.setId(UUID.fromString(row.getString("groupUUID")));
                        group.setOwnerId(UUID.fromString(row.getString("ownerId")));
                        group.setName(row.getString("name"));
                        group.setDescription(row.getString("description"));
                        group.setSetting(row.getString("setting"));
                        group.setCharacterCount(row.getInteger("characterCount"));
                        group.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                        group.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
                        groups.add(group);
                        byId.put(group.getId().toString(), group);
                    }
                    if (groups.isEmpty()) return Uni.createFrom().item(groups);
                    return mysqlPool.preparedQuery("""
                            SELECT m.groupId, m.characterId
                            FROM character_group_member m
                            JOIN character_group g ON g.groupUUID = m.groupId
                            WHERE g.ownerId = ?
                            ORDER BY m.groupId, m.position
                            """).execute(Tuple.of(owner))
                            .map(memberRows -> {
                                for (Row row : memberRows) {
                                    CharacterGroup group = byId.get(row.getString("groupId"));
                                    if (group != null) group.getCharacterIds().add(row.getString("characterId"));
                                }
                                return groups;
                            });
                });
    }

    @Mutation
    @Description("Rename or describe a generated roster")
    public Uni<CharacterGroup> renameCharacterGroup(
            @Name("id") String id,
            @Name("name") @NonNull String name,
            @Name("description") String description) throws GraphQLException {
        var auth = graphQLAuthContext.requireAuth();
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 255)
            throw new GraphQLException("A roster name must be between 1 and 255 characters.");
        String owner = auth.getUserId().toString();
        return mysqlPool.preparedQuery("""
                UPDATE character_group SET name = ?, description = ?
                WHERE groupUUID = ? AND ownerId = ?
                """).execute(Tuple.of(trimmed, description, id, owner))
                .chain(updated -> updated.rowCount() == 0
                        ? Uni.createFrom().failure(new GraphQLException("Roster not found."))
                        : loadGroup(owner, id));
    }

    @Mutation
    @Description("Delete a generated roster without deleting its characters")
    public Uni<Boolean> deleteCharacterGroup(@Name("id") String id) {
        var auth = graphQLAuthContext.requireAuth();
        return mysqlPool.preparedQuery(
                        "DELETE FROM character_group WHERE groupUUID = ? AND ownerId = ?")
                .execute(Tuple.of(id, auth.getUserId().toString()))
                .map(rows -> rows.rowCount() > 0);
    }

    @Mutation
    @Description("Remove one character from a generated roster without deleting the character")
    public Uni<Boolean> removeCharacterFromCharacterGroup(
            @Name("groupId") String groupId, @Name("characterId") String characterId) {
        var auth = graphQLAuthContext.requireAuth();
        return mysqlPool.preparedQuery("""
                DELETE m FROM character_group_member m
                JOIN character_group g ON g.groupUUID = m.groupId
                WHERE m.groupId = ? AND m.characterId = ? AND g.ownerId = ?
                """).execute(Tuple.of(groupId, characterId, auth.getUserId().toString()))
                .map(rows -> rows.rowCount() > 0);
    }

    private Uni<CharacterGroup> loadGroup(String owner, String id) {
        return mysqlPool.preparedQuery("""
                SELECT g.*, (SELECT COUNT(*) FROM character_group_member m
                             WHERE m.groupId = g.groupUUID) AS characterCount
                FROM character_group g
                WHERE g.groupUUID = ? AND g.ownerId = ?
                """).execute(Tuple.of(id, owner))
                .map(rows -> {
                    if (rows.size() == 0) return null;
                    Row row = rows.iterator().next();
                    CharacterGroup group = new CharacterGroup();
                    group.setId(UUID.fromString(row.getString("groupUUID")));
                    group.setOwnerId(UUID.fromString(row.getString("ownerId")));
                    group.setName(row.getString("name"));
                    group.setDescription(row.getString("description"));
                    group.setSetting(row.getString("setting"));
                    group.setCharacterCount(row.getInteger("characterCount"));
                    group.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
                    group.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
                    return group;
                });
    }
}
