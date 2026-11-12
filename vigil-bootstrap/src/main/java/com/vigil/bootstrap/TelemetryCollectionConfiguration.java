package com.vigil.bootstrap;

import com.vigil.adapters.postgres.PostgresTelemetrySources;
import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.port.out.interfaces.Clock;
import com.vigil.application.port.out.interfaces.DatabaseActivityReader;
import com.vigil.application.port.out.interfaces.QueryTelemetryPort;
import com.vigil.application.port.out.interfaces.WorkspaceTelemetryHistory;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="vigil.telemetry",name="enabled",havingValue="true")
class TelemetryCollectionConfiguration {
    @Bean QueryTelemetryPort queryTelemetryPort(TargetProfile profile,VigilProperties properties){
        var p=require(properties);return PostgresTelemetrySources.counters(profile,p.postgresUser(),certificate(p),System.getenv());
    }
    @Bean DatabaseActivityReader databaseActivityReader(TargetProfile profile,VigilProperties properties){
        var p=require(properties);return PostgresTelemetrySources.activity(profile,p.postgresUser(),certificate(p),System.getenv());
    }
    @Bean ContinuousTelemetryCollector continuousTelemetryCollector(QueryTelemetryPort counters, DatabaseActivityReader activity,
            WorkspaceTelemetryHistory history,Clock clock,TargetProfile target,VigilProperties properties){
        var p=require(properties);return new ContinuousTelemetryCollector(counters,activity,history,clock,target.name(),
                p.interval()==null?Duration.ofSeconds(30):p.interval(),p.retention()==null?Duration.ofHours(24):p.retention(),
                p.maximumSamples()==null?2880:p.maximumSamples());
    }
    private static VigilProperties.Telemetry require(VigilProperties properties){
        var p=properties.telemetry();if(p==null||p.postgresUser()==null||p.postgresUser().isBlank())
            throw new IllegalArgumentException("vigil.telemetry.postgres-user é obrigatório quando a coleta está habilitada");
        return p;
    }
    private static Path certificate(VigilProperties.Telemetry telemetry) {
        return telemetry.sslRootCert() == null || telemetry.sslRootCert().isBlank()
                ? null
                : Path.of(telemetry.sslRootCert());
    }
}
