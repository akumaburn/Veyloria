package com.eonmux.chatllm.map;

import com.eonmux.chatllm.models.WorldMap;
import com.eonmux.chatllm.testing.SqlTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapServiceQueryTest {

    @Test void listQueriesReadTheSummaryInsteadOfTheWholeMapBody() {
        AtomicReference<String> captured = new AtomicReference<>();
        MapService service = new MapService();
        service.mysqlPool = SqlTestSupport.pool((sql, parameters) -> {
            captured.set(sql);
            return SqlTestSupport.rows();
        });

        List<WorldMap> maps = service.getMaps("conversation-1").await().indefinitely();

        assertTrue(maps.isEmpty());
        // A 4 MB metadata column exceeds MySQL's default 256 KB sort buffer
        // when the list query uses ORDER BY. The list must read summary columns.
        assertFalse(captured.get().contains("metadata"), captured.get());
        assertTrue(captured.get().contains("ORDER BY createdAt"), captured.get());
    }
}
