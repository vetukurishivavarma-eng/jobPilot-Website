package com.jobpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("jobpilot")
public record AppProperties(String jwtSecret, int jwtTtlHours, String storageDir, List<String> corsOrigins,
                            String adzunaAppId, String adzunaAppKey, String adzunaCountry) {
    public AppProperties {
        if (jwtSecret == null || jwtSecret.length() < 32)
            throw new IllegalStateException("JWT_SECRET must be set and at least 32 characters long");
        if (adzunaCountry == null || adzunaCountry.isBlank()) adzunaCountry = "in";
    }

    public boolean adzunaEnabled() {
        return adzunaAppId != null && !adzunaAppId.isBlank() && adzunaAppKey != null && !adzunaAppKey.isBlank();
    }
}
