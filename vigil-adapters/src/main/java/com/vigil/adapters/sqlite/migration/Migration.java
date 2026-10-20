package com.vigil.adapters.sqlite.migration;

/**
 * Catalog entry of a functional migration: a positive version, a description recorded in {@code schema_version}
 * and the classpath location of its SQL script.
 */
public record Migration(int version, String description, String resource) {
    public Migration {
        if (version <= 0) {
            throw new IllegalArgumentException("Migration version must be positive.");
        }

        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Migration description must not be blank.");
        }

        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("Migration resource must not be blank.");
        }
    }
}
