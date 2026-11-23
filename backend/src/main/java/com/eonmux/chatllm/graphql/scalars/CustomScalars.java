package com.eonmux.chatllm.graphql.scalars;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Custom GraphQL scalar adapters for SmallRye GraphQL.
 *
 * SmallRye GraphQL automatically handles:
 * - UUID (maps java.util.UUID)
 * - DateTime/LocalDateTime (maps java.time types)
 * - JSON/JsonObject (maps Jakarta JSON types)
 * - Long (maps java.lang.Long)
 *
 * We register custom transformation logic for Base64 and DateTime
 * via adapter classes annotated for SmallRye scalar binding.
 *
 * Note: SmallRye auto-discovers java.time.LocalDateTime and serializes it
 * as ISO-8601. For full RFC3339 control (UTC normalization), use
 * OffsetDateTime in model fields or register a custom adapter.
 */
@Slf4j
@ApplicationScoped
public class CustomScalars {

    /**
     * DateTime formatting helper.
     * SmallRye auto-handles LocalDateTime, but this utility is kept for
     * explicit RFC3339 conversion in resolvers that return Map/String dates.
     */
    private static final DateTimeFormatter RFC3339_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * Serialize a LocalDateTime to RFC3339 UTC string.
     * Use this in resolvers that need to put date strings into Maps.
     */
    public static String serializeDateTime(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        ZonedDateTime zonedDateTime = localDateTime.atZone(ZoneId.systemDefault());
        OffsetDateTime offsetDateTime = zonedDateTime.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
        return offsetDateTime.format(RFC3339_FORMATTER);
    }

    /**
     * Parse an RFC3339 date string to LocalDateTime.
     */
    public static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            OffsetDateTime offsetDateTime = OffsetDateTime.parse(value, RFC3339_FORMATTER);
            return offsetDateTime.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException e2) {
                log.warn("Failed to parse DateTime value: {}", value);
                return null;
            }
        }
    }

    /**
     * Encode a string to Base64.
     */
    public static String encodeBase64(String value) {
        if (value == null) {
            return null;
        }
        if (isBase64(value)) {
            return value;
        }
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decode a Base64 string.
     */
    public static String decodeBase64(String value) {
        if (value == null) {
            return null;
        }
        if (!isBase64(value)) {
            return value;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to decode Base64 value", e);
            return value;
        }
    }

    /**
     * Check if a string is Base64 encoded.
     */
    public static boolean isBase64(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.length() % 4 != 0) {
            return false;
        }
        return value.matches("^[A-Za-z0-9+/]*={0,2}$");
    }
}
