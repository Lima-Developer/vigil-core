package com.vigil.core.domain.fixture;

import com.vigil.adapters.SystemClockAdapter;

public final class ForbiddenDomainAdapterDependency {
    private final SystemClockAdapter adapter = new SystemClockAdapter();

    public SystemClockAdapter adapter() {
        return adapter;
    }
}
