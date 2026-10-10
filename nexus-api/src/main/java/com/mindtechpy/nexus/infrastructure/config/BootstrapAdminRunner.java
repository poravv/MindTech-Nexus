package com.mindtechpy.nexus.infrastructure.config;

import com.mindtechpy.nexus.application.usecase.BootstrapAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates the first platform_admin from {@code NEXUS_BOOTSTRAP_ADMIN_*} when there are no users yet. */
@Component
class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final BootstrapAdmin bootstrapAdmin;
    private final NexusProperties properties;

    BootstrapAdminRunner(BootstrapAdmin bootstrapAdmin, NexusProperties properties) {
        this.bootstrapAdmin = bootstrapAdmin;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        NexusProperties.Bootstrap bootstrap = properties.bootstrap();
        if (isBlank(bootstrap.adminEmail()) || isBlank(bootstrap.adminPassword())) {
            log.info("[NEXUS-AUTH] bootstrap de admin omitido: variables no configuradas");
            return;
        }
        if (bootstrapAdmin.execute(bootstrap.adminEmail(), bootstrap.adminPassword())) {
            log.info("[NEXUS-AUTH] platform_admin inicial creado; debe cambiar la contraseña en el primer login");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
