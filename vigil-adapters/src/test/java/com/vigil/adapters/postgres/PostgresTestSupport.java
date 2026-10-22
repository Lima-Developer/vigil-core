package com.vigil.adapters.postgres;

import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.configuration.TargetSslMode;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PostgresTestSupport {
    private PostgresTestSupport() {
    }

    static TargetProfile profile() {
        return new TargetProfile("lab", "development", "localhost", 5432, "vigil_lab",
                TargetSslMode.VERIFY_FULL, Duration.ofSeconds(5));
    }

    static PostgresTargetSettings settings() {
        return PostgresTargetSettings.withDefaults(profile(), "vigil_collector", null);
    }

    static String canary() {
        return "canary-" + UUID.randomUUID();
    }

    /** A connection double that answers {@code SHOW <name>} from a map and records what the factory did to it. */
    static final class FakeConnection {
        final Map<String, String> settings = new LinkedHashMap<>(Map.of(
                "default_transaction_read_only", "on",
                "statement_timeout", "5s",
                "lock_timeout", "200ms",
                "application_name", "vigil"));
        final List<String> calls = new ArrayList<>();
        boolean closed;

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, connectionHandler());
        }

        private InvocationHandler connectionHandler() {
            return (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setAutoCommit", "setReadOnly" -> calls.add(method.getName() + "=" + args[0]);
                    case "close" -> closed = true;
                    case "createStatement" -> {
                        return statement();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
                return null;
            };
        }

        private Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "executeQuery" -> {
                                var sql = (String) args[0];
                                calls.add(sql);
                                return resultSet(settings.get(sql.substring("SHOW ".length())));
                            }
                            case "close" -> {
                                return null;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        private static ResultSet resultSet(String value) {
            var state = new boolean[1];
            return (ResultSet) Proxy.newProxyInstance(FakeConnection.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> {
                            var hasRow = value != null && !state[0];
                            state[0] = true;
                            yield hasRow;
                        }
                        case "getString" -> value;
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
