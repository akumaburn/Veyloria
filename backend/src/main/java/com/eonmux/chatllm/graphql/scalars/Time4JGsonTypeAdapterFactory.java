package com.eonmux.chatllm.graphql.scalars;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import net.time4j.Moment;
import net.time4j.PlainTimestamp;
import net.time4j.format.expert.ChronoFormatter;
import net.time4j.format.expert.PatternType;
import net.time4j.tz.ZonalOffset;

import java.io.IOException;
import java.util.Locale;

public class Time4JGsonTypeAdapterFactory implements TypeAdapterFactory {

    private static final ChronoFormatter<PlainTimestamp> TS_FORMATTER =
            ChronoFormatter.ofTimestampPattern("uuuu-MM-dd'T'HH:mm:ss", PatternType.CLDR, Locale.ROOT);

    private static final ChronoFormatter<Moment> MOMENT_FORMATTER =
            ChronoFormatter.ofMomentPattern("uuuu-MM-dd'T'HH:mm:ssXXX", PatternType.CLDR, Locale.ROOT, ZonalOffset.UTC);

    @Override
    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        if (type.getRawType() == PlainTimestamp.class) {
            return (TypeAdapter<T>) new PlainTimestampAdapter();
        }
        if (type.getRawType() == Moment.class) {
            return (TypeAdapter<T>) new MomentAdapter();
        }
        return null;
    }

    private static class PlainTimestampAdapter extends TypeAdapter<PlainTimestamp> {
        @Override
        public void write(JsonWriter out, PlainTimestamp value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public PlainTimestamp read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            String text = in.nextString();
            if (text == null || text.isEmpty()) return null;
            try {
                return TS_FORMATTER.parse(text);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static class MomentAdapter extends TypeAdapter<Moment> {
        @Override
        public void write(JsonWriter out, Moment value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Moment read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            String text = in.nextString();
            if (text == null || text.isEmpty()) return null;
            try {
                return MOMENT_FORMATTER.parse(text);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
