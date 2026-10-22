package com.vigil.adapters.postgres.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RelationSelectorTest {
    @ParameterizedTest
    @CsvSource(value = {"null,t", "s,null", "'',t", "s,''"}, nullValues = "null")
    void requiresSchemaAndName(String schema, String name) {
        assertThrows(IllegalArgumentException.class, () -> new RelationSelector(schema, name));
    }

    @Test
    void doesNotPrintTheNames() {
        assertFalse(new RelationSelector("payroll", "salaries").toString().contains("payroll"));
        assertFalse(new RelationSelector("payroll", "salaries").toString().contains("salaries"));
    }

    @Test
    void equalityUsesBothNames() {
        assertEquals(new RelationSelector("a", "b"), new RelationSelector("a", "b"));
        assertEquals(new RelationSelector("a", "b").hashCode(), new RelationSelector("a", "b").hashCode());
        assertNotEquals(new RelationSelector("a", "b"), new RelationSelector("a", "c"));
        assertNotEquals(new RelationSelector("a", "b"), new RelationSelector("c", "b"));
        assertNotEquals(new RelationSelector("a", "b"), "a.b");
    }
}
