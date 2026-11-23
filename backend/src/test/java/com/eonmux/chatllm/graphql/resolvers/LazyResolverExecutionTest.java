package com.eonmux.chatllm.graphql.resolvers;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LazyResolverExecutionTest {
    @SuppressWarnings("unchecked")
    private Pool emptyDatabase(AtomicBoolean executed) {
        Pool pool = mock(Pool.class, RETURNS_DEEP_STUBS);
        RowSet<Row> rows = mock(RowSet.class);
        when(rows.size()).thenReturn(0);
        doAnswer(invocation -> null).when(rows).forEach(any());
        when(pool.preparedQuery(anyString()).execute(any(Tuple.class)))
            .thenReturn(Uni.createFrom().deferred(() -> {
                executed.set(true);
                return Uni.createFrom().item(rows);
            }));
        return pool;
    }

    @Test
    void activeSceneExecutesItsDatabaseRead() throws Exception {
        AtomicBoolean executed = new AtomicBoolean();
        SceneResolver resolver = new SceneResolver();
        resolver.mysqlPool = emptyDatabase(executed);
        CompletableFuture<Object> future = new CompletableFuture<>();
        var method = SceneResolver.class.getDeclaredMethod("doActiveScene", String.class, CompletableFuture.class);
        method.setAccessible(true);
        method.invoke(resolver, "conversation", future);
        assertNull(future.get(1, TimeUnit.SECONDS));
        assertTrue(executed.get());
    }

    @Test
    void generatedImagesExecutesItsDatabaseRead() throws Exception {
        AtomicBoolean executed = new AtomicBoolean();
        ImageResolver resolver = new ImageResolver();
        resolver.mysqlPool = emptyDatabase(executed);
        CompletableFuture<Object> future = new CompletableFuture<>();
        var method = ImageResolver.class.getDeclaredMethod("doGeneratedImages", String.class, Integer.class, CompletableFuture.class);
        method.setAccessible(true);
        method.invoke(resolver, "conversation", 10, future);
        assertEquals(java.util.List.of(), future.get(1, TimeUnit.SECONDS));
        assertTrue(executed.get());
    }
}
