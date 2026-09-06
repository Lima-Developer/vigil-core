package com.vigil.application.configuration;

public record VigilConfiguration(
        String targetName,
        String environment
) {
    public VigilConfiguration{
        if (targetName == null || targetName.isEmpty()){
            throw new IllegalArgumentException("Target name cannot be null or empty");
        }

        if (environment == null || environment.isEmpty()){
            throw new IllegalArgumentException("Environment cannot be null or empty");
        }
    }
}
