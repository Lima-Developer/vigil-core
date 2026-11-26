package com.vigil.core.domain.enums;

public enum QueryShape {
    SIMPLE_SELECT(true),
    JOIN(true),
    AGGREGATE(true),
    SUBQUERY(false),
    SET_OPERATION(false),
    UNSUPPORTED(false);

    private final boolean automaticValidationSupported;

    QueryShape(boolean automaticValidationSupported) {
        this.automaticValidationSupported = automaticValidationSupported;
    }

    public boolean automaticValidationSupported() {
        return automaticValidationSupported;
    }
}
