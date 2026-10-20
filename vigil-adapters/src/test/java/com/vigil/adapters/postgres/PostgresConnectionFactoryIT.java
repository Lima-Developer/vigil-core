package com.vigil.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.adapters.postgres.lab.PostgresLaboratory;
import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.configuration.TargetSslMode;
import com.vigil.application.exception.TargetConnectionFailure;
import com.vigil.application.exception.TargetFailureReason;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Runs the factory against the real laboratory over verified TLS. The CA and server certificate are generated inside
 * the container at startup, so no key material is committed; only the public CA certificate is copied out.
 */
@Testcontainers
class PostgresConnectionFactoryIT {

    private static final String TLS_DIRECTORY = "/var/lib/vigil-tls";
    private static final String TLS_BOOTSTRAP = String.join("\n",
            "set -e",
            "mkdir -p " + TLS_DIRECTORY,
            "cd " + TLS_DIRECTORY,
            "openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.crt -subj /CN=vigil-test-ca -days 2",
            "openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj /CN=localhost",
            "echo subjectAltName=DNS:localhost > ext.cnf",
            "openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out server.crt -days 2"
                    + " -extfile ext.cnf",
            "chown postgres:postgres server.key server.crt",
            "chmod 600 server.key",
            "exec docker-entrypoint.sh \"$@\" -c ssl=on -c ssl_cert_file=" + TLS_DIRECTORY + "/server.crt"
                    + " -c ssl_key_file=" + TLS_DIRECTORY + "/server.key");
    private static final String ROLE_SCRIPT = "/tmp/vigil-lab-role.sql";
    private static final String USER = "vigil_collector";
    private static final String TLS_HOST = "localhost";

