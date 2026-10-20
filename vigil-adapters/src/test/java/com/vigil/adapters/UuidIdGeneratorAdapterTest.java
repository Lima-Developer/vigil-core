package com.vigil.adapters;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UuidIdGeneratorAdapterTest {
    @Test
    void shouldGenerateDistinctRandomVersion4Ids() {
        var generator = new UuidIdGeneratorAdapter();
        var ids = new HashSet<java.util.UUID>();

        for (int index = 0; index < 100; index++) {
            var id = generator.nextId();
            assertNotNull(id);
            assertEquals(4, id.version());
            ids.add(id);
        }

        assertEquals(100, ids.size());
    }
}
