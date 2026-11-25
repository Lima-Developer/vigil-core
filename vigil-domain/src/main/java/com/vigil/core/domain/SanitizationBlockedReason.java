package com.vigil.core.domain;

/**
 * Why sanitization refused to produce a safe context. Each reason carries a fixed, SQL-free {@link #detail()} so a
 * {@link SanitizationResult.Blocked} can never leak the original SQL or any literal — the guarantee holds by
 * construction, not only by test.
 */
public enum SanitizationBlockedReason {
    NULL_INPUT("Entrada ausente."),
    EMPTY("Instrução vazia."),
    TOO_LARGE("Instrução excede o tamanho máximo permitido."),
    MULTIPLE_STATEMENTS("Mais de uma instrução não é permitida."),
    NOT_SELECT("Apenas consultas SELECT são permitidas."),
    FORBIDDEN_CLAUSE("Cláusula não permitida para contexto seguro."),
    UNSUPPORTED_SYNTAX("Sintaxe não suportada para sanitização."),
    INTERNAL_ERROR("Falha interna ao sanitizar.");

    private final String detail;

    SanitizationBlockedReason(String detail) {
        this.detail = detail;
    }

    public String detail() {
        return detail;
    }
}