    @Container
    static final PostgreSQLContainer<?> CONTAINER = PostgresLaboratory.newContainer()
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "-c", TLS_BOOTSTRAP, "sh"));

    private static final String PASSWORD = "pw-" + UUID.randomUUID();

    private static Path workDirectory;
    private static Path caCertificate;

    @BeforeAll
    static void prepareLaboratory() throws Exception {
        PostgresLaboratory.initializeStandard(CONTAINER);
        workDirectory = Files.createTempDirectory("vigil-it");
        caCertificate = workDirectory.resolve("ca.crt");
        CONTAINER.copyFileFromContainer(TLS_DIRECTORY + "/ca.crt", caCertificate.toString());
        createRole();
        try (Connection admin = admin(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE TABLE su_owned (id int)");
            statement.execute("CREATE TABLE lock_probe (id int)");
            statement.execute("GRANT SELECT ON lock_probe TO " + USER);
            statement.execute("CREATE DATABASE locked_db");
            statement.execute("REVOKE CONNECT ON DATABASE locked_db FROM PUBLIC");
        }
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (workDirectory != null) {
            try (var files = Files.walk(workDirectory)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static void createRole() throws Exception {
        CONTAINER.copyFileToContainer(MountableFile.forClasspathResource("postgres/vigil-lab-role.sql"), ROLE_SCRIPT);
        var result = CONTAINER.execInContainer("psql", "-U", CONTAINER.getUsername(), "-d", CONTAINER.getDatabaseName(),
                "-v", "ON_ERROR_STOP=1", "-v", "vigil_password=" + PASSWORD, "-f", ROLE_SCRIPT);
        assertEquals(0, result.getExitCode(), "Role script failed with exit code " + result.getExitCode());
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    private static PostgresConnectionFactory factory(String host, int port, String database, String password,
                                                     TargetSslMode sslMode) {
        return factory(host, port, database, password, sslMode, DriverManager::getConnection, duration -> { });
    }

    private static PostgresConnectionFactory factory(
            String host, int port, String database, String password, TargetSslMode sslMode,
            PostgresConnectionFactory.ConnectionOpener opener, PostgresConnectionFactory.Sleeper sleeper) {
        var profile = new TargetProfile("lab", "development", host, port, database, sslMode, Duration.ofSeconds(5));
        var settings = PostgresTargetSettings.withDefaults(profile, USER, caCertificate);
        return new PostgresConnectionFactory(settings, password::toCharArray, opener, sleeper);
    }

    private static PostgresConnectionFactory factory() {
        return factory(TLS_HOST, CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                CONTAINER.getDatabaseName(), PASSWORD, TargetSslMode.VERIFY_FULL);
    }

    private static String single(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static SQLException failureOf(Connection connection, String sql) {
        return assertThrows(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        });
    }

    @Test
    void shouldOpenAVerifiedTlsSessionThatIsReadOnlyAndTimeLimited() throws SQLException {
        try (Connection connection = factory().open()) {
            assertEquals("on", single(connection, "SHOW default_transaction_read_only"));
            assertEquals("5s", single(connection, "SHOW statement_timeout"));
            assertEquals("200ms", single(connection, "SHOW lock_timeout"));
            assertEquals("vigil", single(connection, "SHOW application_name"));
            assertEquals("true", single(connection, "SELECT ssl::text FROM pg_stat_ssl WHERE pid = pg_backend_pid()"));
        }
    }

    @Test
    void shouldRunWithARoleWithoutAdministrativePrivileges() throws SQLException {
        try (Connection connection = factory().open()) {
            assertEquals("false", single(connection,
                    "SELECT (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls OR rolinherit)::text"
                            + " FROM pg_roles WHERE rolname = current_user"));
        }
    }

    @Test
    void shouldReadTelemetryStatistics() throws SQLException {
        try (Connection connection = factory().open()) {
            assertTrue(Long.parseLong(single(connection, "SELECT count(*) FROM pg_stat_database")) > 0);
            assertTrue(Long.parseLong(single(connection,
                    "SELECT count(*) FROM pg_stat_user_tables WHERE relname = 'orders'")) == 1);
            assertTrue(Long.parseLong(single(connection,
                    "SELECT count(*) FROM pg_stat_statements WHERE query LIKE '%orders%'")) > 0);
        }
    }

    @Test
    void shouldHideColumnStatisticsOfTablesTheRoleCannotRead() throws SQLException {
        try (Connection connection = factory().open()) {
            assertEquals("0", single(connection, "SELECT count(*) FROM pg_stats WHERE tablename = 'orders'"));
            assertEquals("42501", failureOf(connection, "SELECT count(*) FROM orders").getSQLState());
        }
    }

    @Test
    void shouldRejectWritesBecauseTheSessionIsReadOnly() throws SQLException {
        try (Connection connection = factory().open()) {
            assertEquals("25006", failureOf(connection, "CREATE TABLE vigil_created (id int)").getSQLState());
            assertEquals("25006", failureOf(connection, "INSERT INTO su_owned VALUES (1)").getSQLState());
        }
    }

    @Test
    void shouldRejectWritesByPrivilegeEvenWhenTheSessionIsMadeWritable() throws SQLException {
        try (Connection connection = factory().open()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET default_transaction_read_only = off");
            }
            var create = failureOf(connection, "CREATE TABLE vigil_created (id int)");
            var insert = failureOf(connection, "INSERT INTO su_owned VALUES (1)");

            assertEquals("42501", create.getSQLState());
            assertEquals("42501", insert.getSQLState());
            assertEquals(TargetFailureReason.AUTHORIZATION_DENIED,
                    PostgresFailureClassifier.classify(insert).reason());
        }
    }

    @Test
    void shouldInterruptALongStatementAfterTheStatementTimeout() throws SQLException {
        try (Connection connection = factory().open()) {
            var started = System.nanoTime();
            var failure = failureOf(connection, "SELECT pg_sleep(10)");
            var elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertEquals("57014", failure.getSQLState());
            assertTrue(elapsed.compareTo(Duration.ofSeconds(4)) > 0, "interrupted too early: " + elapsed);
            assertTrue(elapsed.compareTo(Duration.ofSeconds(8)) < 0, "interrupted too late: " + elapsed);
            var classification = PostgresFailureClassifier.classify(failure);
            assertEquals(TargetFailureReason.TIMEOUT, classification.reason());
            assertFalse(classification.retryable());
        }
    }

    @Test
    void shouldGiveUpOnALockAfterTheLockTimeout() throws SQLException {
        try (Connection admin = admin(); Statement statement = admin.createStatement()) {
            admin.setAutoCommit(false);
            statement.execute("LOCK TABLE lock_probe IN ACCESS EXCLUSIVE MODE");
            try (Connection connection = factory().open()) {
                var started = System.nanoTime();
                var failure = failureOf(connection, "SELECT count(*) FROM lock_probe");
                var elapsed = Duration.ofNanos(System.nanoTime() - started);

                assertEquals("55P03", failure.getSQLState());
                assertTrue(elapsed.compareTo(Duration.ofSeconds(3)) < 0, "waited too long: " + elapsed);
                var classification = PostgresFailureClassifier.classify(failure);
                assertEquals(TargetFailureReason.TIMEOUT, classification.reason());
                assertFalse(classification.retryable());
            } finally {
                admin.rollback();
            }
        }
    }

    @Test
    void shouldFailAuthenticationWithoutRetryAndWithoutLeakingThePassword() {
        var wrong = "wrong-" + UUID.randomUUID();
        var sleeps = new ArrayList<Duration>();
        var factory = factory(TLS_HOST, CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                CONTAINER.getDatabaseName(), wrong, TargetSslMode.VERIFY_FULL, DriverManager::getConnection,
                sleeps::add);

        var failure = assertThrows(TargetConnectionFailure.class, factory::open);

        assertEquals(TargetFailureReason.AUTHENTICATION_FAILED, failure.reason());
        assertEquals("28P01", failure.sqlState().orElseThrow());
        assertTrue(sleeps.isEmpty());
        assertFalse(failure.getMessage().contains(wrong));
        assertFalse(failure.toString().contains(wrong));
    }

    @Test
    void shouldReportMissingConnectPrivilegeAsAuthorizationDenied() {
        var factory = factory(TLS_HOST, CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                "locked_db", PASSWORD, TargetSslMode.VERIFY_FULL);

        var failure = assertThrows(TargetConnectionFailure.class, factory::open);

        assertEquals(TargetFailureReason.AUTHORIZATION_DENIED, failure.reason());
        assertEquals("42501", failure.sqlState().orElseThrow());
    }

    @Test
    void shouldRetryTwiceThenReportUnreachableWhenThePortIsClosed() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var attempts = new AtomicInteger();
        var sleeps = new ArrayList<Duration>();
        var factory = factory(TLS_HOST, closedPort, CONTAINER.getDatabaseName(), PASSWORD, TargetSslMode.VERIFY_FULL,
                (url, properties) -> {
                    attempts.incrementAndGet();
                    return DriverManager.getConnection(url, properties);
                }, sleeps::add);

        var failure = assertThrows(TargetConnectionFailure.class, factory::open);

        assertEquals(TargetFailureReason.UNREACHABLE, failure.reason());
        assertEquals(3, attempts.get());
        assertEquals(List.of(Duration.ofMillis(200), Duration.ofMillis(800)), sleeps);
    }

    @Test
    void shouldFailTlsVerificationWithoutRetryWhenTheHostnameDoesNotMatchTheCertificate() {
        var sleeps = new ArrayList<Duration>();
        var factory = factory("127.0.0.1", CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                CONTAINER.getDatabaseName(), PASSWORD, TargetSslMode.VERIFY_FULL, DriverManager::getConnection,
                sleeps::add);

        var failure = assertThrows(TargetConnectionFailure.class, factory::open);

        assertEquals(TargetFailureReason.TLS_FAILED, failure.reason());
        assertTrue(sleeps.isEmpty());
    }
}
