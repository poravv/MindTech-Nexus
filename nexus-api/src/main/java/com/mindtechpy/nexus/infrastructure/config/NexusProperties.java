package com.mindtechpy.nexus.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Platform configuration. Secrets come from the {@code nexus-api-secrets} Secret / local {@code .env};
 * startup fails fast if any of them is missing.
 */
@Validated
@ConfigurationProperties("nexus")
public record NexusProperties(@Valid @NotNull Security security, @Valid @NotNull Connectors connectors,
                              @Valid @NotNull Bootstrap bootstrap) {

    /** @param jwtSecret base64, ≥ 256 bits. @param masterKey base64, exactly 32 bytes (AES-256). */
    public record Security(@NotBlank String jwtSecret, @NotBlank String masterKey) {
        @Override
        public String toString() {
            return "Security[***]";
        }
    }

    /** @param allowedHosts host allowlist for connection base URLs ({@code *.suffix} or exact). */
    public record Connectors(@NotEmpty List<String> allowedHosts) {
    }

    /** First platform_admin, created only when the users table is empty. Blank values skip the bootstrap. */
    public record Bootstrap(String adminEmail, String adminPassword) {
        @Override
        public String toString() {
            return "Bootstrap[adminEmail=" + adminEmail + ", adminPassword=***]";
        }
    }
}
