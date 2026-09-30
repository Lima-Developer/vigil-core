package com.vigil.adapters.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqliteInstantCodecTest {

    @ParameterizedTest(name = "{0} <-> {1}")
    @CsvSource({
            "2026-09-30T12:00:00Z,                  2026-09-30T12:00:00.000000000Z",
            "2026-09-30T12:00:00.123Z,              2026-09-30T12:00:00.123000000Z",
            "2026-09-30T12:00:00.000123Z,           2026-09-30T12:00:00.000123000Z",
            "2026-09-30T12:00:00.000000001Z,        2026-09-30T12:00:00.000000001Z",
            "2024-02-29T23:59:59.999999999Z,        2024-02-29T23:59:59.999999999Z",
            "0001-01-01T00:00:00Z,                  0001-01-01T00:00:00.000000000Z",
            "9999-12-31T23:59:59.999999999Z,        9999-12-31T23:59:59.999999999Z"
    })
    void shouldEncodeWithNineFractionalDigitsAndDecodeToTheSameInstant(String instant, String encoded) {
        var original = Instant.parse(instant);

        assertAll(
                () -> assertEquals(encoded, SqliteInstantCodec.encode(original)),
                () -> assertEquals(original, SqliteInstantCodec.decode(encoded))
        );
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "0000-12-31T23:59:59.999999999Z",
            "-0001-01-01T00:00:00Z",
            "+10000-01-01T00:00:00Z"
    })
    void shouldRejectEncodingInstantsOutsideTheSupportedRange(String instant) {
        var outOfRange = Instant.parse(instant);

        assertThrows(IllegalArgumentException.class, () -> SqliteInstantCodec.encode(outOfRange));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "",
            "2026-09-30T12:00:00Z",
            "2026-09-30T12:00:00.123Z",
            "2026-09-30T12:00:00.1234567890Z",
            "2026-09-30T12:00:00.000000000+00:00",
            "2026-09-30T12:00:00.000000000",
            "2026-09-30T12:00:00.000000000z",
            "2026-09-30t12:00:00.000000000Z",
            "2026-09-30 12:00:00.000000000Z",
            " 2026-09-30T12:00:00.000000000Z",
            "2026-09-30T12:00:00.000000000Z ",
            "2026-9-30T12:00:00.000000000Z",
            "26-09-30T12:00:00.000000000Z",
            "+2026-09-30T12:00:00.000000000Z",
            "2026-02-30T12:00:00.000000000Z",
            "2025-02-29T12:00:00.000000000Z",
            "2026-13-01T12:00:00.000000000Z",
            "2026-00-10T12:00:00.000000000Z",
            "2026-09-30T24:00:00.000000000Z",
            "2026-09-30T12:60:00.000000000Z",
            "2026-09-30T12:00:60.000000000Z",
            "0000-12-31T23:59:59.999999999Z"
    })
    void shouldRejectNonCanonicalOrUnsupportedText(String text) {
        assertThrows(IllegalArgumentException.class, () -> SqliteInstantCodec.decode(text));
    }

    @Test
    void shouldRejectNullValues() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> SqliteInstantCodec.encode(null)),
                () -> assertThrows(NullPointerException.class, () -> SqliteInstantCodec.decode(null))
        );
    }

    @Test
    void shouldExposeTheDocumentedBoundaries() {
        assertAll(
                () -> assertEquals(Instant.parse("0001-01-01T00:00:00Z"), SqliteInstantCodec.MIN),
                () -> assertEquals(Instant.parse("9999-12-31T23:59:59.999999999Z"), SqliteInstantCodec.MAX)
        );
    }

    @Test
    void shouldKeepTextualOrderEqualToChronologicalOrder() {
        var instants = List.of(
                Instant.parse("2026-09-30T12:00:00.000000010Z"),
                SqliteInstantCodec.MAX,
                Instant.parse("2026-09-30T12:00:00Z"),
                Instant.parse("0999-01-01T00:00:00Z"),
                Instant.parse("2026-09-30T12:00:00.000000009Z"),
                SqliteInstantCodec.MIN,
                Instant.parse("2026-09-30T12:00:00.1Z")
        );

        var chronological = instants.stream().sorted().map(SqliteInstantCodec::encode).toList();
        var textual = instants.stream().map(SqliteInstantCodec::encode).sorted(Comparator.naturalOrder()).toList();

        assertEquals(chronological, textual);
    }
}
