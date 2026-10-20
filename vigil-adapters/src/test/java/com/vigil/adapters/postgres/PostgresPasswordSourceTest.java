package com.vigil.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PostgresPasswordSourceTest {

    @TempDir
    Path directory;

    @Test
    void shouldReadThePasswordFromTheEnvironmentAsAFreshArrayEachTime() {
        var canary = PostgresTestSupport.canary();
        var supplier = PostgresPasswordSource.fromEnvironment(Map.of("VIGIL_PG_PASSWORD", canary));

        var first = supplier.get();
        var second = supplier.get();

        assertArrayEquals(canary.toCharArray(), first);
        assertArrayEquals(first, second);
        assertNotSame(first, second);
    }

    @ParameterizedTest
    @CsvSource({"abc, abc", "'abc\n', abc", "'abc\r\n', abc", "'a b\n\n', a b", "' abc ', ' abc '"})
    void shouldReadThePasswordFromTheFileWithoutTrailingNewlines(String content, String expected) throws IOException {
        var file = directory.resolve("pw");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        var supplier = PostgresPasswordSource.fromEnvironment(Map.of("VIGIL_PG_PASSWORD_FILE", file.toString()));

        assertArrayEquals(expected.toCharArray(), supplier.get());
    }

    @Test
    void shouldRejectMissingOrAmbiguousSources() {
        var file = directory.resolve("pw").toString();

        assertThrows(IllegalArgumentException.class, () -> PostgresPasswordSource.fromEnvironment(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> PostgresPasswordSource.fromEnvironment(
                Map.of("VIGIL_PG_PASSWORD", "x", "VIGIL_PG_PASSWORD_FILE", file)));
    }

    @Test
    void shouldRejectEmptyValues() throws IOException {
        var empty = directory.resolve("empty");
        Files.writeString(empty, "\n");

        assertThrows(IllegalArgumentException.class,
                () -> PostgresPasswordSource.fromEnvironment(Map.of("VIGIL_PG_PASSWORD", "")));
        assertThrows(IllegalArgumentException.class,
                () -> PostgresPasswordSource.fromEnvironment(Map.of("VIGIL_PG_PASSWORD_FILE", "")));
        var supplier = PostgresPasswordSource.fromEnvironment(Map.of("VIGIL_PG_PASSWORD_FILE", empty.toString()));
        assertThrows(IllegalArgumentException.class, supplier::get);
    }

    @Test
    void shouldNotRevealThePathOfAnUnreadablePasswordFile() {
        var missing = directory.resolve("missing-" + PostgresTestSupport.canary());
        var supplier = PostgresPasswordSource.fromEnvironment(Map.of("VIGIL_PG_PASSWORD_FILE", missing.toString()));

        var thrown = assertThrows(UncheckedIOException.class, supplier::get);

        assertFalse(thrown.getMessage().contains(missing.toString()));
        assertFalse(thrown.getCause().getMessage().contains(missing.toString()));
        assertTrue(thrown.getMessage().contains("VIGIL_PG_PASSWORD_FILE"));
    }
}
