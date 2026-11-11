package com.vigil.bootstrap;

import com.vigil.adapters.SystemClockAdapter;
import com.vigil.adapters.UuidIdGeneratorAdapter;
import com.vigil.adapters.sqlite.SqliteAnalysisCaseAuditReader;
import com.vigil.adapters.sqlite.SqliteAnalysisCaseRepository;
import com.vigil.adapters.sqlite.SqliteConnectionFactory;
import com.vigil.adapters.sqlite.SqliteDatabaseInitializer;
import com.vigil.application.casework.DiscoverObservedQueryUseCase;
import com.vigil.application.casework.TriageAnalysisCaseUseCase;
import com.vigil.application.configuration.StorageConfiguration;
import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.configuration.TargetSslMode;
import com.vigil.application.configuration.VigilConfiguration;
import com.vigil.application.health.CheckVigilHealth;
import com.vigil.application.port.out.interfaces.AnalysisCaseAuditReader;
import com.vigil.application.port.out.interfaces.AnalysisCaseRepository;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.IdGenerator;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Constructor-injected adapters and use cases; no component scanning of core modules. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VigilProperties.class)
class VigilWiring {
    @Bean Clock clock() { return new SystemClockAdapter(); }
    @Bean IdGenerator idGenerator() { return new UuidIdGeneratorAdapter(); }

    @Bean TargetProfile targetProfile(VigilProperties properties) {
        var target = Objects.requireNonNull(properties.target(), "vigil.target é obrigatório");
        return new TargetProfile(target.name(), target.environment(), target.host(),
                target.port() == null ? TargetProfile.DEFAULT_PORT : target.port(), target.database(),
                TargetSslMode.fromConfigValue(target.sslMode() == null ? "verify-full" : target.sslMode()),
                target.connectTimeout() == null ? Duration.ofSeconds(5) : target.connectTimeout());
    }

    @Bean StorageConfiguration storageConfiguration(VigilProperties properties) {
        var sqlite = Objects.requireNonNull(properties.sqlite(), "vigil.sqlite é obrigatório");
        return new StorageConfiguration(Objects.requireNonNull(sqlite.path(), "vigil.sqlite.path é obrigatório"),
                sqlite.minimumFreeBytes() == null ? 268_435_456L : sqlite.minimumFreeBytes());
    }

    @Bean VigilConfiguration vigilConfiguration(TargetProfile target) {
        return new VigilConfiguration(target.name(), target.environment());
    }

    @Bean SqliteConnectionFactory sqliteConnectionFactory(Clock clock, StorageConfiguration storage,
                                                          VigilConfiguration configuration) {
        var health = new CheckVigilHealth(clock, "0.1.0-SNAPSHOT", new com.vigil.adapters.sqlite.SqliteStorageProbe(), storage)
                .check(configuration);
        if (!health.healthy()) {
            throw new IllegalStateException("Preflight do armazenamento falhou: " + health.storage().problems());
        }
        return SqliteDatabaseInitializer.initialize(storage.databaseFile(), clock);
    }

    @Bean AnalysisCaseRepository analysisCaseRepository(SqliteConnectionFactory connections) {
        return new SqliteAnalysisCaseRepository(connections);
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
}
