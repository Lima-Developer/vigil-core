package com.vigil.adapters;

import com.vigil.application.port.out.interfaces.Clock;

import java.time.Instant;

public final class SystemClockAdapter implements Clock {
    @Override
    public Instant now() {
        return Instant.now();
    }
}
