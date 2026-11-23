package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.models.CharacterDraft;
import com.eonmux.chatllm.models.inputs.CreateCharacterInput;
import com.eonmux.chatllm.models.inputs.RosterMemberInput;
import com.eonmux.chatllm.models.inputs.RosterRelationshipInput;
import com.eonmux.chatllm.services.auth.AuthContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CharacterRosterResolverTest {
    CharacterRosterResolver resolver;
    UUID owner = UUID.randomUUID();
    List<Tuple> inserted;
    List<Tuple> alignmentRows;
    List<Tuple> affinityRows;
    List<Tuple> groupMemberRows;
    AtomicBoolean committed = new AtomicBoolean(), rolledBack = new AtomicBoolean();
    boolean failInsert;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        resolver = new CharacterRosterResolver();
        resolver.graphQLAuthContext = mock(GraphQLAuthContext.class);
        var auth = mock(AuthContext.class);
        when(auth.getUserId()).thenReturn(owner);
        when(resolver.graphQLAuthContext.requireAuth()).thenReturn(auth);
        resolver.vertx = mock(Vertx.class, RETURNS_DEEP_STUBS);
        resolver.mysqlPool = mock(Pool.class);
        SqlConnection connection = mock(SqlConnection.class);

        PreparedQuery<RowSet<Row>> characters = mock(PreparedQuery.class);
        PreparedQuery<RowSet<Row>> alignments = mock(PreparedQuery.class);
        PreparedQuery<RowSet<Row>> feelings = mock(PreparedQuery.class);
        PreparedQuery<RowSet<Row>> groups = mock(PreparedQuery.class);
        PreparedQuery<RowSet<Row>> groupMembers = mock(PreparedQuery.class);
        when(groups.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(mock(RowSet.class)));
        when(groupMembers.executeBatch(anyList())).thenAnswer(call -> {
            groupMemberRows = call.getArgument(0);
            return Future.succeededFuture(mock(RowSet.class));
        });
        when(characters.executeBatch(anyList())).thenAnswer(call -> {
            inserted = call.getArgument(0);
            return failInsert ? Future.failedFuture(new IllegalStateException("Insert failed"))
                    : Future.succeededFuture(mock(RowSet.class));
        });
        when(alignments.executeBatch(anyList())).thenAnswer(call -> {
            alignmentRows = call.getArgument(0);
            return Future.succeededFuture(mock(RowSet.class));
        });
        when(feelings.executeBatch(anyList())).thenAnswer(call -> {
            affinityRows = call.getArgument(0);
            return Future.succeededFuture(mock(RowSet.class));
        });
        when(connection.preparedQuery(anyString())).thenAnswer(call -> {
            String sql = call.<String>getArgument(0);
            if (sql.contains("INSERT INTO `character`")) return characters;
            if (sql.contains("character_alignment")) return alignments;
            if (sql.contains("character_affinity")) return feelings;
            if (sql.contains("INSERT INTO character_group ")) return groups;
            if (sql.contains("character_group_member")) return groupMembers;
            return mock(PreparedQuery.class);
        });
        when(resolver.mysqlPool.withTransaction(any())).thenAnswer(call -> {
            Function<SqlConnection, Future<List<Character>>> action = call.getArgument(0);
            return action.apply(connection).onSuccess(value -> committed.set(true)).onFailure(error -> rolledBack.set(true));
        });
    }

    private CreateCharacterInput member(String name) {
        var input = new CreateCharacterInput();
        input.setName(name);
        input.setSystemPrompt("Shared setting and established relationships.");
        return input;
    }

    private RosterMemberInput entry(String name) {
        return new RosterMemberInput(member(name), null);
    }

    private List<Character> create(List<RosterMemberInput> inputs) throws Exception {
        return resolver.createCharacterRoster(inputs, null, null).get(2, TimeUnit.SECONDS);
    }

    @Test void savesAllMembersWithNewIdsAndAuthenticatedOwnershipInOneTransaction() throws Exception {
        List<Character> saved = create(List.of(entry("Maya Chen"), entry("Maya Torres")));
        assertTrue(committed.get()); assertFalse(rolledBack.get());
        assertEquals(2, inserted.size());
        assertNotEquals(saved.get(0).getId(), saved.get(1).getId());
        for (int i = 0; i < 2; i++) {
            assertEquals(38, inserted.get(i).size());
            assertEquals(owner.toString(), inserted.get(i).getString(35));
            assertFalse(inserted.get(i).getBoolean(26));
            assertEquals(owner, saved.get(i).getOwnerId());
        }
        assertNull(alignmentRows);
        assertNull(affinityRows);
        verify(resolver.mysqlPool, times(1)).withTransaction(any());
        verify(resolver.vertx.eventBus()).publish(eq("user:" + owner + ":characters:updated"), any());
    }

    @Test void profileFactsAndAttributesReachTheSavedRow() throws Exception {
        var input = member("Maya Chen");
        input.setGender("Woman");
        input.setPronouns("she/her");
        input.setAgeYears(31);
        input.setHeight("5 ft 6 in");
        input.setBuild("Slight");
        input.setHair("Black, bobbed");
        input.setEyes("Hazel");
        input.setStrength(7);
        input.setDexterity(14);
        input.setConstitution(9);
        input.setIntelligence(16);
        input.setWisdom(12);
        input.setCharisma(11);
        List<Character> saved = create(List.of(new RosterMemberInput(input, null), entry("Captain Rook")));

        Tuple row = inserted.get(0);
        assertEquals("Woman", row.getString(6));
        assertEquals("she/her", row.getString(7));
        assertEquals(31, row.getInteger(8));
        assertEquals("5 ft 6 in", row.getString(9));
        assertEquals("Slight", row.getString(10));
        assertEquals("Black, bobbed", row.getString(11));
        assertEquals("Hazel", row.getString(12));
        assertEquals(7, row.getInteger(13));
        assertEquals(14, row.getInteger(14));
        assertEquals(9, row.getInteger(15));
        assertEquals(16, row.getInteger(16));
        assertEquals(12, row.getInteger(17));
        assertEquals(11, row.getInteger(18));
        assertEquals(7, saved.get(0).getStrength());
        assertEquals(16, saved.get(0).getIntelligence());
    }

    @Test void savesGeneratedAlignmentsAndDirectionalFeelingsTogether() throws Exception {
        var maya = member("Maya Chen");
        maya.setLawfulness(70); maya.setGoodness(70);
        var rook = member("Captain Rook");
        rook.setLawfulness(-70); rook.setGoodness(-70);
        var feeling = new RosterRelationshipInput("Captain Rook", "hate", "Sold out the harbor");
        List<Character> saved = create(List.of(
                new RosterMemberInput(maya, List.of(feeling)),
                new RosterMemberInput(rook, List.of())));

        assertEquals(2, alignmentRows.size());
        assertEquals(70, alignmentRows.get(0).getInteger(4));
        assertEquals(70, alignmentRows.get(0).getInteger(5));
        assertEquals(-70, alignmentRows.get(1).getInteger(4));
        assertEquals(-70, alignmentRows.get(1).getInteger(5));

        assertEquals(1, affinityRows.size());
        Tuple affinity = affinityRows.get(0);
        assertEquals(saved.get(0).getId().toString(), affinity.getString(2));
        assertEquals(saved.get(1).getId().toString(), affinity.getString(3));
        assertEquals(saved.get(0).getId().toString(), affinity.getString(4));
        assertEquals(saved.get(1).getId().toString(), affinity.getString(5));
        assertEquals(-60, affinity.getInteger(6));
    }

    @Test void neutralGeneratedConnectionsStayReviewableButWriteNoRows() throws Exception {
        JsonObject member = new JsonObject().put("name", "Maya Chen").put("alignment", "Lawful Good")
                .put("relationships", new JsonArray().add(new JsonObject().put("targetName", "Captain Rook")
                        .put("feeling", "NEUTRAL").put("description", "A guarded truce after the siege")));
        CharacterDraft draft = CharacterRosterResolver.draft(member);
        assertEquals("LAWFUL_GOOD", draft.getAlignment());
        assertEquals(1, draft.getRelationships().size());
        assertEquals("Captain Rook", draft.getRelationships().get(0).getTargetName());
        assertEquals("NEUTRAL", draft.getRelationships().get(0).getTier());

        List<Character> saved = create(List.of(entry("Maya Chen"), entry("Captain Rook")));
        assertEquals(2, saved.size());
        assertNull(affinityRows);
    }

    @Test void neutralFeelingsCreateNoRowsAndUnknownTargetsAreRejected() throws Exception {
        var maya = member("Maya Chen");
        var neutral = new RosterRelationshipInput("Captain Rook", "NEUTRAL", "A polite distance");
        create(List.of(new RosterMemberInput(maya, List.of(neutral)), entry("Captain Rook")));
        assertNull(affinityRows);

        RosterMemberInput withStranger = new RosterMemberInput(member("Maya Chen"),
                List.of(new RosterRelationshipInput("Someone Else", "LIKE", "A rumor")));
        assertThrows(org.eclipse.microprofile.graphql.GraphQLException.class,
                () -> resolver.createCharacterRoster(List.of(withStranger, entry("Captain Rook")), null, null));
    }

    @Test void insertFailureRollsBackAndPublishesNoSuccess() {
        failInsert = true;
        assertThrows(Exception.class, () -> create(List.of(entry("One"), entry("Two"))));
        assertFalse(committed.get()); assertTrue(rolledBack.get());
        verify(resolver.vertx.eventBus(), never()).publish(anyString(), any());
    }

    @Test void rejectsInvalidRostersBeforeOpeningATransaction() {
        assertThrows(org.eclipse.microprofile.graphql.GraphQLException.class, () -> resolver.createCharacterRoster(List.of(entry("One")), null, null));
        assertThrows(org.eclipse.microprofile.graphql.GraphQLException.class, () -> resolver.createCharacterRoster(List.of(entry("Same"), entry("SAME")), null, null));
        verifyNoInteractions(resolver.mysqlPool);
    }
}
