package com.vigil.application.port.out.interfaces;

public record PostgresPreflightFacts(int serverVersionNum, String databaseName, boolean extensionAvailable,
                                     boolean extensionInstalled, boolean preloaded, boolean statsReadable,
                                     String computeQueryId, String trackIoTiming) {
}
