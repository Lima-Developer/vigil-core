package com.vigil.adapters.postgres;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;

/** Reads the target password from the environment or from a file; never from configuration files. */
final class PostgresPasswordSource {
    static final String PASSWORD_VARIABLE = "VIGIL_PG_PASSWORD";
    static final String PASSWORD_FILE_VARIABLE = "VIGIL_PG_PASSWORD_FILE";

    private PostgresPasswordSource() {
    }

    /**
     * Each call of the returned supplier yields a fresh array that the caller must zero after use. Exactly one of
     * the two variables must be set. Error messages name variables only, never values or file paths.
     */
    static Supplier<char[]> fromEnvironment(Map<String, String> environment) {
        var direct = environment.get(PASSWORD_VARIABLE);
        var file = environment.get(PASSWORD_FILE_VARIABLE);
        if ((direct == null) == (file == null)) {
            throw new IllegalArgumentException(
                    "Defina exatamente uma entre " + PASSWORD_VARIABLE + " e " + PASSWORD_FILE_VARIABLE);
        }
        if (direct != null) {
            requireNotEmpty(direct.length(), PASSWORD_VARIABLE);
            return direct::toCharArray;
        }
        requireNotEmpty(file.length(), PASSWORD_FILE_VARIABLE);
        var path = Path.of(file);
        return () -> readFile(path);
    }

    private static char[] readFile(Path path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler o arquivo apontado por " + PASSWORD_FILE_VARIABLE,
                    new IOException(e.getClass().getSimpleName()));
        }
        try {
            var decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
            var password = stripTrailingNewline(decoded);
            requireNotEmpty(password.length, PASSWORD_FILE_VARIABLE);
            return password;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static char[] stripTrailingNewline(CharBuffer decoded) {
        var chars = new char[decoded.remaining()];
        decoded.get(chars);
        var length = chars.length;
        while (length > 0 && (chars[length - 1] == '\n' || chars[length - 1] == '\r')) {
            length--;
        }
        var password = Arrays.copyOf(chars, length);
        Arrays.fill(chars, '\0');
        return password;
    }

    private static void requireNotEmpty(int length, String variable) {
        if (length == 0) {
            throw new IllegalArgumentException(variable + " não pode ser vazia");
        }
    }
}
