package com.vigil.application.exception;

public enum TargetFailureReason {
    UNREACHABLE,
    TLS_FAILED,
    AUTHENTICATION_FAILED,
    AUTHORIZATION_DENIED,
    TIMEOUT,
    UNKNOWN
}
