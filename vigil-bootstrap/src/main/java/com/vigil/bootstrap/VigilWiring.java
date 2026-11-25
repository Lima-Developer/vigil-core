package com.vigil.bootstrap;

import com.vigil.adapters.SystemClockAdapter;
import com.vigil.adapters.UuidIdGeneratorAdapter;
import com.vigil.adapters.inference.OllamaInferenceAdapter;
import com.vigil.adapters.lab.ArtifactHypothesisValidationAdapter;
import com.vigil.adapters.sqlite.SqliteAnalysisCaseAuditReader;
import com.vigil.adapters.sqlite.SqliteAnalysisCaseRepository;
import com.vigil.adapters.sqlite.SqliteConnectionFactory;
import com.vigil.adapters.sqlite.SqliteDatabaseInitializer;
import com.vigil.adapters.sqlite.SqliteStorageProbe;
import com.vigil.adapters.postgres.PostgresPreflightProbes;
import com.vigil.adapters.postgres.PostgresExecutionPlanSource;
import com.vigil.adapters.sqlite.SqliteWorkspaceTelemetryHistory;
import com.vigil.application.casework.DiscoverObservedQueryUseCase;
import com.vigil.application.casework.RecordHumanDecisionUseCase;
import com.vigil.application.casework.TriageAnalysisCaseUseCase;
import com.vigil.application.casework.ValidateAnalysisCaseUseCase;
import com.vigil.application.configuration.StorageConfiguration;
import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.configuration.TargetSslMode;
import com.vigil.application.configuration.VigilConfiguration;
import com.vigil.application.health.CheckVigilHealth;
import com.vigil.application.preflight.CheckPostgresPreflight;
import com.vigil.application.port.out.interfaces.StorageProbe;
import com.vigil.application.port.out.interfaces.PostgresCapabilityProbe;
import com.vigil.application.port.out.interfaces.ExecutionPlanSource;
import com.vigil.application.port.out.interfaces.AnalysisCaseAuditReader;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.IdGenerator;
import com.vigil.application.port.out.interfaces.InferencePort;
import com.vigil.application.port.out.interfaces.HypothesisValidationPort;
import com.vigil.application.port.out.interfaces.QueryFingerprinter;
import com.vigil.application.port.out.interfaces.WorkspaceTelemetryHistory;
import com.vigil.adapters.fingerprint.HmacQueryFingerprinter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.springframework.core.env.Environment;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.context.annotation.Lazy;
import java.nio.file.Path;
import java.net.URI;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Constructor-injected adapters and use cases; no component scanning of core modules. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VigilProperties.class)
class VigilWiring {
    @Bean com.vigil.application.port.out.interfaces.LocalVolumeSpaceReader localVolumeSpaceReader() {
        return new com.vigil.adapters.FileSystemVolumeSpaceReader();
    }
    @Bean Clock clock() { return new SystemClockAdapter(); }
    @Bean IdGenerator idGenerator() { return new UuidIdGeneratorAdapter(); }

    @Bean TargetProfile targetProfile(VigilProperties properties, ConfigurableEnvironment environment) {
        var variableNames = environment.getPropertySources().stream()
                .filter(source -> source instanceof EnumerablePropertySource<?>)
                .map(source -> (EnumerablePropertySource<?>) source)
                .flatMap(source -> Arrays.stream(source.getPropertyNames()))
                .collect(Collectors.toMap(name -> name, name -> "", (first, second) -> first));
        TargetProfileLoader.rejectMultipleTargets(variableNames);
        var target = Objects.requireNonNull(properties.target(), "vigil.target é obrigatório");
        return new TargetProfile(required(target.name(), "vigil.target.name"),
                required(target.environment(), "vigil.target.environment"),
                required(target.host(), "vigil.target.host"),
                targetPort(target.port()),
                required(target.database(), "vigil.target.database"),
                TargetSslMode.fromConfigValue(target.sslMode() == null ? "verify-full" : target.sslMode()),
                target.connectTimeout() == null ? Duration.ofSeconds(5) : target.connectTimeout());
    }

    @Bean StorageConfiguration storageConfiguration(VigilProperties properties) {
        var sqlite = Objects.requireNonNull(properties.sqlite(), "vigil.sqlite é obrigatório");
        if (sqlite.path() == null || !sqlite.path().isAbsolute()) {
            throw new IllegalArgumentException("vigil.sqlite.path deve ser absoluto");
        }
        if (sqlite.minimumFreeBytes() != null && sqlite.minimumFreeBytes() <= 0) {
            throw new IllegalArgumentException("vigil.sqlite.minimum-free-bytes deve ser positivo");
        }
        return new StorageConfiguration(sqlite.path(),
                sqlite.minimumFreeBytes() == null ? 268_435_456L : sqlite.minimumFreeBytes());
    }

    @Bean VigilConfiguration vigilConfiguration(TargetProfile target) {
        return new VigilConfiguration(target.name(), target.environment());
    }

    @Bean StorageProbe storageProbe() { return new SqliteStorageProbe(); }

    @Bean CheckVigilHealth checkVigilHealth(Clock clock, StorageProbe probe, StorageConfiguration storage) {
        return new CheckVigilHealth(clock, "0.1.0-SNAPSHOT", probe, storage);
    }

