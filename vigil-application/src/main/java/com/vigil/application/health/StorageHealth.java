package com.vigil.application.health;

import java.util.List;

public record StorageHealth(boolean ok, List<String> problems) {
    public StorageHealth {
        problems = List.copyOf(problems);
    }
}
