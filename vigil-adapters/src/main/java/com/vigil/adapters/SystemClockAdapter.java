package com.vigil.adapters;

import com.vigil.application.port.out.interfaces.ClockPort;

import java.time.Instant;

public final class SystemClockAdapter implements ClockPort {
    @Override
    public Instant now() {
        return Instant.now();
    }
}
