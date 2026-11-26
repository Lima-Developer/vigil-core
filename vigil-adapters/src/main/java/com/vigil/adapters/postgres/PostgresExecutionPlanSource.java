package com.vigil.adapters.postgres;

import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.exception.PlanCollectionRejected;
import com.vigil.application.exception.PlanRejectionReason;
import com.vigil.application.exception.TargetConnectionFailure;
import com.vigil.application.port.out.interfaces.ExecutionPlanSource;
import com.vigil.core.domain.PlanSummaryEvidencePayload;
import com.vigil.adapters.postgres.schema.Pseudonymizer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class PostgresExecutionPlanSource implements ExecutionPlanSource {
    static final String LOCAL_TIMEOUT_SQL = "SET LOCAL statement_timeout = '2s'";
    static final int QUERY_TIMEOUT_SECONDS = 2;
    static final String EXPLAIN_PREFIX = "EXPLAIN (FORMAT JSON) ";
    private final Supplier<Connection> connections;
    private final Pseudonymizer pseudonyms;

    PostgresExecutionPlanSource(Supplier<Connection> connections) {
        this(connections, randomPseudonymizer());
    }

    PostgresExecutionPlanSource(Supplier<Connection> connections, Pseudonymizer pseudonyms) {
        this.connections = Objects.requireNonNull(connections);
        this.pseudonyms = Objects.requireNonNull(pseudonyms);
    }

    public static ExecutionPlanSource forTarget(TargetProfile profile, String user, Path sslRootCert,
                                                Map<String, String> environment) {
        Objects.requireNonNull(environment, "Environment não pode ser nulo");
        var settings = PostgresTargetSettings.withDefaults(profile, user, sslRootCert);
        var factory = new PostgresConnectionFactory(settings, PostgresPasswordSource.fromEnvironment(environment));
        return new PostgresExecutionPlanSource(factory::open);
    }

    @Override
    public PlanSummaryEvidencePayload collect(String selectText) {
        String validated = PostgresSelectStatementGuard.validate(selectText);
        Connection connection = null;
        Statement statement = null;
        ResultSet result = null;
        PlanSummaryEvidencePayload plan = null;
        RuntimeException rejection = null;
        try {
            connection = connections.get();
            connection.setAutoCommit(false);
            statement = connection.createStatement();
            statement.setEscapeProcessing(false);
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.execute(LOCAL_TIMEOUT_SQL);
            result = statement.executeQuery(EXPLAIN_PREFIX + validated);
            if (!result.next()) throw new PlanCollectionRejected(PlanRejectionReason.PLAN_UNREADABLE);
            plan = PostgresExplainPlanParser.parse(result.getString(1), pseudonyms);
        } catch (PlanCollectionRejected failure) {
            rejection = failure;
        } catch (TargetConnectionFailure failure) {
            rejection = new TargetConnectionFailure(failure.reason(), failure.targetName(),
                    failure.sqlState().orElse(null), null);
        } catch (SQLException failure) {
            rejection = sqlFailure(failure);
        } finally {
            rejection = close(result, rejection);
            rejection = close(statement, rejection);
            if (connection != null) {
                try {
                    connection.rollback();
                } catch (SQLException | RuntimeException failure) {
                    rejection = firstFailure(rejection);
                }
            }
            rejection = close(connection, rejection);
        }
        if (rejection != null) throw rejection;
        return plan;
    }

    private static Pseudonymizer randomPseudonymizer() {
        byte[] salt = new byte[32];
        new java.security.SecureRandom().nextBytes(salt);
        return new Pseudonymizer(salt);
    }

    private static PlanCollectionRejected sqlFailure(SQLException failure) {
        if ("57014".equals(failure.getSQLState()) || "55P03".equals(failure.getSQLState()))
            return new PlanCollectionRejected(PlanRejectionReason.TIMEOUT);
        return new PlanCollectionRejected(PlanRejectionReason.TARGET_UNAVAILABLE);
    }

    private static RuntimeException firstFailure(RuntimeException rejection) {
        return rejection == null ? new PlanCollectionRejected(PlanRejectionReason.TARGET_UNAVAILABLE) : rejection;
    }

    private static RuntimeException close(AutoCloseable resource, RuntimeException rejection) {
        if (resource == null) return rejection;
        try {
            resource.close();
        } catch (Exception failure) {
            return firstFailure(rejection);
        }
        return rejection;
    }
}
