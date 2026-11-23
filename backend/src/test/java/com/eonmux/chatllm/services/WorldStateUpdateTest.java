package com.eonmux.chatllm.services;

import com.eonmux.chatllm.models.WorldState;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.PreparedQuery;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorldStateUpdateTest {

    @Test
    @SuppressWarnings("unchecked")
    void theRowIsCreatedBeforeTheFirstUpdate() {
        Pool pool = mock(Pool.class);
        PreparedQuery<RowSet<Row>> query = mock(PreparedQuery.class);
        when(pool.preparedQuery(anyString())).thenReturn(query);
        when(query.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(mock(RowSet.class)));

        WorldState existing = WorldState.builder().currentLocation("before").build();
        WorldStateService service = spy(new WorldStateService());
        service.pool = pool;
        doReturn(Uni.createFrom().item(existing))
                .when(service).getOrCreateWorldState("conversation");

        service.updateWorldState("conversation",
                new JsonObject().put("currentLocation", "The Shattered Keep"))
                .await().indefinitely();

        // The absent-row bug was a bare UPDATE: it changed nothing and the
        // caller then received a newly created default state.
        verify(service, org.mockito.Mockito.atLeastOnce()).getOrCreateWorldState("conversation");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> params = ArgumentCaptor.forClass(Tuple.class);
        verify(pool).preparedQuery(sql.capture());
        verify(query).execute(params.capture());

        assertTrue(sql.getValue().contains("UPDATE world_state SET currentLocation = ?"), sql.getValue());
        assertEquals("The Shattered Keep", params.getValue().getString(0));
    }
}
