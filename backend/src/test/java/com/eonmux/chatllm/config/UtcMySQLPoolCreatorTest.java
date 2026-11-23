package com.eonmux.chatllm.config;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.Query;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UtcMySQLPoolCreatorTest {
    @Test
    @SuppressWarnings("unchecked")
    void connectionIsNotAvailableBeforeUtcInitializationCompletes() {
        SqlConnection connection = mock(SqlConnection.class);
        Query<RowSet<Row>> query = mock(Query.class);
        Promise<RowSet<Row>> initialized = Promise.promise();
        when(connection.query(anyString())).thenReturn(query);
        when(query.execute()).thenReturn(initialized.future());
        when(connection.close()).thenReturn(Future.succeededFuture());

        Future<Void> ready = UtcMySQLPoolCreator.initialize(connection);
        verify(connection, never()).close();
        assertFalse(ready.isComplete());
        initialized.complete(mock(RowSet.class));
        verify(connection).close();
        assertTrue(ready.succeeded());
    }
}
