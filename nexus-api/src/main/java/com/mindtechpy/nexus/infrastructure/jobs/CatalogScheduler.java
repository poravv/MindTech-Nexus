package com.mindtechpy.nexus.infrastructure.jobs;

import com.mindtechpy.nexus.application.usecase.RunCatalogSync;
import org.springframework.scheduling.annotation.Scheduled;

/** Every minute asks {@link RunCatalogSync} for the catalog runs whose window started (spec §10.2). */
public class CatalogScheduler {

    private final RunCatalogSync runCatalogSync;

    public CatalogScheduler(RunCatalogSync runCatalogSync) {
        this.runCatalogSync = runCatalogSync;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void schedule() {
        runCatalogSync.scheduleDue();
    }
}
