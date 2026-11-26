package com.vigil.adapters.e2e;

import com.vigil.adapters.fingerprint.HmacQueryFingerprinter;
import com.vigil.adapters.lab.ArtifactHypothesisValidationAdapter;
import com.vigil.adapters.postgres.PostgresExplainPlanParser;
import com.vigil.adapters.postgres.QuerySanitizerAdapter;
import com.vigil.adapters.postgres.lab.PostgresLaboratory;
import com.vigil.adapters.postgres.pgstatstatements.PgStatStatementsImpactAdapter;
import com.vigil.adapters.postgres.preflight.JdbcPostgresCapabilityProbe;
import com.vigil.adapters.postgres.schema.PostgresSchemaCatalogCollector;
import com.vigil.adapters.postgres.schema.Pseudonymizer;
import com.vigil.adapters.postgres.schema.RelationSelector;
import com.vigil.adapters.sqlite.SqliteAnalysisCaseRepository;
import com.vigil.adapters.sqlite.SqliteDatabaseInitializer;
import com.vigil.application.casework.*;
import com.vigil.application.port.out.interfaces.HypothesisValidationPort;
import com.vigil.application.telemetry.QueryTelemetryDeltaCalculator;
import com.vigil.core.domain.*;
import com.vigil.core.domain.enums.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Full synthetic flow: telemetry → human approval, with a read-only monitored-target identity. */
@Testcontainers
class VigilWorkflowE2EIT {
    private static final Instant T1 = Instant.parse("2026-11-26T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-26T12:05:00Z");
    private static final String SQL = "SELECT id FROM orders WHERE status = 'VALUE_THAT_DOES_NOT_EXIST'";
    private static final String READER = "vigil_e2e_reader";
    private static final String READER_PASSWORD = "synthetic-e2e-reader";

    @Container static final PostgreSQLContainer<?> LAB = PostgresLaboratory.newContainer();
    @TempDir Path directory;

