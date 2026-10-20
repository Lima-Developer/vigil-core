package com.vigil.adapters.postgres;

import static com.vigil.application.exception.TargetFailureReason.AUTHENTICATION_FAILED;
import static com.vigil.application.exception.TargetFailureReason.AUTHORIZATION_DENIED;
import static com.vigil.application.exception.TargetFailureReason.TIMEOUT;
import static com.vigil.application.exception.TargetFailureReason.TLS_FAILED;
import static com.vigil.application.exception.TargetFailureReason.UNKNOWN;
import static com.vigil.application.exception.TargetFailureReason.UNREACHABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vigil.application.exception.TargetFailureReason;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PostgresFailureClassifierTest {

    @ParameterizedTest
    @CsvSource({
            "08000, UNREACHABLE, true", "08001, UNREACHABLE, true", "08006, UNREACHABLE, true",
            "08P01, UNREACHABLE, true", "57P01, UNREACHABLE, true", "57P03, UNREACHABLE, true",
            "28P01, AUTHENTICATION_FAILED, false", "28000, AUTHENTICATION_FAILED, false",
            "42501, AUTHORIZATION_DENIED, false",
            "57014, TIMEOUT, false", "55P03, TIMEOUT, false",
            "25006, UNKNOWN, false", "42P01, UNKNOWN, false", "57P02, UNKNOWN, false", "28001, UNKNOWN, false",
            "07000, UNKNOWN, false", "'', UNKNOWN, false"
    })
    void shouldClassifyBySqlState(String sqlState, TargetFailureReason reason, boolean retryable) {
        var classification = PostgresFailureClassifier.classify(new SQLException("boom", sqlState));

        assertEquals(reason, classification.reason());
        assertEquals(retryable, classification.retryable());
    }

    @ParameterizedTest
    @NullSource
    void shouldClassifyAMissingSqlStateAsUnknown(String sqlState) {
        var classification = PostgresFailureClassifier.classify(new SQLException("boom", sqlState));

        assertEquals(UNKNOWN, classification.reason());
        assertEquals(false, classification.retryable());
    }

    @Test
    void shouldClassifyANullFailureAsUnknown() {
        assertEquals(UNKNOWN, PostgresFailureClassifier.classify(null).reason());
    }

    @ParameterizedTest
    @ValueSource(classes = {
            SocketTimeoutException.class, ConnectException.class, NoRouteToHostException.class,
            UnknownHostException.class})
    void shouldTreatNetworkFailuresAnywhereInTheChainAsRetryableUnreachable(Class<? extends Exception> network)
            throws ReflectiveOperationException {
        var cause = network.getConstructor(String.class).newInstance("x");
        var failure = new SQLException("outer", "XX000", new RuntimeException("middle", cause));

        var classification = PostgresFailureClassifier.classify(failure);

        assertEquals(UNREACHABLE, classification.reason());
        assertEquals(true, classification.retryable());
    }

    @Test
    void shouldPreferTlsFailureOverConnectionSqlState() {
        var failure = new SQLException("tls", "08001", new SSLHandshakeException("bad cert"));

        var classification = PostgresFailureClassifier.classify(failure);

        assertEquals(TLS_FAILED, classification.reason());
        assertEquals(false, classification.retryable());
    }

    @Test
    void shouldPreferTlsFailureOverANetworkFailureEarlierInTheChain() {
        var failure = new SQLException("outer", null, new ConnectException("x"));
        var tls = new SQLException("tls", "08001", new SSLHandshakeException("bad cert"));
        failure.setNextException(tls);

        assertEquals(TLS_FAILED, PostgresFailureClassifier.classify(failure).reason());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "The hostname 127.0.0.1 could not be verified by hostnameverifier PgjdbcHostnameVerifier.|08006",
            "The server does not support SSL.|08004",
            "Could not open SSL root certificate file /home/u/.postgresql/root.crt.|08006",
            "Could not read SSL root certificate file /home/u/.postgresql/root.crt.|08006"
    })
    void shouldRecognizeDriverTlsFailuresReportedWithoutAnSslCause(String message, String sqlState) {
        var classification = PostgresFailureClassifier.classify(new SQLException(message, sqlState));

        assertEquals(TLS_FAILED, classification.reason());
        assertEquals(false, classification.retryable());
    }

    @Test
    void shouldNotMistakeUnrelatedMessagesForTlsFailures() {
        var classification = PostgresFailureClassifier.classify(new SQLException("Connection refused", "08001"));

        assertEquals(UNREACHABLE, classification.reason());
    }

    @Test
    void shouldFindTheSqlStateOfADeepCause() {
        var failure = new SQLException("wrapper", "XX000", new SQLException("inner", "28P01"));

        assertEquals(AUTHENTICATION_FAILED, PostgresFailureClassifier.classify(failure).reason());
    }

    @Test
    void shouldFindTheSqlStateOfAChainedException() {
        var failure = new SQLException("first", "XX000");
        failure.setNextException(new SQLException("second", "42501"));

        assertEquals(AUTHORIZATION_DENIED, PostgresFailureClassifier.classify(failure).reason());
    }

    @Test
    void shouldFindANetworkFailureBehindAChainedException() {
        var failure = new SQLException("first", "XX000");
        failure.setNextException(new SQLException("second", "XX000", new SocketTimeoutException("slow")));

        assertEquals(UNREACHABLE, PostgresFailureClassifier.classify(failure).reason());
    }

    @Test
    void shouldUseTheFirstMatchInChainOrder() {
        var failure = new SQLException("outer", "57014", new SQLException("inner", "28P01"));

        assertEquals(TIMEOUT, PostgresFailureClassifier.classify(failure).reason());
    }

    @Test
    void shouldTerminateOnACyclicCauseChain() {
        var a = new SQLException("a", "XX000");
        var b = new SQLException("b", "XX000", a);
        a.initCause(b);

        assertEquals(UNKNOWN, PostgresFailureClassifier.classify(a).reason());
    }

    @Test
    void shouldTerminateOnACyclicNextExceptionChain() {
        var a = new SQLException("a", "XX000");
        var b = new SQLException("b", "XX000");
        a.setNextException(b);
        b.setNextException(a);

        assertEquals(UNKNOWN, PostgresFailureClassifier.classify(a).reason());
    }

    @Test
    void shouldStillFindMatchesAfterAShortCycle() {
        var a = new SQLException("a", "XX000");
        var b = new SQLException("b", "55P03", a);
        a.initCause(b);

        assertEquals(TIMEOUT, PostgresFailureClassifier.classify(a).reason());
    }
}
