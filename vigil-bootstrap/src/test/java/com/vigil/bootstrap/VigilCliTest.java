package com.vigil.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the persisted first flow only through {@link VigilCli}'s public API and a child JVM. */
class VigilCliTest {
    private static final Pattern DISCOVERED = Pattern.compile("case=([0-9a-f-]{36}) created=(true|false)");
    private static final String REASON = "Impacto alto na latência.";

    @TempDir
    Path directory;

    @Test
    void shouldDiscoverTriageAndShowAPersistedCase() {
        var env = env();

        var discover = run(env, "case", "discover");
        var caseId = caseId(discover);
        var triage = run(env, "case", "triage", "--case", caseId, "--reason", REASON);
        var show = run(env, "case", "show", "--case", caseId);

        assertAll(
                () -> assertEquals(0, discover.exit(), discover.err()),
                () -> assertTrue(discover.out().contains("created=true")),
                () -> assertEquals(0, triage.exit(), triage.err()),
                () -> assertEquals("case=" + caseId + " status=TRIAGED" + System.lineSeparator(), triage.out()),
                () -> assertEquals(0, show.exit(), show.err()),
                () -> assertEquals(List.of(
                        "case=" + caseId + " status=TRIAGED version=2",
                        "history=DISCOVERED->TRIAGED reason=" + REASON,
                        "event=ANALYSIS_CASE_DISCOVERED",
                        "event=QUERY_TRIAGED"), lines(show.out())),
                () -> assertEquals("", discover.err() + triage.err() + show.err())
        );
    }

    @Test
    void shouldReturnTheSameCaseWhenDiscoverIsRepeated() {
        var env = env();

        var first = run(env, "case", "discover");
        var second = run(env, "case", "discover");

        assertAll(
                () -> assertEquals(caseId(first), caseId(second)),
                () -> assertTrue(first.out().contains("created=true")),
                () -> assertTrue(second.out().contains("created=false"))
        );
    }