    @Bean SqliteConnectionFactory sqliteConnectionFactory(Clock clock, StorageConfiguration storage,
                                                          VigilConfiguration configuration, CheckVigilHealth checker) {
        var health = checker.check(configuration);
        if (!health.healthy()) {
            throw new IllegalStateException("Falha ao acessar o armazenamento. Verifique o volume e a integridade do banco.");
        }
        try {
            return SqliteDatabaseInitializer.initialize(storage.databaseFile(), clock);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Falha ao acessar o armazenamento. Verifique o volume e a integridade do banco.");
        }
    }

    @Bean AnalysisCaseRepository analysisCaseRepository(SqliteConnectionFactory connections) {
        return new SqliteAnalysisCaseRepository(connections);
    }
    @Bean WorkspaceTelemetryHistory workspaceTelemetryHistory(SqliteConnectionFactory connections) {
        return new SqliteWorkspaceTelemetryHistory(connections);
    }
    @Bean AnalysisCaseAuditReader analysisCaseAuditReader(SqliteConnectionFactory connections) {
        return new SqliteAnalysisCaseAuditReader(connections);
    }
    @Bean DiscoverObservedQueryUseCase discoverObservedQueryUseCase(AnalysisCaseRepository repository,
                                                                    Clock clock, IdGenerator ids) {
        return new DiscoverObservedQueryUseCase(repository, clock, ids);
    }
    @Bean TriageAnalysisCaseUseCase triageAnalysisCaseUseCase(AnalysisCaseRepository repository,
                                                              Clock clock, IdGenerator ids) {
        return new TriageAnalysisCaseUseCase(repository, clock, ids);
    }
    @Bean RecordHumanDecisionUseCase recordHumanDecisionUseCase(AnalysisCaseRepository repository, Clock clock) {
        return new RecordHumanDecisionUseCase(repository, clock);
    }
    @Bean HypothesisValidationPort hypothesisValidationPort(VigilProperties properties) {
        Path path = properties.validation() != null && properties.validation().artifactPath() != null
                ? properties.validation().artifactPath()
                : Path.of("docs", "experiments", "hypopg-physical-index-2026-11-18", "result.json");
        return new ArtifactHypothesisValidationAdapter(path);
    }
    @Bean ValidateAnalysisCaseUseCase validateAnalysisCaseUseCase(AnalysisCaseRepository repository,
                                                                   HypothesisValidationPort validator, Clock clock) {
        return new ValidateAnalysisCaseUseCase(repository, validator, clock);
    }
    @Bean @Lazy InferencePort inferencePort(VigilProperties properties) {
        var inference = properties.inference();
        URI endpoint = inference != null && inference.endpoint() != null
                ? inference.endpoint() : URI.create("http://127.0.0.1:11434");
        return new OllamaInferenceAdapter(endpoint);
    }

    /**
     * Lazy so the key is required only when a fingerprint is actually computed (for example {@code case discover}),
     * not at startup and not for commands like {@code triage}/{@code show} that never touch it. Absent key fails
     * fail-closed with a clear message rather than hashing raw SQL. The key is installation-stable and never hardcoded.
     */
    @Bean @Lazy QueryFingerprinter queryFingerprinter(VigilProperties properties) {
        var fingerprint = properties.fingerprint();
        var key = fingerprint == null ? null : fingerprint.key();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("vigil.fingerprint.key (VIGIL_FINGERPRINT_KEY) é obrigatória para "
                    + "calcular o fingerprint das consultas; configure uma chave estável por instalação.");
        }
        return new HmacQueryFingerprinter(key.getBytes(StandardCharsets.UTF_8));
    }

    @Bean CaseFlow caseFlow(DiscoverObservedQueryUseCase discover, TriageAnalysisCaseUseCase triage,
                           AnalysisCaseRepository repository, AnalysisCaseAuditReader audit,
                           @Lazy QueryFingerprinter fingerprinter) {
        return new CaseFlow(discover, triage, repository, audit, fingerprinter);
    }

    @Bean @Lazy PostgresCapabilityProbe postgresCapabilityProbe(TargetProfile profile, Environment environment) {
        return PostgresPreflightProbes.forTarget(profile, environment.getProperty("vigil.target.user", "vigil"),
                rootCert(environment), System.getenv());
    }

    @Bean @Lazy CheckPostgresPreflight checkPostgresPreflight(Clock clock, PostgresCapabilityProbe probe) {
        return new CheckPostgresPreflight(clock, probe);
    }

    @Bean @Lazy ExecutionPlanSource executionPlanSource(TargetProfile profile, Environment environment) {
        return PostgresExecutionPlanSource.forTarget(profile, environment.getProperty("vigil.target.user", "vigil"),
                rootCert(environment), System.getenv());
    }

    private static Path rootCert(Environment environment) {
        String value = environment.getProperty("vigil.target.ssl-root-cert");
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static String required(String value, String key) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " é obrigatório");
        return value;
    }

    private static int targetPort(String value) {
        if (value == null) return TargetProfile.DEFAULT_PORT;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("vigil.target.port deve ser um número inteiro entre 1 e 65535");
        }
    }
}
