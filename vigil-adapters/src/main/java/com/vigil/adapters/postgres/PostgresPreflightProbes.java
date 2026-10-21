package com.vigil.adapters.postgres;

import com.vigil.adapters.postgres.preflight.JdbcPostgresCapabilityProbe;
import com.vigil.application.configuration.TargetProfile;
import com.vigil.application.port.out.interfaces.PostgresCapabilityProbe;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Adapter composition using the target connection policy of VIG-032. */
public final class PostgresPreflightProbes {
    private PostgresPreflightProbes() {
    }

    public static PostgresCapabilityProbe forTarget(TargetProfile profile, String user, Path sslRootCert,
                                                    Map<String, String> environment) {
        Objects.requireNonNull(environment, "Environment não pode ser nulo");
        var settings = PostgresTargetSettings.withDefaults(profile, user, sslRootCert);
        var factory = new PostgresConnectionFactory(settings, PostgresPasswordSource.fromEnvironment(environment));
        return new JdbcPostgresCapabilityProbe(factory::open);
    }
}