    @BeforeAll
    static void initialize() throws SQLException {
        PostgresLaboratory.initializeStandard(LAB);
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + READER + " LOGIN PASSWORD '" + READER_PASSWORD + "'");
            statement.execute("GRANT CONNECT ON DATABASE " + LAB.getDatabaseName() + " TO " + READER);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + READER);
            statement.execute("GRANT SELECT ON orders, customers TO " + READER);
            statement.execute("GRANT pg_monitor TO " + READER);
            statement.execute("ALTER ROLE " + READER + " SET default_transaction_read_only = on");
        }
    }

    @Test
    void traversesTheCompleteWorkflowWithoutWritingToTheMonitoredTarget() throws Exception {
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("SELECT pg_stat_statements_reset()");
        }
        var telemetry = impactAdapter();
        var before = telemetry.collectForImpactSelection(T1);
        runMeasuredReadWorkload();
        var after = telemetry.collectForImpactSelection(T2);
        var selection = new SelectCandidatesByImpactUseCase(new QueryTelemetryDeltaCalculator(),
                new ImpactSelectionPolicy(BigDecimal.ZERO, BigDecimal.ZERO, 1_000)).execute(before, after);
        assertFalse(selection.candidates().isEmpty());
        var candidate = selection.candidates().getFirst();

        var clock = (com.vigil.application.port.out.interfaces.Clock) () -> T2;
        var factory = SqliteDatabaseInitializer.initialize(directory.resolve("vigil-e2e.db"), clock);
        var repository = new SqliteAnalysisCaseRepository(factory);
        var ids = ids(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"));
        var discovery = new DiscoverObservedQueryUseCase(repository, clock, ids);
        var discovered = discovery.execute(candidate, TelemetrySource.PG_STAT_STATEMENTS, T2,
                new ObservationWindow(T1, T2), CommandType.SELECT, "e2e-workflow");
        new TriageAnalysisCaseUseCase(repository, clock, ids).execute(new TriageAnalysisCaseCommand(
                discovered.caseId(), candidate.selectionReason().render(), "e2e"));

        var pseudonyms = new Pseudonymizer(new byte[32]);
        var plan = PostgresExplainPlanParser.parse(explainJson(), pseudonyms);
        var schema = new PostgresSchemaCatalogCollector(VigilWorkflowE2EIT::reader)
                .collect(T2, List.of(new RelationSelector("public", "orders")), pseudonyms);
        var full = schema.relations().getFirst();
        var minimal = new RelationBlueprint(full.relation(), full.schema(), full.type(), full.estimatedRows(),
                full.pages(), List.of(), List.of(), full.indexes());

        var analysisCase = repository.getById(discovered.caseId());
        var planEvidence = evidence(analysisCase.caseId(), UUID.fromString("44444444-4444-4444-4444-444444444444"),
                EvidenceSource.POSTGRESQL_EXPLAIN, plan);
        var catalogPayload = RelationMetadataEvidencePayload.fromBlueprint(minimal);
        var catalogEvidence = evidence(analysisCase.caseId(),
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                EvidenceSource.POSTGRESQL_CATALOG, catalogPayload);
        analysisCase.collectEvidence(planEvidence, "e2e", T2);
        analysisCase.collectEvidence(catalogEvidence, "e2e", T2);
        var safe = assertInstanceOf(SanitizationResult.Safe.class, new QuerySanitizerAdapter().sanitize(SQL));
        analysisCase.sanitize(safe, metadata("safe"));
        var evaluation = new DiagnosticRuleEngine().evaluate(
                new DiagnosticRuleEngine.PlanEvidence(planEvidence.id(), plan),
                List.of(new DiagnosticRuleEngine.CatalogEvidence(catalogEvidence.id(), minimal)),
                Optional.of(candidate.assessment()));
        var finding = evaluation.findings().getFirst();
        assertEquals(DiagnosticRuleCode.SEQ_SCAN_SELECTIVE_FILTER, finding.ruleCode());
        var diagnosis = new Diagnosis(evaluation.findings(), Set.copyOf(finding.evidenceIds()));
        analysisCase.diagnose(diagnosis, metadata("diagnosed"));
        var filterColumns = plan.nodes().stream().filter(PlanNodeEvidence::filterPresent)
                .findFirst().orElseThrow().filterColumns();
        analysisCase.proposeHypothesis(new OptimizationHypothesis(full.relation(), filterColumns, finding),
                metadata("hypothesis"));
        analysisCase.startValidation(metadata("sandbox"));
        repository.save(analysisCase);

        Path artifact = directory.resolve("validation.json");
        Files.writeString(artifact, """
                {"schemaVersion":1,"safety":{"targetDatabaseAccessed":false,"physicalIndexCreated":true,
                "explainAnalyzeUsed":true},"before":{"executionTimeMs":12.0},
                "after":{"executionTimeMs":2.0,"indexName":"synthetic_status_idx"}}
                """);
        HypothesisValidationPort.ValidationAssessment assessment = new ValidateAnalysisCaseUseCase(repository,
                new ArtifactHypothesisValidationAdapter(artifact), clock)
                .execute(new ValidateAnalysisCaseCommand(analysisCase.caseId(), "e2e", QueryShape.SIMPLE_SELECT));
        assertEquals(FidelityLevel.SYNTHETIC_PHYSICAL_INDEX, assessment.fidelityLevel());

        new RecordHumanDecisionUseCase(repository, clock).execute(new RecordHumanDecisionCommand(
                analysisCase.caseId(), HumanDecision.APPROVE, "Evidência sintética suficiente para aprovação.",
                "human-reviewer", 1));
        var approved = repository.getById(analysisCase.caseId());
        assertAll(
                () -> assertEquals(CaseStatus.APPROVED, approved.status()),
                () -> assertEquals(1, approved.recommendationVersion()),
                () -> assertEquals(2, approved.evidences().size()),
                () -> assertTrue(defaultTransactionReadOnly()),
                () -> assertEquals(0, monitoredTargetWrites())
        );
    }

    private static PgStatStatementsImpactAdapter impactAdapter() {
        Supplier<Connection> connections = VigilWorkflowE2EIT::reader;
        return new PgStatStatementsImpactAdapter(connections, new JdbcPostgresCapabilityProbe(connections),
                fingerprinter());
    }

    private static HmacQueryFingerprinter fingerprinter() {
        return new HmacQueryFingerprinter("e2e-impact-key".getBytes(StandardCharsets.UTF_8));
    }

    private static void runMeasuredReadWorkload() throws SQLException {
        try (var connection = admin(); var statement = connection.createStatement()) {
            for (int i = 0; i < 30; i++) statement.executeQuery(SQL).close();
        }
    }

    private static String explainJson() throws SQLException {
        try (var connection = reader(); var statement = connection.createStatement();
             var result = statement.executeQuery("EXPLAIN (FORMAT JSON) " + SQL)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static boolean defaultTransactionReadOnly() {
        try (var connection = reader(); var statement = connection.createStatement();
             var result = statement.executeQuery("SHOW default_transaction_read_only")) {
            return result.next() && "on".equals(result.getString(1));
        } catch (SQLException failure) {
            throw new AssertionError(failure);
        }
    }

    private static long monitoredTargetWrites() {
        String sql = "SELECT count(*) FROM pg_stat_statements s JOIN pg_roles r ON r.oid=s.userid "
                + "WHERE r.rolname=? AND s.query ~* '^[[:space:]]*(insert|update|delete|merge|create|alter|drop|truncate)'";
        try (var connection = admin(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, READER);
            try (var result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        } catch (SQLException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Evidence evidence(UUID caseId, UUID id, EvidenceSource source,
                                     com.vigil.core.domain.interfaces.EvidencePayload payload) {
        return new Evidence(id, caseId, payload.type(), source, T2, "e2e-v1", EvidenceConfidence.HIGH,
                payload, Optional.empty());
    }

    private static TransitionMetadata metadata(String reason) {
        return new TransitionMetadata("e2e", reason, T2);
    }

    private static com.vigil.application.port.out.interfaces.IdGenerator ids(UUID... values) {
        var index = new AtomicInteger();
        return () -> values[index.getAndIncrement()];
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(LAB.getJdbcUrl(), LAB.getUsername(), LAB.getPassword());
    }

    private static Connection reader() {
        try {
            return DriverManager.getConnection(LAB.getJdbcUrl(), READER, READER_PASSWORD);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
