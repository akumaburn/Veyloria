package com.eonmux.chatllm.utils;

import net.time4j.Moment;
import net.time4j.PlainDate;
import net.time4j.PlainTimestamp;
import net.time4j.ClockUnit;
import net.time4j.Duration;
import net.time4j.format.expert.ChronoFormatter;
import net.time4j.format.expert.PatternType;
import net.time4j.tz.ZonalOffset;

import java.util.Locale;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Helper for converting between Time4J types and java.time types
 * at library boundaries (database driver, JWT library, etc.).
 *
 * Time4J is the primary representation everywhere in the codebase.
 * java.time conversions exist only at the boundary.
 */
public final class Time4JHelper {

    private static final ZonalOffset UTC = ZonalOffset.UTC;

    private Time4JHelper() {
    }

    // ──────────────────────────────────────────────
    //  now() helpers
    // ──────────────────────────────────────────────

    public static PlainTimestamp now() {
        return nowUTC();
    }

    /** Current instant in UTC (timezone-independent). */
    public static Moment nowInstant() {
        return Moment.nowInSystemTime();
    }

    /** Current PlainTimestamp in UTC. Replacement for missing {@code PlainTimestamp.nowInUTC()}. */
    public static PlainTimestamp nowUTC() {
        return PlainTimestamp.from(LocalDateTime.now(java.time.ZoneOffset.UTC));
    }

    /** Current ISO-8601 string in UTC. */
    public static String nowUTCString() {
        return LocalDateTime.now(java.time.ZoneOffset.UTC).toString();
    }

    // ──────────────────────────────────────────────
    //  PlainTimestamp <-> LocalDateTime (DB boundary)
    // ──────────────────────────────────────────────

    public static PlainTimestamp fromLocalDateTime(LocalDateTime ldt) {
        if (ldt == null) return null;
        return PlainTimestamp.from(ldt);
    }

    public static LocalDateTime toLocalDateTime(PlainTimestamp pts) {
        if (pts == null) return null;
        return pts.toTemporalAccessor();
    }

    // ──────────────────────────────────────────────
    //  Moment <-> Instant (JWT / library boundary)
    // ──────────────────────────────────────────────

    public static Moment fromInstant(Instant instant) {
        if (instant == null) return null;
        return Moment.from(instant);
    }

    public static Instant toInstant(Moment moment) {
        if (moment == null) return null;
        return Instant.ofEpochSecond(moment.getPosixTime(), moment.getNanosecond());
    }

    // ──────────────────────────────────────────────
    //  java.util.Date <-> Moment (JWT boundary)
    // ──────────────────────────────────────────────

    public static Moment fromDate(java.util.Date date) {
        if (date == null) return null;
        return Moment.from(date.toInstant());
    }

    public static java.util.Date toDate(Moment moment) {
        if (moment == null) return null;
        long millis = TimeUnit.SECONDS.toMillis(moment.getPosixTime())
                + moment.getNanosecond() / 1_000_000;
        return new java.util.Date(millis);
    }

    // ──────────────────────────────────────────────
    //  PlainDate <-> LocalDate
    // ──────────────────────────────────────────────

    public static PlainDate fromLocalDate(LocalDate ld) {
        if (ld == null) return null;
        return PlainDate.of(ld.getYear(), ld.getMonthValue(), ld.getDayOfMonth());
    }

    public static LocalDate toLocalDate(PlainDate pd) {
        if (pd == null) return null;
        return LocalDate.of(pd.getYear(), pd.getMonth(), pd.getDayOfMonth());
    }

    /**
     * Convert a possibly-null LocalDateTime to an ISO-8601 String, or null.
     * Safe for nullable database columns.
     */
    public static String localDateTimeToString(java.time.LocalDateTime ldt) {
        return ldt != null ? ldt.toString() : null;
    }

    // ──────────────────────────────────────────────
    //  Duration helpers
    // ──────────────────────────────────────────────

    public static long minutesBetween(PlainTimestamp start, PlainTimestamp end) {
        return ClockUnit.MINUTES.between(start, end);
    }

    public static long secondsBetween(PlainTimestamp start, PlainTimestamp end) {
        return ClockUnit.SECONDS.between(start, end);
    }

    public static Duration<ClockUnit> durationOfMinutes(long minutes) {
        return Duration.of(minutes, ClockUnit.MINUTES);
    }

    public static Duration<ClockUnit> durationOfSeconds(long seconds) {
        return Duration.of(seconds, ClockUnit.SECONDS);
    }

    // ──────────────────────────────────────────────
    //  Formatting / parsing (Time4J ChronoFormatter)
    // ──────────────────────────────────────────────

    private static final ChronoFormatter<PlainTimestamp> TIMESTAMP_FORMATTER =
            ChronoFormatter.ofTimestampPattern("yyyy-MM-dd HH:mm:ss", PatternType.CLDR, Locale.ROOT);

    public static String formatTimestamp(PlainTimestamp ts) {
        if (ts == null) return null;
        return TIMESTAMP_FORMATTER.print(ts);
    }

    public static PlainTimestamp parseTimestamp(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return TIMESTAMP_FORMATTER.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    // ──────────────────────────────────────────────
    //  RFC3339 formatting (for GraphQL / JSON)
    // ──────────────────────────────────────────────

    private static final ChronoFormatter<Moment> RFC3339_FORMATTER =
            ChronoFormatter.ofMomentPattern("yyyy-MM-dd'T'HH:mm:ssXXX", PatternType.CLDR, Locale.ROOT, UTC);

    private static final ChronoFormatter<Moment> RFC3339_NANOS_FORMATTER =
            ChronoFormatter.ofMomentPattern("yyyy-MM-dd'T'HH:mm:ss,SSSSSSSSSXXX", PatternType.CLDR, Locale.ROOT, UTC);

    public static String serializeRfc3339(PlainTimestamp localDateTime) {
        if (localDateTime == null) return null;
        Moment moment = localDateTime.at(UTC);
        return RFC3339_FORMATTER.print(moment);
    }

    public static PlainTimestamp parseRfc3339(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            Moment moment = RFC3339_FORMATTER.parse(value);
            return moment.toZonalTimestamp(UTC);
        } catch (Exception e) {
            try {
                Moment moment = RFC3339_NANOS_FORMATTER.parse(value);
                return moment.toZonalTimestamp(UTC);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