    @Test
    void shouldReadTheTriagedCaseFromANewJvmProcess() throws Exception {
        var env = env();
        var caseId = caseId(run(env, "case", "discover"));
        run(env, "case", "triage", "--case", caseId, "--reason", REASON);

        var child = runInChildJvm(env, "case", "show", "--case", caseId);

        assertAll(
                () -> assertEquals(0, child.exit(), child.err()),
                () -> assertEquals(List.of(
                        "case=" + caseId + " status=TRIAGED version=2",
                        "history=DISCOVERED->TRIAGED reason=" + REASON,
                        "event=ANALYSIS_CASE_DISCOVERED",
                        "event=QUERY_TRIAGED"), lines(child.out())),
                () -> assertEquals("", child.err()),
                () -> assertNoSql(child.out() + child.err())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test"})
    void applicationEntryUsesProfileTargetDefaults(String profile) throws Exception {
        var env = Map.of("VIGIL_DATABASE_PATH", directory.resolve(profile + ".db").toString(),
                "VIGIL_STORAGE_MIN_FREE_BYTES", "1", "SPRING_PROFILES_ACTIVE", profile);

        var result = runInChildJvm(VigilApplication.class, env);

        assertEquals(0, result.exit(), result.err());
        assertEquals("", result.err());
        assertTrue(result.out().contains("healthy=true"), result.out());
        assertTrue(Files.exists(directory.resolve(profile + ".db")));
    }

    @Test
    void caseCommandAcceptsPartialTargetOverrideWithProfileDefaults() {
        var variables = new HashMap<>(env());
        variables.put("SPRING_PROFILES_ACTIVE", "test");
        variables.put("VIGIL_TARGET_NAME", "case-demo");

        var result = run(Map.copyOf(variables), "case", "discover");

        assertEquals(0, result.exit(), result.err());
        assertTrue(result.out().contains("created=true"));
    }

    @Test
    void caseCommandRejectsInvalidIsolatedTargetOverridesBeforeStorage() {
        Map.of("VIGIL_TARGET_SSL_MODE", "disable", "VIGIL_TARGET_PORT", "invalid",
                "VIGIL_TARGET_CONNECT_TIMEOUT_SECONDS", "0").forEach((key, value) -> {
            var database = directory.resolve("case-" + key + ".db");
            var variables = new HashMap<String, String>();
            variables.put("VIGIL_DATABASE_PATH", database.toString());
            variables.put(key, value);

            var result = run(Map.copyOf(variables), "case", "discover");

            assertEquals(2, result.exit(), key + ": " + result.err());
            assertTrue(result.err().contains(key), result.err());
            assertFalse(Files.exists(database), key);
        });
    }

    @Test
    void shouldNotExposeSqlInOutputOrErrors() {
        var env = env();
        var caseId = caseId(run(env, "case", "discover"));
        run(env, "case", "triage", "--case", caseId, "--reason", REASON);

        var all = run(env, "case", "show", "--case", caseId).out()
                + run(env, "case", "triage", "--case", caseId, "--reason", REASON).err()
                + run(Map.of("VIGIL_DATABASE_PATH", directory.resolve("missing/vigil.db").toString()),
                "case", "discover").err();

        assertNoSql(all);
    }

    @Test
    void shouldRejectTriagingAnAlreadyTriagedCaseWithoutChangingIt() {
        var env = env();
        var caseId = caseId(run(env, "case", "discover"));
        run(env, "case", "triage", "--case", caseId, "--reason", REASON);

        var again = run(env, "case", "triage", "--case", caseId, "--reason", "Outro motivo secreto.");
        var show = run(env, "case", "show", "--case", caseId);

        assertAll(
                () -> assertEquals(1, again.exit()),
                () -> assertEquals("O caso não pode ser triado no estado atual." + System.lineSeparator(), again.err()),
                () -> assertTrue(show.out().contains("version=2")),
                () -> assertFalse(show.out().contains("secreto"))
        );
    }

    @Test
    void shouldReportAnUnknownCaseWithoutEchoingTheReason() {
        var result = run(env(), "case", "triage", "--case", UUID.randomUUID().toString(), "--reason", "motivo-sigiloso");

        assertAll(
                () -> assertEquals(1, result.exit()),
                () -> assertEquals("Caso não encontrado." + System.lineSeparator(), result.err()),
                () -> assertEquals("", result.out())
        );
        assertEquals(1, run(env(), "case", "show", "--case", UUID.randomUUID().toString()).exit());
    }

    @Test
    void shouldKeepAReasonOnASingleShowLine() {
        var env = env();
        var caseId = caseId(run(env, "case", "discover"));
        run(env, "case", "triage", "--case", caseId, "--reason", "linha um\nevent=FORGED");

        var show = run(env, "case", "show", "--case", caseId);

        assertFalse(lines(show.out()).contains("event=FORGED"));
    }

    static Stream<Arguments> invalidUsage() {
        var id = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of((Object) new String[]{"unknown"}),
                Arguments.of((Object) new String[]{"case"}),
                Arguments.of((Object) new String[]{"case", "unknown"}),
                Arguments.of((Object) new String[]{"case", "discover", "--extra", "x"}),
                Arguments.of((Object) new String[]{"case", "triage", "--reason", "motivo"}),
                Arguments.of((Object) new String[]{"case", "triage", "--case", id}),
                Arguments.of((Object) new String[]{"case", "triage", "--case", id, "--reason"}),
                Arguments.of((Object) new String[]{"case", "triage", "--case", id, "--reason", "  "}),
                Arguments.of((Object) new String[]{"case", "triage", "--case", id, "--reason", ""}),
                Arguments.of((Object) new String[]{"case", "triage", "--case", "not-a-uuid", "--reason", "motivo"}),
                Arguments.of((Object) new String[]{"case", "triage", "--case", id, "--case", id, "--reason", "motivo"}),
                Arguments.of((Object) new String[]{"case", "triage", "--case", id, "--reason", "a", "--other", "b"}),
                Arguments.of((Object) new String[]{"case", "show"}),
                Arguments.of((Object) new String[]{"case", "show", "--case", "not-a-uuid"}),
                Arguments.of((Object) new String[]{"case", "show", "--reason", "motivo"})
        );
    }

    @ParameterizedTest
    @MethodSource("invalidUsage")
    void shouldReturnUsageErrorAndLeaveNoDatabaseForInvalidArguments(String[] args) {
        var result = run(env(), args);

        assertAll(
                () -> assertEquals(2, result.exit(), result.err()),
                () -> assertEquals("", result.out()),
                () -> assertFalse(result.err().isBlank()),
                () -> assertFalse(Files.exists(directory.resolve("vigil.db")))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"discover", "show", "triage"})
    void shouldRequireTheDatabasePath(String command) {
        var id = UUID.randomUUID().toString();
        var args = switch (command) {
            case "show" -> new String[]{"case", "show", "--case", id};
            case "triage" -> new String[]{"case", "triage", "--case", id, "--reason", "motivo"};
            default -> new String[]{"case", "discover"};
        };

        var result = run(Map.of(), args);

        assertAll(
                () -> assertEquals(2, result.exit()),
                () -> assertTrue(result.err().contains("VIGIL_DATABASE_PATH"))
        );
        assertEquals(2, run(Map.of("VIGIL_DATABASE_PATH", "  "), args).exit());
    }

    @Test
    void shouldFailWithOwnTextWhenTheDatabaseDirectoryDoesNotExist() {
        var path = directory.resolve("missing/vigil.db");

        var result = run(Map.of("VIGIL_DATABASE_PATH", path.toString()), "case", "discover");

        assertAll(
                () -> assertEquals(1, result.exit()),
                () -> assertEquals("", result.out()),
                () -> assertEquals("Falha ao acessar o armazenamento. Verifique o volume e a integridade do banco."
                        + System.lineSeparator(), result.err())
        );
    }

    @Test
    void shouldFailWithOwnTextWhenTheDatabaseFileIsNotSqlite() throws IOException {
        var path = Files.writeString(directory.resolve("vigil.db"), "this is not a sqlite database at all, ".repeat(10));

        var result = run(Map.of("VIGIL_DATABASE_PATH", path.toString()), "case", "discover");

        assertAll(
                () -> assertEquals(1, result.exit()),
                () -> assertFalse(result.err().contains("not a database")),
                () -> assertTrue(result.err().startsWith("Falha ao acessar o armazenamento."))
        );
    }

    @Test
    void shouldReleaseTheDatabaseFileAfterEveryCommand() throws IOException {
        var env = env();
        var caseId = caseId(run(env, "case", "discover"));
        run(env, "case", "triage", "--case", caseId, "--reason", REASON);
        run(env, "case", "triage", "--case", caseId, "--reason", REASON);
        run(env, "case", "show", "--case", caseId);

        // SQLite removes the WAL and shared-memory files only when the last connection closes.
        assertAll(
                () -> assertTrue(Files.exists(directory.resolve("vigil.db"))),
                () -> assertFalse(Files.exists(directory.resolve("vigil.db-wal"))),
                () -> assertFalse(Files.exists(directory.resolve("vigil.db-shm")))
        );
    }

    @Test
    void shouldAllowDeletingDatabaseAndTemporaryDirectoryAfterSuccessAndFailure() throws IOException {
        var env = env();
        var database = directory.resolve("vigil.db");
        var id = caseId(run(env, "case", "discover"));
        run(env, "case", "triage", "--case", id, "--reason", REASON);
        var failed = run(env, "case", "triage", "--case", id, "--reason", "segunda tentativa");
        assertEquals(1, failed.exit());

        Files.deleteIfExists(directory.resolve("vigil.db-wal"));
        Files.deleteIfExists(directory.resolve("vigil.db-shm"));
        Files.delete(database);
        Files.delete(directory);

        assertFalse(Files.exists(directory));
    }

    @Test
    void shouldNotPrintSqlOrJdbcDetailsOnSuccessOrOperationalFailure() {
        var env = env();
        var id = caseId(run(env, "case", "discover"));
        var success = run(env, "case", "show", "--case", id);
        var missingCase = run(env, "case", "show", "--case", UUID.randomUUID().toString());
        var corruptPath = run(Map.of("VIGIL_DATABASE_PATH", directory.resolve("not-created/child.db").toString()),
                "case", "discover");

        assertNoSql(success.out() + success.err() + missingCase.out() + missingCase.err()
                + corruptPath.out() + corruptPath.err());
    }

    @Test
    void shouldKeepTheHealthCheckWhenNoArgumentsAreGiven() {
        var env = Map.of("VIGIL_DATABASE_PATH", directory.resolve("vigil.db").toString(),
                "VIGIL_STORAGE_MIN_FREE_BYTES", "1", "VIGIL_TARGET_NAME", "target", "VIGIL_ENVIRONMENT", "test",
                "VIGIL_TARGET_HOST", "localhost", "VIGIL_TARGET_DATABASE", "lab");

        var result = run(env);

        assertAll(() -> assertEquals(0, result.exit(), result.err()), () -> assertFalse(result.out().isBlank()));
    }

    private Map<String, String> env() {
        return Map.of("VIGIL_DATABASE_PATH", directory.resolve("vigil.db").toString());
    }

    private static String caseId(Result discover) {
        var matcher = DISCOVERED.matcher(discover.out().strip());
        assertTrue(matcher.matches(), discover.out() + discover.err());
        return matcher.group(1);
    }

    private static List<String> lines(String text) {
        return text.lines().toList();
    }

    private static void assertNoSql(String text) {
        assertFalse(Pattern.compile("(?i)\\b(select|insert|update|create\\s+table|sqlite|SQLException)\\b")
                .matcher(text).find(), text);
    }

    private static Result run(Map<String, String> env, String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int exit = VigilCli.run(args, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Result runInChildJvm(Map<String, String> env, String... args) throws Exception {
        return runInChildJvm(Main.class, env, args);
    }

    private static Result runInChildJvm(Class<?> entry, Map<String, String> env, String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(entry.getName());
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command);
        builder.environment().putAll(env);
        var outFile = Files.createTempFile("vigil-child-out", ".txt");
        var errFile = Files.createTempFile("vigil-child-err", ".txt");
        try {
            builder.redirectOutput(outFile.toFile()).redirectError(errFile.toFile());
            var process = builder.start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Child JVM timed out. stdout=" + Files.readString(outFile)
                        + " stderr=" + Files.readString(errFile));
            }
            return new Result(process.exitValue(), Files.readString(outFile), Files.readString(errFile));
        } finally {
            Files.deleteIfExists(outFile);
            Files.deleteIfExists(errFile);
        }
    }

    private record Result(int exit, String out, String err) {
    }
}
