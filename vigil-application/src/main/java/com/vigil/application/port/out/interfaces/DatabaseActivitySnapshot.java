package com.vigil.application.port.out.interfaces;

import java.time.Instant;
import java.util.Objects;

public record DatabaseActivitySnapshot(Instant observedAt, long activeSessions, long waitingSessions,
                                       long blockedSessions, boolean fullVisibility) {
    public DatabaseActivitySnapshot {
        Objects.requireNonNull(observedAt);
        if (activeSessions<0 || waitingSessions<0 || blockedSessions<0) throw new IllegalArgumentException("Negative activity count");
    }
}
