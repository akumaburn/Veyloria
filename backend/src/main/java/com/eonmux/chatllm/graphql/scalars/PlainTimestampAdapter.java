package com.eonmux.chatllm.graphql.scalars;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.adapter.JsonbAdapter;
import net.time4j.PlainTimestamp;
import net.time4j.format.expert.ChronoFormatter;
import net.time4j.format.expert.PatternType;

import java.util.Locale;

/**
 * JSON-B adapter so SmallRye GraphQL treats PlainTimestamp as a String scalar.
 */
@ApplicationScoped
public class PlainTimestampAdapter implements JsonbAdapter<PlainTimestamp, String> {

    private static final ChronoFormatter<PlainTimestamp> ISO_FORMATTER =
            ChronoFormatter.ofTimestampPattern("uuuu-MM-dd'T'HH:mm:ss", PatternType.CLDR, Locale.ROOT);

    @Override
    public String adaptToJson(PlainTimestamp obj) {
        if (obj == null) return null;
        return obj.toString();
    }

    @Override
    public PlainTimestamp adaptFromJson(String obj) {
        if (obj == null || obj.isEmpty()) return null;
        try {
            return ISO_FORMATTER.parse(obj);
        } catch (Exception e) {
            return null;
        }
    }
}
