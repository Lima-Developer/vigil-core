package com.vigil.application.configuration;

public enum TargetSslMode {
    VERIFY_CA("verify-ca"),
    VERIFY_FULL("verify-full");

    private final String configValue;

    TargetSslMode(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static TargetSslMode fromConfigValue(String value) {
        for (TargetSslMode mode : values()) {
            if (mode.configValue.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "VIGIL_TARGET_SSL_MODE deve ser verify-ca ou verify-full. TLS sem verificação não é aceito.");
    }
}
