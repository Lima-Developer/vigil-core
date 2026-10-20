package com.vigil.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vigil.adapters.postgres.PostgresTestSupport.FakeConnection;
import com.vigil.application.exception.TargetConnectionFailure;
import com.vigil.application.exception.TargetFailureReason;
import java.net.ConnectException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PostgresConnectionFactoryTest {

    private final String password = PostgresTestSupport.canary();
    private final List<Duration> sleeps = new ArrayList<>();
    private final List<String> urls = new ArrayList<>();
    private final List<Properties> snapshots = new ArrayList<>();
    private char[] suppliedPassword;

    private PostgresConnectionFactory factory(
            PostgresTargetSettings settings, PostgresConnectionFactory.ConnectionOpener opener) {
        return new PostgresConnectionFactory(settings, () -> {
            suppliedPassword = password.toCharArray();
            return suppliedPassword;
        }, (url, properties) -> {
            urls.add(url);
            var copy = new Properties();
            copy.putAll(properties);
            snapshots.add(copy);
            return opener.open(url, properties);
        }, sleeps::add);
    }

    private static PostgresConnectionFactory.ConnectionOpener failingWith(SQLException failure) {
        return (url, properties) -> {
            throw failure;
        };
    }

    @Test
    void shouldOpenAReadOnlyAutoCommitConnectionAfterVerifyingTheSession() {
        var fake = new FakeConnection();
        var factory = factory(PostgresTestSupport.settings(), (url, properties) -> fake.connection());

        var connection = factory.open();

        assertNotNull(connection);
        assertFalse(fake.closed);
        assertEquals(List.of(
                "setAutoCommit=true", "setReadOnly=true",
                "SHOW default_transaction_read_only", "SHOW statement_timeout",
                "SHOW lock_timeout", "SHOW application_name"), fake.calls);
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void shouldBuildTheDriverPropertiesFromTheSettings() {
        var fake = new FakeConnection();
        var root = Path.of("trust", "ca.crt");
        var settings = PostgresTargetSettings.withDefaults(PostgresTestSupport.profile(), "vigil_collector", root);

        factory(settings, (url, properties) -> fake.connection()).open();

        var properties = snapshots.get(0);
        assertEquals("jdbc:postgresql://localhost:5432/vigil_lab", urls.get(0));
        assertEquals("vigil_collector", properties.getProperty("user"));
        assertEquals(password, properties.getProperty("password"));
        assertEquals("verify-full", properties.getProperty("sslmode"));
        assertEquals(root.toString(), properties.getProperty("sslrootcert"));
        assertEquals("5", properties.getProperty("connectTimeout"));
        assertEquals("10", properties.getProperty("socketTimeout"));
        assertEquals("vigil", properties.getProperty("ApplicationName"));
        assertEquals("-c statement_timeout=5000 -c lock_timeout=200 -c default_transaction_read_only=on",
                properties.getProperty("options"));
        assertEquals(8, properties.size());
    }

    @Test
    void shouldOmitTheRootCertificateWhenNoneIsConfigured() {
        var fake = new FakeConnection();

        factory(PostgresTestSupport.settings(), (url, properties) -> fake.connection()).open();

        assertNull(snapshots.get(0).getProperty("sslrootcert"));
        assertEquals(7, snapshots.get(0).size());
    }

    @Test
    void shouldZeroThePasswordAndClearThePropertiesAfterOpening() {
        var fake = new FakeConnection();
        var seen = new AtomicReference<Properties>();

        factory(PostgresTestSupport.settings(), (url, properties) -> {
            seen.set(properties);
            return fake.connection();
        }).open();

        assertArrayEquals(new char[password.length()], suppliedPassword);
        assertTrue(seen.get().isEmpty());
    }

    @Test
    void shouldZeroThePasswordAndClearThePropertiesWhenTheOpenerFails() {
        var seen = new AtomicReference<Properties>();

        var factory = factory(PostgresTestSupport.settings(), (url, properties) -> {
            seen.set(properties);
            throw new SQLException("no", "28P01");
        });
        assertThrows(TargetConnectionFailure.class, factory::open);

        assertArrayEquals(new char[password.length()], suppliedPassword);
        assertTrue(seen.get().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "default_transaction_read_only, off",
            "statement_timeout, 5000",
            "statement_timeout, 0",
            "lock_timeout, 0",
            "lock_timeout, 200",
            "application_name, other",
            "application_name, ''"
    })
    void shouldCloseTheConnectionAndFailAsUnknownWhenASettingDiverges(String setting, String actual) {
        var fake = new FakeConnection();
        fake.settings.put(setting, actual);
        var factory = factory(PostgresTestSupport.settings(), (url, properties) -> fake.connection());

        var failure = assertThrows(TargetConnectionFailure.class, factory::open);

        assertEquals(TargetFailureReason.UNKNOWN, failure.reason());
        assertTrue(fake.closed);
        assertTrue(sleeps.isEmpty());
        assertEquals(1, urls.size());
        assertTrue(failure.getCause().getMessage().contains(setting));
    }

    @Test
    void shouldFailAsUnknownWhenAShowReturnsNoRow() {
        var fake = new FakeConnection();
        fake.settings.put("lock_timeout", null);
        var factory = factory(PostgresTestSupport.settings(), (url, properties) -> fake.connection());

        var failure = assertThrows(TargetConnectionFailure.class, factory::open);

        assertEquals(TargetFailureReason.UNKNOWN, failure.reason());
        assertTrue(fake.closed);
    }

    @Test
    void shouldCloseTheConnectionAndPropagateAnUnexpectedRuntimeFailureDuringConfiguration() {
        var fake = new FakeConnection();
        fake.settings.remove("statement_timeout");
        var factory = factory(PostgresTestSupport.settings(), (url, properties) -> fake.connection());

        assertThrows(RuntimeException.class, factory::open);

        assertTrue(fake.closed);
    }

    @Test
    void shouldRetryUnreachableTargetsTwiceWithBackoffThenFail() {
        var failure = new SQLException("refused", "08001", new ConnectException("refused"));
        var factory = factory(PostgresTestSupport.settings(), failingWith(failure));

        var thrown = assertThrows(TargetConnectionFailure.class, factory::open);

        assertEquals(TargetFailureReason.UNREACHABLE, thrown.reason());
        assertEquals(3, urls.size());
        assertEquals(List.of(Duration.ofMillis(200), Duration.ofMillis(800)), sleeps);
        assertEquals("08001", thrown.sqlState().orElseThrow());
        assertSame(failure, thrown.getCause());
        assertEquals("lab", thrown.targetName());
    }

    @Test
    void shouldReturnTheConnectionWhenARetrySucceeds() {
        var fake = new FakeConnection();
        var attempts = new int[1];
        var factory = factory(PostgresTestSupport.settings(), (url, properties) -> {
            if (++attempts[0] < 3) {
                throw new SQLException("starting", "57P03");
            }
            return fake.connection();
        });

        assertNotNull(factory.open());

        assertEquals(3, urls.size());
        assertEquals(List.of(Duration.ofMillis(200), Duration.ofMillis(800)), sleeps);
    }

    @Test
    void shouldReadThePasswordOncePerAttempt() {
        var reads = new int[1];
        var factory = new PostgresConnectionFactory(PostgresTestSupport.settings(), () -> {
            reads[0]++;
            return "pw".toCharArray();
        }, failingWith(new SQLException("down", "08006")), sleeps::add);

        assertThrows(TargetConnectionFailure.class, factory::open);

        assertEquals(3, reads[0]);
    }

    @ParameterizedTest
    @CsvSource({
            "28P01, AUTHENTICATION_FAILED", "28000, AUTHENTICATION_FAILED",
            "42501, AUTHORIZATION_DENIED", "57014, TIMEOUT", "55P03, TIMEOUT", "XX000, UNKNOWN"
    })
    void shouldNotRetryNonRetryableFailures(String sqlState, TargetFailureReason reason) {
        var factory = factory(PostgresTestSupport.settings(), failingWith(new SQLException("no", sqlState)));

        var thrown = assertThrows(TargetConnectionFailure.class, factory::open);

        assertEquals(reason, thrown.reason());
        assertEquals(1, urls.size());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void shouldNotRetryTlsFailures() {
        var failure = new SQLException("tls", "08001", new SSLHandshakeException("bad cert"));
        var factory = factory(PostgresTestSupport.settings(), failingWith(failure));

        var thrown = assertThrows(TargetConnectionFailure.class, factory::open);

        assertEquals(TargetFailureReason.TLS_FAILED, thrown.reason());
        assertEquals(1, urls.size());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void shouldReportAFailureWithoutSqlStateAndKeepThePasswordOutOfTheMessage() {
        var canary = PostgresTestSupport.canary();
        var factory = new PostgresConnectionFactory(PostgresTestSupport.settings(), canary::toCharArray,
                failingWith(new SQLException("driver says " + "x")), sleeps::add);

        var thrown = assertThrows(TargetConnectionFailure.class, factory::open);

        assertTrue(thrown.sqlState().isEmpty());
        assertFalse(thrown.getMessage().contains(canary));
        assertFalse(thrown.toString().contains(canary));
        assertFalse(thrown.getMessage().contains("jdbc:"));
        assertFalse(thrown.getMessage().contains("localhost"));
    }

    @Test
    void shouldStopRetryingAndFlagTheInterruptWhenTheBackoffIsInterrupted() {
        var factory = new PostgresConnectionFactory(PostgresTestSupport.settings(), () -> "pw".toCharArray(),
                failingWith(new SQLException("down", "08006")), duration -> {
                    throw new InterruptedException();
                });

        try {
            var thrown = assertThrows(TargetConnectionFailure.class, factory::open);

            assertEquals(TargetFailureReason.UNKNOWN, thrown.reason());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, thrown.getCause().getSuppressed().length);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldCloseSuppressingTheCloseFailureWhenConfigurationFails() {
        var fake = new FakeConnection();
        fake.settings.put("application_name", "other");
        var closeFailure = new SQLException("close failed");
        Connection delegate = fake.connection();
        Connection connection = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        throw closeFailure;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        var factory = factory(PostgresTestSupport.settings(), (url, properties) -> connection);

        var thrown = assertThrows(TargetConnectionFailure.class, factory::open);

        assertEquals(List.of(closeFailure), List.of(thrown.getCause().getSuppressed()));
    }
}
