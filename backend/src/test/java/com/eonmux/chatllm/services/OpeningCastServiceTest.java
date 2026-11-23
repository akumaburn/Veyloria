package com.eonmux.chatllm.services;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.eonmux.chatllm.testing.SqlTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OpeningCastServiceTest {
    private final String conversation = "conversation";
    private final String owner = "owner";

    @Test void replacesTheProvisionalCastWithTheScenarioFit() {
        List<String> introduced = new ArrayList<>();
        OpeningCastService service = new OpeningCastService();
        service.pool = pool((sql, params) -> {
            if (sql.contains("FROM conversation_character cc") && sql.contains("c.name")) {
                return rows(
                        castRow("a", "Sasha", 0, "Dungeon guide", "guides expeditions through dungeon ruins"),
                        castRow("b", "Mira", 1, "Dungeon scholar", "studies dungeon history"),
                        castRow("c", "Nia", 2, "Dungeon healer", "heals divers in the dungeon"),
                        castRow("d", "Oren", 3, "Merchant", "prices cargo at the docks"));
            }
            if (sql.contains("FROM character_affinity")) {
                return rows(
                        row(Map.of("fromActor", "a", "toActor", "b", "points", 60)),
                        row(Map.of("fromActor", "b", "toActor", "a", "points", 40)),
                        row(Map.of("fromActor", "b", "toActor", "c", "points", 40)),
                        row(Map.of("fromActor", "c", "toActor", "b", "points", 20)));
            }
            throw new AssertionError("Unexpected query: " + sql);
        });
        wireTransaction(service, (sql, params) -> {
            if (sql.contains("SET isIntroduced = TRUE")) {
                for (int i = 2; i < params.size(); i++) introduced.add(params.getString(i));
            }
            return affected(1);
        });

        service.selectOpeningCast(conversation, owner, "Dungeon Diving", 3).await().indefinitely();

        assertEquals(List.of("a", "b", "c"), introduced);
    }

    @Test void leavesTheProvisionalCastInPlaceWhenSelectionFails() {
        OpeningCastService service = new OpeningCastService();
        service.pool = pool((sql, params) -> {
            throw new IllegalStateException("database unavailable");
        });

        service.selectOpeningCast(conversation, owner, "Dungeon Diving", 3).await().indefinitely();

        verify(service.pool, never()).withTransaction(any());
    }

    @Test void aScenarioThatNamesOneMemberPutsThatMemberOnstage() {
        List<String> introduced = new ArrayList<>();
        OpeningCastService service = new OpeningCastService();
        service.pool = pool((sql, params) -> {
            if (sql.contains("FROM conversation_character cc") && sql.contains("c.name")) {
                return rows(
                        castRow("a", "Sasha", 0, "Mercenary", "keeps to the shadows"),
                        castRow("b", "Mira", 1, "Scholar", "studies old maps"),
                        castRow("c", "Nia", 2, "Healer", "tends the wounded"),
                        castRow("d", "Oren", 3, "Merchant", "prices every risk"));
            }
            if (sql.contains("FROM character_affinity")) return rows();
            throw new AssertionError("Unexpected query: " + sql);
        });
        wireTransaction(service, (sql, params) -> {
            if (sql.contains("SET isIntroduced = TRUE")) {
                for (int i = 2; i < params.size(); i++) introduced.add(params.getString(i));
            }
            return affected(1);
        });

        service.selectOpeningCast(conversation, owner,
                "Mira Solis discovers a sealed door beneath the city", 3).await().indefinitely();

        assertTrue(introduced.contains("b"));
    }

    private Row castRow(String id, String name, int order, String role, String description) {
        return row(Map.of(
                "characterId", id, "turnOrder", order, "name", name,
                "description", role + ". " + description,
                "personality", "", "backstory", "", "goals", "",
                "systemPrompt", "Canonical identity: " + name + "; " + role
                        + "; age 30; pronouns they/them.",
                "greeting", ""));
    }

    @SuppressWarnings("unchecked")
    private void wireTransaction(OpeningCastService service,
                                 java.util.function.BiFunction<String, Tuple, RowSet<Row>> execute) {
        SqlConnection connection = mock(SqlConnection.class);
        Pool transactionQueries = pool(execute);
        when(connection.preparedQuery(anyString()))
                .thenAnswer(call -> transactionQueries.preparedQuery(call.getArgument(0)));
        when(service.pool.withTransaction(any())).thenAnswer(call -> {
            Function<SqlConnection, Uni<Object>> transaction = call.getArgument(0);
            return Uni.createFrom().deferred(() -> transaction.apply(connection));
        });
    }
}
