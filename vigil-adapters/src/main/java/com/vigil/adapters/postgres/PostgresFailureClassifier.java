package com.vigil.adapters.postgres;

import com.vigil.application.exception.TargetFailureReason;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLException;

/** Pure mapping from a driver failure to a Vigil failure reason; only connection-level failures are retryable. */
final class PostgresFailureClassifier {

    record Classification(TargetFailureReason reason, boolean retryable) {
        static final Classification UNKNOWN = new Classification(TargetFailureReason.UNKNOWN, false);
        private static final Classification UNREACHABLE = new Classification(TargetFailureReason.UNREACHABLE, true);
        private static final Classification TLS_FAILED = new Classification(TargetFailureReason.TLS_FAILED, false);
    }

    private PostgresFailureClassifier() {
    }

    static Classification classify(SQLException failure) {
        var chain = chainOf(failure);
        for (Throwable link : chain) {
            if (link instanceof SSLException || isTlsMessage(link.getMessage())) {
                return Classification.TLS_FAILED;
            }
        }
        for (Throwable link : chain) {
            if (isNetworkFailure(link)) {
                return Classification.UNREACHABLE;
            }
            if (link instanceof SQLException sql && sql.getSQLState() != null) {
                var byState = bySqlState(sql.getSQLState());
                if (byState != null) {
                    return byState;
                }
            }
        }
        return Classification.UNKNOWN;
    }

    /**
     * pgjdbc reports a hostname mismatch and a server without TLS as a plain {@code PSQLException}, with no
     * {@link SSLException} cause. Since only verified TLS modes exist, those two messages are TLS failures; with a
     * localized driver message the failure degrades to the bounded UNREACHABLE retry instead.
     */
    private static boolean isTlsMessage(String message) {
        return message != null
                && (message.contains("could not be verified by hostnameverifier")
                || message.contains("The server does not support SSL"));
    }

    private static boolean isNetworkFailure(Throwable link) {
        return link instanceof SocketTimeoutException
                || link instanceof ConnectException
                || link instanceof NoRouteToHostException
                || link instanceof UnknownHostException;
    }

    private static Classification bySqlState(String sqlState) {
        return switch (sqlState) {
            case "57P01", "57P03" -> Classification.UNREACHABLE;
            case "28P01", "28000" -> new Classification(TargetFailureReason.AUTHENTICATION_FAILED, false);
            case "42501" -> new Classification(TargetFailureReason.AUTHORIZATION_DENIED, false);
            case "57014", "55P03" -> new Classification(TargetFailureReason.TIMEOUT, false);
            default -> sqlState.startsWith("08") ? Classification.UNREACHABLE : null;
        };
    }

    /** Causes and chained SQL exceptions in visiting order, each node once, so a cyclic chain terminates. */
    private static List<Throwable> chainOf(SQLException failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var chain = new ArrayList<Throwable>();
        collect(failure, seen, chain);
        return chain;
    }

    private static void collect(Throwable node, Set<Throwable> seen, List<Throwable> chain) {
        var current = node;
        while (current != null && seen.add(current)) {
            chain.add(current);
            if (current instanceof SQLException sql) {
                collect(sql.getNextException(), seen, chain);
            }
            current = current.getCause();
        }
    }
}
