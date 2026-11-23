package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.utils.ServiceHelper;
import com.eonmux.chatllm.models.Party;
import com.eonmux.chatllm.utils.Time4JHelper;
import com.eonmux.chatllm.models.PartyMember;
import com.eonmux.chatllm.models.StoryBranch;
import com.eonmux.chatllm.models.inputs.*;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

/**
 * GraphQL resolver for story branches and party operations.
 */
@Slf4j
@GraphQLApi
@ApplicationScoped
public class BranchPartyResolver {

    @Inject
    Vertx vertx;
    @Inject
    Pool mysqlPool;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    com.eonmux.chatllm.utils.ServiceHelper serviceHelper;

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Verify the caller is a member of the party.
     */
    private Uni<Boolean> isPartyMember(String partyId, String userId) {
        String query = "SELECT 1 FROM party_member WHERE partyId = ? AND userId = ? LIMIT 1";
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(partyId, userId))
            .map(rows -> rows.size() > 0);
    }

    /**
     * Verify the caller is the leader of the party.
     */
    private Uni<Boolean> isPartyLeader(String partyId, String userId) {
        String query = "SELECT 1 FROM party_member WHERE partyId = ? AND userId = ? AND role = 'leader' LIMIT 1";
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(partyId, userId))
            .map(rows -> rows.size() > 0);
    }


    // ==================== Branch Queries/Mutations ====================

    @Query
    public Uni<List<StoryBranch>> storyBranches(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return serviceHelper.verifyConversationAccess(conversationId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }
                return doStoryBranches(conversationId);
            });
    }

    private Uni<List<StoryBranch>> doStoryBranches(String conversationId) {
        String query = "SELECT * FROM story_branch WHERE parentConversationId = ? ORDER BY createdAt";
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                List<StoryBranch> branches = new ArrayList<>();
                rows.forEach(row -> {
                    JsonObject worldStateSnapshot = null;
                    try {
                        String snapshotStr = ServiceHelper.safeGetJsonText(row, "worldStateSnapshot");
                        if (snapshotStr != null && !snapshotStr.isEmpty()) {
                            worldStateSnapshot = new JsonObject(snapshotStr);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse worldStateSnapshot for branch {}", row.getString("branchId"), e);
                    }

                    StoryBranch branch = StoryBranch.builder()
                        .branchId(uuidFromString(row.getString("branchId")))
                        .parentConversationId(uuidFromString(row.getString("parentConversationId")))
                        .branchConversationId(uuidFromString(row.getString("branchConversationId")))
                        .branchPointMessageId(uuidFromString(row.getString("branchPointMessageId")))
                        .branchPointBookmarkId(uuidFromString(row.getString("branchPointBookmarkId")))
                        .label(row.getString("label"))
                        .description(row.getString("description"))
                        .worldStateSnapshot(worldStateSnapshot)
                        .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
                        .build();
                    branches.add(branch);
                });
                return branches;
            })
            .onFailure().invoke(err -> {
                log.error("Failed to fetch story branches for conversation {}", conversationId, err);
            });
    }

    @Mutation
    public Uni<StoryBranch> createBranch(@Name("input") CreateBranchInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        java.util.Map<String, Object> input = inputObj != null ? io.vertx.core.json.JsonObject.mapFrom(inputObj).getMap() : java.util.Map.of();
        String parentConversationId = (String) input.get("parentConversationId");
        String branchPointMessageId = (String) input.get("branchPointMessageId");
        String bookmarkId = (String) input.get("bookmarkId");
        String label = (String) input.get("label");
        String description = (String) input.get("description");

        return serviceHelper.verifyConversationAccess(parentConversationId, userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }
                return doCreateBranch(parentConversationId, branchPointMessageId, bookmarkId, label, description);
            });
    }

    private Uni<StoryBranch> doCreateBranch(String parentConversationId, String branchPointMessageId,
                                            String bookmarkId, String label, String description) {
        String worldStateQuery = "SELECT * FROM world_state WHERE conversationId = ?";
        return mysqlPool.preparedQuery(worldStateQuery)
            .execute(Tuple.of(parentConversationId))
            .chain(wsRows -> {
                JsonObject worldStateSnapshot = new JsonObject();
                if (wsRows.size() > 0) {
                    var wsRow = wsRows.iterator().next();
                    worldStateSnapshot.put("currentScene", wsRow.getString("currentScene"));
                    worldStateSnapshot.put("currentLocation", wsRow.getString("currentLocation"));
                    worldStateSnapshot.put("currentLocationId", wsRow.getString("currentLocationId"));
                    worldStateSnapshot.put("currentArea", wsRow.getString("currentArea"));
                    worldStateSnapshot.put("currentAreaId", wsRow.getString("currentAreaId"));
                    worldStateSnapshot.put("timeOfDay", wsRow.getString("timeOfDay"));
                    worldStateSnapshot.put("dayNumber", wsRow.getInteger("dayNumber"));
                    worldStateSnapshot.put("weather", wsRow.getString("weather"));
                    worldStateSnapshot.put("mood", wsRow.getString("mood"));
                    try {
                        JsonObject globalFlags = wsRow.getJsonObject("globalFlags");
                        if (globalFlags != null) {
                            worldStateSnapshot.put("globalFlags", globalFlags);
                        }
                    } catch (Exception e) {
                        log.debug("No globalFlags for world state");
                    }
                }

                String branchId = UUID.randomUUID().toString();
                String insertQuery = """
                    INSERT INTO story_branch (branchId, parentConversationId, branchPointMessageId, branchPointBookmarkId, label, description, worldStateSnapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;

                return mysqlPool.preparedQuery(insertQuery)
                    .execute(Tuple.tuple()
                        .addValue(branchId)
                        .addValue(parentConversationId)
                        .addValue(branchPointMessageId)
                        .addValue(bookmarkId)
                        .addValue(label)
                        .addValue(description)
                        .addValue(worldStateSnapshot.encode()))
                    .map(insertResult -> StoryBranch.builder()
                        .branchId(uuidFromString(branchId))
                        .parentConversationId(uuidFromString(parentConversationId))
                        .branchPointMessageId(uuidFromString(branchPointMessageId))
                        .branchPointBookmarkId(uuidFromString(bookmarkId))
                        .label(label)
                        .description(description)
                        .worldStateSnapshot(worldStateSnapshot)
                        .createdAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                        .build());
            })
            .onFailure().invoke(err -> {
                log.error("Failed to create branch for conversation {}", parentConversationId, err);
            });
    }

    // ==================== Party Queries/Mutations ====================

    @Query
    public Uni<List<Party>> parties() {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String userId = auth.getUserId().toString();

        String query = "SELECT p.* FROM party p JOIN party_member pm ON p.partyId = pm.partyId WHERE pm.userId = ?";
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(userId))
            .chain(rows -> {
                List<Party> parties = new ArrayList<>();
                rows.forEach(row -> {
                    Party party = mapPartyRow(row);
                    parties.add(party);
                });

                if (parties.isEmpty()) {
                    return Uni.createFrom().item(parties);
                }

                // Fetch members for each party
                List<Uni<Void>> memberUnis = new ArrayList<>();
                for (Party party : parties) {
                    memberUnis.add(fetchPartyMembers(party));
                }

                return Uni.join().all(memberUnis).andFailFast()
                    .map(results -> parties)
                    .onFailure().recoverWithItem(err -> {
                        log.warn("Failed to fetch some party members, returning parties without members", err);
                        return parties;
                    });
            })
            .onFailure().invoke(err -> {
                log.error("Failed to fetch parties for user {}", userId, err);
            });
    }

    @Query
    public Uni<Party> party(@Name("partyId") String partyId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return isPartyMember(partyId, userId)
            .chain(isMember -> {
                if (!isMember) {
                    return Uni.createFrom().nullItem();
                }
                return doParty(partyId);
            });
    }

    private Uni<Party> doParty(String partyId) {
        return mysqlPool.preparedQuery("SELECT * FROM party WHERE partyId = ?")
            .execute(Tuple.of(partyId))
            .chain(rows -> {
                if (rows.size() == 0) {
                    return Uni.createFrom().nullItem();
                }
                Party party = mapPartyRow(rows.iterator().next());
                return fetchPartyMembers(party)
                    .map(v -> party)
                    .onFailure().recoverWithItem(err -> {
                        log.warn("Failed to fetch party members for party {}", partyId, err);
                        return party;
                    });
            })
            .onFailure().invoke(err -> {
                log.error("Failed to fetch party {}", partyId, err);
            });
    }

    @Query
    public Uni<Party> partyByInviteCode(@Name("inviteCode") String inviteCode) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        return mysqlPool.preparedQuery("SELECT * FROM party WHERE inviteCode = ? AND isActive = TRUE")
            .execute(Tuple.of(inviteCode))
            .chain(rows -> {
                if (rows.size() == 0) {
                    return Uni.createFrom().nullItem();
                }
                Party party = mapPartyRow(rows.iterator().next());
                return fetchPartyMembers(party)
                    .map(v -> party)
                    .onFailure().recoverWithItem(err -> {
                        log.warn("Failed to fetch party members by invite code {}", inviteCode, err);
                        return party;
                    });
            })
            .onFailure().invoke(err -> {
                log.error("Failed to fetch party by invite code {}", inviteCode, err);
            });
    }

    @Mutation
    public Uni<Party> createParty(@Name("input") CreatePartyInput inputObj) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        java.util.Map<String, Object> input = inputObj != null ? io.vertx.core.json.JsonObject.mapFrom(inputObj).getMap() : java.util.Map.of();
        String name = (String) input.get("name");
        String description = (String) input.get("description");
        Integer maxMembers = input.get("maxMembers") != null ? (Integer) input.get("maxMembers") : 6;
        Boolean isPublic = input.get("isPublic") != null ? (Boolean) input.get("isPublic") : false;
        String userId = auth.getUserId().toString();

        String partyId = UUID.randomUUID().toString();
        String inviteCode = generateInviteCode(8);

        String insertPartyQuery = """
            INSERT INTO party (partyId, name, description, leaderId, inviteCode, maxMembers, isPublic, isActive)
            VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)
            """;

        return mysqlPool.preparedQuery(insertPartyQuery)
            .execute(Tuple.tuple()
                .addValue(partyId)
                .addValue(name)
                .addValue(description)
                .addValue(userId)
                .addValue(inviteCode)
                .addValue(maxMembers)
                .addValue(isPublic))
            .chain(insertResult -> {
                String memberId = UUID.randomUUID().toString();
                String insertMemberQuery = """
                    INSERT INTO party_member (id, partyId, userId, role)
                    VALUES (?, ?, ?, 'leader')
                    """;

                return mysqlPool.preparedQuery(insertMemberQuery)
                    .execute(Tuple.of(memberId, partyId, userId))
                    .map(memberResult -> Party.builder()
                        .partyId(uuidFromString(partyId))
                        .name(name)
                        .description(description)
                        .leaderId(uuidFromString(userId))
                        .inviteCode(inviteCode)
                        .maxMembers(maxMembers)
                        .isActive(true)
                        .isPublic(isPublic)
                        .createdAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                        .build());
            })
            .onFailure().invoke(err -> {
                log.error("Failed to create party", err);
            });
    }

    @Mutation
    public Uni<PartyMember> joinParty(@Name("partyId") String partyId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return verifyPartyJoinable(partyId, userId)
            .chain(party -> insertPartyMember(party.getPartyId().toString(), userId, "member"));
    }

    @Mutation
    public Uni<PartyMember> joinPartyByCode(@Name("inviteCode") String inviteCode) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return mysqlPool.preparedQuery("SELECT partyId FROM party WHERE inviteCode = ? AND isActive = TRUE")
            .execute(Tuple.of(inviteCode))
            .chain(rows -> {
                if (rows.size() == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Party not found or inactive"));
                }
                String partyId = rows.iterator().next().getString("partyId");
                return verifyPartyJoinable(partyId, userId)
                    .chain(party -> insertPartyMember(partyId, userId, "member"));
            })
            .onFailure().invoke(err -> {
                log.error("Failed to join party by code for user {}", userId, err);
            });
    }

    /**
     * Verify a party exists, is active, is not at capacity, and the user is
     * not already a member — then return the party. Prevents joining
     * arbitrary/inactive/full parties and duplicate membership rows.
     */
    private Uni<Party> verifyPartyJoinable(String partyId, String userId) {
        return mysqlPool.preparedQuery("""
            SELECT p.partyId, p.maxMembers, p.isActive
            FROM party p
            LEFT JOIN party_member pm ON p.partyId = pm.partyId AND pm.userId = ?
            WHERE p.partyId = ?
            GROUP BY p.partyId, p.maxMembers, p.isActive
            HAVING (p.isActive = TRUE OR p.isActive IS NULL)
               AND COUNT(pm.id) = 0
            """)
            .execute(Tuple.of(userId, partyId))
            .chain(rows -> {
                if (rows.size() == 0) {
                    return Uni.createFrom().failure(new RuntimeException("Party not found, inactive, at capacity, or already a member"));
                }
                var row = rows.iterator().next();
                Party party = Party.builder()
                    .partyId(uuidFromString(row.getString("partyId")))
                    .maxMembers(row.getInteger("maxMembers"))
                    .build();
                return mysqlPool.preparedQuery("SELECT COUNT(*) as cnt FROM party_member WHERE partyId = ?")
                    .execute(Tuple.of(partyId))
                    .map(countRows -> {
                        long currentMembers = countRows.iterator().next().getLong("cnt");
                        if (party.getMaxMembers() != null && currentMembers >= party.getMaxMembers()) {
                            throw new RuntimeException("Party is at capacity");
                        }
                        return party;
                    });
            });
    }

    private Uni<PartyMember> insertPartyMember(String partyId, String userId, String role) {
        String memberId = UUID.randomUUID().toString();
        return mysqlPool.preparedQuery("INSERT INTO party_member (id, partyId, userId, role) VALUES (?, ?, ?, ?)")
            .execute(Tuple.of(memberId, partyId, userId, role))
            .map(insertResult -> PartyMember.builder()
                .id(uuidFromString(memberId))
                .partyId(uuidFromString(partyId))
                .userId(uuidFromString(userId))
                .role(role)
                .joinedAt(LocalDateTime.now(ZoneOffset.UTC).toString())
                .build());
    }

    @Mutation
    public Uni<Boolean> leaveParty(@Name("partyId") String partyId) {
        AuthContext auth = graphQLAuthContext.requireAuth();

        String userId = auth.getUserId().toString();

        return mysqlPool.preparedQuery("DELETE FROM party_member WHERE partyId = ? AND userId = ?")
            .execute(Tuple.of(partyId, userId))
            .map(result -> result.rowCount() > 0)
            .onFailure().invoke(err -> {
                log.error("Failed to leave party {} for user {}", partyId, userId, err);
            });
    }

    @Mutation
    public Uni<PartyMember> updatePartyMemberRole(@Name("partyId") String partyId, @Name("userId") String targetUserId, @Name("role") String role) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        // Only the party leader may change roles. Previously any
        // authenticated user could promote themselves (or anyone) to leader.
        return isPartyLeader(partyId, userId)
            .chain(isLeader -> {
                if (!isLeader) {
                    return Uni.createFrom().failure(new RuntimeException("Only the party leader can update member roles"));
                }
                return mysqlPool.preparedQuery("UPDATE party_member SET role = ? WHERE partyId = ? AND userId = ?")
                    .execute(Tuple.of(role, partyId, targetUserId))
                    .chain(updateResult ->
                        mysqlPool.preparedQuery("SELECT * FROM party_member WHERE partyId = ? AND userId = ?")
                            .execute(Tuple.of(partyId, targetUserId))
                            .map(rows -> {
                                if (rows.size() == 0) {
                                    throw new RuntimeException("Party member not found");
                                }
                                var row = rows.iterator().next();
                                return PartyMember.builder()
                                    .id(uuidFromString(row.getString("id")))
                                    .partyId(uuidFromString(row.getString("partyId")))
                                    .userId(uuidFromString(row.getString("userId")))
                                    .characterId(uuidFromString(row.getString("characterId")))
                                    .role(row.getString("role"))
                                    .joinedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("joinedAt")))
                                    .build();
                            })
                    );
            })
            .onFailure().invoke(err -> {
                log.error("Failed to update party member role for party {} user {}", partyId, targetUserId, err);
            });
    }

    // ==================== Helper Methods ====================

    /**
     * Fetches party members and attaches them to the party object.
     */
    private Uni<Void> fetchPartyMembers(Party party) {
        String partyId = party.getPartyId().toString();

        String memberQuery = "SELECT pm.*, c.name AS characterName, c.avatar AS characterAvatar " +
            "FROM party_member pm LEFT JOIN `character` c ON pm.characterId = c.characterUUID " +
            "WHERE pm.partyId = ? ORDER BY pm.joinedAt";

        return mysqlPool.preparedQuery(memberQuery)
            .execute(Tuple.of(partyId))
            .map(rows -> {
                List<PartyMember> members = new ArrayList<>();
                rows.forEach(row -> {
                    PartyMember member = PartyMember.builder()
                        .id(uuidFromString(row.getString("id")))
                        .partyId(uuidFromString(row.getString("partyId")))
                        .userId(uuidFromString(row.getString("userId")))
                        .characterId(uuidFromString(row.getString("characterId")))
                        .role(row.getString("role"))
                        .joinedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("joinedAt")))
                        .build();
                    members.add(member);
                });
                party.setMembers(members);
                return null;
            })
            .onFailure().invoke(err -> {
                log.warn("Failed to fetch members for party {}", partyId, err);
                party.setMembers(List.of());
            })
            .replaceWithVoid();
    }

    private Party mapPartyRow(Row row) {
        return Party.builder()
            .partyId(uuidFromString(row.getString("partyId")))
            .name(row.getString("name"))
            .description(row.getString("description"))
            .leaderId(uuidFromString(row.getString("leaderId")))
            .inviteCode(row.getString("inviteCode"))
            .maxMembers(row.getInteger("maxMembers"))
            .isActive(row.getBoolean("isActive"))
            .isPublic(row.getBoolean("isPublic"))
            .createdAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))
            .updatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")))
            .build();
    }

    private String generateInviteCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    /**
     * Null-safe UUID conversion from String.
     */
    private static UUID uuidFromString(String str) {
        return str != null ? UUID.fromString(str) : null;
    }
}
