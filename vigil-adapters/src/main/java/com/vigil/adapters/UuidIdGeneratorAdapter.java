package com.vigil.adapters;

import com.vigil.application.port.out.interfaces.IdGenerator;

import java.util.UUID;

public final class UuidIdGeneratorAdapter implements IdGenerator {
    @Override
    public UUID nextId() {
        return UUID.randomUUID();
    }
}
