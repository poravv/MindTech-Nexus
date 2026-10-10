package com.mindtechpy.nexus.infrastructure.config;

import com.mindtechpy.nexus.application.usecase.RecryptCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Finishes a master-key rotation at startup (ADR-006): while {@code NEXUS_MASTER_KEY_PREVIOUS} is set, every
 * credential still sealed with it is sealed again with {@code NEXUS_MASTER_KEY}. Without it there is nothing to do.
 * A failure does not stop the API: both keys still open every credential, and the next start resumes.
 */
@Component
class RecryptCredentialsRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecryptCredentialsRunner.class);

    private final RecryptCredentials recryptCredentials;
    private final NexusProperties properties;

    RecryptCredentialsRunner(RecryptCredentials recryptCredentials, NexusProperties properties) {
        this.recryptCredentials = recryptCredentials;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String previous = properties.security().masterKeyPrevious();
        if (previous == null || previous.isBlank()) {
            return;
        }
        try {
            recryptCredentials.execute();
        } catch (RuntimeException e) {
            log.warn("[NEXUS-RECRYPT] recifrado interrumpido; se reanuda en el próximo arranque", e);
        }
    }
}
