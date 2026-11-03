package com.vigil.adapters.postgres;

import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.exception.PlanCollectionRejected;
import com.vigil.application.exception.PlanRejectionReason;
import com.vigil.application.exception.TargetConnectionFailure;
import com.vigil.application.port.out.interfaces.ExecutionPlanSource;
import com.vigil.core.domain.PlanSummaryEvidencePayload;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class PostgresExecutionPlanSource implements ExecutionPlanSource {
    static final String LOCAL_TIMEOUT_SQL = "SET LOCAL statement_timeout = '2s'";
    static final int QUERY_TIMEOUT_SECONDS = 2;
    static final String EXPLAIN_PREFIX = "EXPLAIN (FORMAT JSON) ";
    private final Supplier<Connection> connections;

    PostgresExecutionPlanSource(Supplier<Connection> connections) {
        this.connections = Objects.requireNonNull(connections);
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
        try (Connection connection = connections.get()) {
            try {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.setEscapeProcessing(false);
                    statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    statement.execute(LOCAL_TIMEOUT_SQL);
                    try (var result = statement.executeQuery(EXPLAIN_PREFIX + validated)) {
                        if (!result.next()) throw new PlanCollectionRejected(PlanRejectionReason.PLAN_UNREADABLE);
                        return PostgresExplainPlanParser.parse(result.getString(1));
                    }
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            if ("57014".equals(failure.getSQLState()) || "55P03".equals(failure.getSQLState()))
                throw new PlanCollectionRejected(PlanRejectionReason.TIMEOUT);
            throw new PlanCollectionRejected(PlanRejectionReason.TARGET_UNAVAILABLE);
        } catch (TargetConnectionFailure failure) {
            throw failure;
        }
    }
}
