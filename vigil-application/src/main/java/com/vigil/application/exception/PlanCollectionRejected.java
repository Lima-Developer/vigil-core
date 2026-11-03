package com.vigil.application.exception;

import java.util.Objects;

public final class PlanCollectionRejected extends RuntimeException {
    private final PlanRejectionReason reason;

    public PlanCollectionRejected(PlanRejectionReason reason) {
        super("Coleta de plano rejeitada: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    public PlanRejectionReason reason() {
        return reason;
    }
}
