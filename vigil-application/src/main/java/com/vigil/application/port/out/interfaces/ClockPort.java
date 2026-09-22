package com.vigil.application.port.out.interfaces;

import java.time.Instant;

public interface ClockPort {
    Instant now();
}
