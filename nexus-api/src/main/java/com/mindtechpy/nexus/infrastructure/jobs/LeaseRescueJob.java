package com.mindtechpy.nexus.infrastructure.jobs;

import com.mindtechpy.nexus.application.port.out.JobQueuePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Every minute returns operations whose worker died mid-step (lease expired) to RETRY. */
public class LeaseRescueJob {

    private static final Logger log = LoggerFactory.getLogger(LeaseRescueJob.class);

    private final JobQueuePort queue;

    public LeaseRescueJob(JobQueuePort queue) {
        this.queue = queue;
    }

    @Scheduled(fixedDelay = 60_000)
    public void rescue() {
        int rescued = queue.rescueExpiredLeases();
        if (rescued > 0) {
            log.warn("Rescatadas {} operaciones con lease vencido", rescued);
        }
    }
}
