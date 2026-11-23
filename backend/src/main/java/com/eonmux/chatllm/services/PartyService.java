package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.Party;
import com.eonmux.chatllm.models.PartyMember;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@ApplicationScoped
@Slf4j
public class PartyService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    private final Random random = new Random();

    public Uni<Party> createParty(String name, String leaderId, String description, Boolean isPublic) {
        String id = UUID.randomUUID().toString();
        String inviteCode = generateInviteCode();
        String insert = "INSERT INTO party (partyId, name, leaderId, description, isPublic, inviteCode) VALUES (?, ?, ?, ?, ?, ?)";
        return pool.preparedQuery(insert)
            .execute(Tuple.of(id, name, leaderId, description, isPublic != null ? isPublic : false, inviteCode))
            .chain(v -> {
                // Add leader as member
                String memberId = UUID.randomUUID().toString();
                String memberInsert = "INSERT INTO party_member (id, partyId, userId, role) VALUES (?, ?, ?, 'leader')";
                return pool.preparedQuery(memberInsert).execute(Tuple.of(memberId, id, leaderId));
            })
            .map(v -> Party.builder()
                .partyId(UUID.fromString(id))
                .name(name)
                .leaderId(UUID.fromString(leaderId))
                .description(description)
                .isPublic(isPublic != null ? isPublic : false)
                .inviteCode(inviteCode)
                .build());
    }

    public Uni<Party> getParty(String partyId) {
        return pool.preparedQuery("SELECT * FROM party WHERE partyId = ?")
            .execute(Tuple.of(partyId))
            .chain(rows -> {
                if (rows.size() == 0) return Uni.createFrom().failure(new RuntimeException("Party not found"));
                Party party = mapRowToParty(rows.iterator().next());
                return getMembers(partyId).map(members -> {
                    party.setMembers(members);
                    return party;
                });
            });
    }

    public Uni<Party> joinByCode(String inviteCode, String userId) {
        return pool.preparedQuery("SELECT * FROM party WHERE inviteCode = ?")
            .execute(Tuple.of(inviteCode))
            .chain(rows -> {
                if (rows.size() == 0) return Uni.createFrom().failure(new RuntimeException("Invalid invite code"));
                Party party = mapRowToParty(rows.iterator().next());
                return pool.preparedQuery("SELECT COUNT(*) as cnt FROM party_member WHERE partyId = ? AND userId = ?")
                    .execute(Tuple.of(party.getPartyId().toString(), userId))
                    .chain(countRows -> {
                        long count = countRows.iterator().next().getLong("cnt");
                        if (count > 0) return Uni.createFrom().item(party);
                        String memberId = UUID.randomUUID().toString();
                        return pool.preparedQuery("INSERT INTO party_member (id, partyId, userId) VALUES (?, ?, ?)")
                            .execute(Tuple.of(memberId, party.getPartyId().toString(), userId))
                            .map(v -> party);
                    });
            });
    }

    public Uni<List<PartyMember>> getMembers(String partyId) {
        return pool.preparedQuery("SELECT * FROM party_member WHERE partyId = ? ORDER BY joinedAt")
            .execute(Tuple.of(partyId))
            .map(rows -> {
                List<PartyMember> members = new ArrayList<>();
                for (Row row : rows) {
                    PartyMember m = new PartyMember();
                    m.setId(UUID.fromString(row.getString("id")));
                    m.setPartyId(UUID.fromString(row.getString("partyId")));
                    m.setUserId(UUID.fromString(row.getString("userId")));
                    if (row.getString("characterId") != null) m.setCharacterId(UUID.fromString(row.getString("characterId")));
                    m.setRole(row.getString("role"));
                    m.setJoinedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("joinedAt")));
                    members.add(m);
                }
                return members;
            });
    }

    private String generateInviteCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            code.append(chars.charAt(random.nextInt(chars.length())));
        }
        return code.toString();
    }

    private Party mapRowToParty(Row row) {
        Party p = new Party();
        p.setPartyId(UUID.fromString(row.getString("partyId")));
        p.setName(row.getString("name"));
        p.setLeaderId(UUID.fromString(row.getString("leaderId")));
        p.setDescription(row.getString("description"));
        p.setMaxMembers(row.getInteger("maxMembers"));
        p.setIsPublic(row.getBoolean("isPublic"));
        p.setInviteCode(row.getString("inviteCode"));
        p.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        p.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return p;
    }
}
