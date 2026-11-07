package com.jobpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("jobpilot")
public record AppProperties(String jwtSecret, int jwtTtlHours, String storageDir, List<String> corsOrigins) {
    public AppProperties {
        if (jwtSecret == null || jwtSecret.length() < 32)
            throw new IllegalStateException("JWT_SECRET must be set and at least 32 characters long");
    }
}
