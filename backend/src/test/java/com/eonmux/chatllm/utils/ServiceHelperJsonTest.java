package com.eonmux.chatllm.utils;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Row;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceHelperJsonTest {

    private Row rowReturning(String column, Object value) {
        Row row = mock(Row.class);
        when(row.getValue(column)).thenReturn(value);
        return row;
    }

    @Test
    void readsAJsonColumnInEveryShapeTheDriverReturns() {
        // The MySQL client decodes JSON columns to JsonObject. Row.getString
        // throws ClassCastException for that case, which broke map reads.
        assertEquals("{\"name\":\"keep\"}",
            ServiceHelper.safeGetJsonText(rowReturning("metadata", new JsonObject().put("name", "keep")), "metadata"));

        assertEquals("[\"a\",\"b\"]",
            ServiceHelper.safeGetJsonText(rowReturning("ids", new JsonArray().add("a").add("b")), "ids"));

        assertEquals("{\"kind\":\"town\"}",
            ServiceHelper.safeGetJsonText(rowReturning("metadata", "{\"kind\":\"town\"}"), "metadata"));

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", "town");
        assertEquals("{\"kind\":\"town\"}",
            ServiceHelper.safeGetJsonText(rowReturning("metadata", map), "metadata"));

        assertEquals("[\"a\"]",
            ServiceHelper.safeGetJsonText(rowReturning("ids", List.of("a")), "ids"));
    }

    @Test
    void returnsNullForNullOrEmptyColumns() {
        assertNull(ServiceHelper.safeGetJsonText(rowReturning("metadata", null), "metadata"));
        assertNull(ServiceHelper.safeGetJsonText(rowReturning("metadata", ""), "metadata"));
    }
}
