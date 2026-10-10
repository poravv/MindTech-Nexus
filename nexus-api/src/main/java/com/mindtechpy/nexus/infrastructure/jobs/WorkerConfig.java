package com.mindtechpy.nexus.infrastructure.jobs;

import com.mindtechpy.nexus.application.port.out.JobQueuePort;
import com.mindtechpy.nexus.application.saga.SagaRunner;
import com.mindtechpy.nexus.infrastructure.config.NexusProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs run only where {@code nexus.worker.enabled=true} (default), so the worker can move to its own process. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "nexus.worker.enabled", matchIfMissing = true)
@EnableScheduling
class WorkerConfig {

    @Bean
    OperationWorker operationWorker(JobQueuePort queue, SagaRunner runner, NexusProperties properties) {
        return new OperationWorker(queue, runner, properties.worker().batchSize(), properties.worker().shutdownTimeout());
    }

    @Bean
    LeaseRescueJob leaseRescueJob(JobQueuePort queue) {
        return new LeaseRescueJob(queue);
    }

    @Bean
    PayloadRetentionJob payloadRetentionJob(JdbcClient jdbc) {
        return new PayloadRetentionJob(jdbc);
    }
}
