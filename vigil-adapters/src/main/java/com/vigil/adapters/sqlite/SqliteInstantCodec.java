package com.vigil.adapters.sqlite;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.format.SignStyle;
import java.util.Objects;

import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.NANO_OF_SECOND;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;
import static java.time.temporal.ChronoField.YEAR;

/**
 * Canonical TEXT representation of instants stored in SQLite: {@code yyyy-MM-ddTHH:mm:ss.nnnnnnnnnZ} in UTC,
 * always with nine fractional digits so that lexicographic order matches chronological order.
 */
public final class SqliteInstantCodec {
    public static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z");
    public static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999999Z");

    private static final DateTimeFormatter FORMAT = new DateTimeFormatterBuilder()
            .appendValue(YEAR, 4, 4, SignStyle.NOT_NEGATIVE)
            .appendLiteral('-')
            .appendValue(MONTH_OF_YEAR, 2)
            .appendLiteral('-')
            .appendValue(DAY_OF_MONTH, 2)
            .appendLiteral('T')
            .appendValue(HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(MINUTE_OF_HOUR, 2)
            .appendLiteral(':')
            .appendValue(SECOND_OF_MINUTE, 2)
            .appendFraction(NANO_OF_SECOND, 9, 9, true)
            .appendLiteral('Z')
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT);

    private SqliteInstantCodec() {
    }

    public static String encode(Instant instant) {
        Objects.requireNonNull(instant, "Instant must not be null.");
        requireSupported(instant);
        return FORMAT.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    public static Instant decode(String text) {
        Objects.requireNonNull(text, "Instant text must not be null.");

        Instant instant;
        try {
            instant = LocalDateTime.parse(text, FORMAT).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("Instant text is not in the canonical UTC format: " + text, invalid);
        }

        requireSupported(instant);
        return instant;
    }

    private static void requireSupported(Instant instant) {
        if (instant.isBefore(MIN) || instant.isAfter(MAX)) {
            throw new IllegalArgumentException("Instant is outside the supported range: " + instant);
        }
    }
}
