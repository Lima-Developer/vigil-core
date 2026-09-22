package com.vigil.core.domain;

import java.util.Objects;

public final class NormalizedQueryTemplate {
    private final String content;

    public NormalizedQueryTemplate(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Normalized query template must not be blank.");
        }

        this.content = content;
    }

    @Override
    public String toString() {
        return "NormalizedQueryTemplate[redacted]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NormalizedQueryTemplate template
                && content.equals(template.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content);
    }
}
