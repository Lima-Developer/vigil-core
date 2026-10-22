package com.vigil.application.port.out.interfaces;

public interface PostgresCapabilityProbe {
    PostgresPreflightFacts inspect();
}
