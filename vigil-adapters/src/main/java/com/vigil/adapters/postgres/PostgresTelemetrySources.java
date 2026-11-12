package com.vigil.adapters.postgres;

import com.vigil.adapters.postgres.pgstatstatements.PgStatStatementsTelemetryAdapter;
import com.vigil.adapters.postgres.preflight.JdbcPostgresCapabilityProbe;
import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.port.out.interfaces.DatabaseActivityReader;
import com.vigil.application.port.out.interfaces.QueryTelemetryPort;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Public composition facade; credentials remain in the existing password-source policy. */
public final class PostgresTelemetrySources {
    private PostgresTelemetrySources() { }
    public static QueryTelemetryPort counters(TargetProfile profile,String user,Path sslRootCert,Map<String,String> environment){
        var parts=parts(profile,user,sslRootCert,environment);
        return new PgStatStatementsTelemetryAdapter(parts.factory()::open,parts.probe());
    }
    public static DatabaseActivityReader activity(TargetProfile profile,String user,Path sslRootCert,Map<String,String> environment){
        var parts=parts(profile,user,sslRootCert,environment);
        return new PostgresDatabaseActivityAdapter(parts.factory()::open,parts.probe());
    }
    private static Parts parts(TargetProfile profile,String user,Path cert,Map<String,String> environment){
        Objects.requireNonNull(environment);
        var settings=PostgresTargetSettings.withDefaults(profile,user,cert);
        var factory=new PostgresConnectionFactory(settings,PostgresPasswordSource.fromEnvironment(environment));
        return new Parts(factory,new JdbcPostgresCapabilityProbe(factory::open));
    }
    private record Parts(PostgresConnectionFactory factory,JdbcPostgresCapabilityProbe probe) { }
}
