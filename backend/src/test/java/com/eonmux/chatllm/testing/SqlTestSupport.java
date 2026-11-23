package com.eonmux.chatllm.testing;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.*;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public final class SqlTestSupport {
    private SqlTestSupport() {}

    @SuppressWarnings("unchecked")
    public static Pool pool(BiFunction<String, Tuple, RowSet<Row>> execute) {
        Pool pool = mock(Pool.class);
        when(pool.preparedQuery(anyString())).thenAnswer(queryCall -> {
            String sql = queryCall.getArgument(0);
            PreparedQuery<RowSet<Row>> query = mock(PreparedQuery.class);
            when(query.execute(any(Tuple.class))).thenAnswer(call -> {
                Tuple parameters = call.getArgument(0);
                return Uni.createFrom().deferred(() -> Uni.createFrom().item(execute.apply(sql, parameters)));
            });
            return query;
        });
        return pool;
    }

    public static Row row(Map<String, Object> values) {
        Row row = mock(Row.class);
        when(row.getString(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        when(row.getInteger(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        when(row.getLong(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        when(row.getDouble(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        when(row.getBoolean(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        when(row.getLocalDateTime(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        return row;
    }

    @SuppressWarnings("unchecked")
    public static RowSet<Row> rows(Row... items) {
        RowSet<Row> rows = mock(RowSet.class);
        when(rows.size()).thenReturn(items.length);
        when(rows.iterator()).thenAnswer(call -> {
            Iterator<Row> iterator = Arrays.asList(items).iterator();
            RowIterator<Row> result = mock(RowIterator.class);
            when(result.hasNext()).thenAnswer(v -> iterator.hasNext());
            when(result.next()).thenAnswer(v -> iterator.next());
            return result;
        });
        doAnswer(call -> {
            Consumer<Row> consumer = call.getArgument(0);
            Arrays.asList(items).forEach(consumer);
            return null;
        }).when(rows).forEach(any());
        return rows;
    }

    public static RowSet<Row> affected(int count) {
        RowSet<Row> rows = rows();
        when(rows.rowCount()).thenReturn(count);
        return rows;
    }
}
