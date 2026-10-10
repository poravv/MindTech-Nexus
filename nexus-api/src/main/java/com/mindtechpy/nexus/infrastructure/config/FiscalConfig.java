package com.mindtechpy.nexus.infrastructure.config;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.port.out.SecretCipherPort;
import com.mindtechpy.nexus.application.port.out.SifenPort;
import com.mindtechpy.nexus.application.saga.fiscal.CancelInvoiceSaga;
import com.mindtechpy.nexus.application.saga.fiscal.IssueInvoiceSaga;
import com.mindtechpy.nexus.application.usecase.CancelInvoice;
import com.mindtechpy.nexus.application.usecase.InvoiceQueries;
import com.mindtechpy.nexus.application.usecase.IssueInvoice;
import com.mindtechpy.nexus.application.usecase.StartOperation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fiscal flow (S3): SIFEN sagas, registered by kind in the {@code SagaRunner}, and their use cases. */
@Configuration(proxyBeanMethods = false)
class FiscalConfig {

    @Bean
    IssueInvoiceSaga issueInvoiceSaga(SifenPort sifen, ClockPort clock) {
        return new IssueInvoiceSaga(sifen, clock);
    }

    @Bean
    CancelInvoiceSaga cancelInvoiceSaga(SifenPort sifen) {
        return new CancelInvoiceSaga(sifen);
    }

    @Bean
    IssueInvoice issueInvoice(IntegrationRepository integrations, StartOperation startOperation) {
        return new IssueInvoice(integrations, startOperation);
    }

    @Bean
    CancelInvoice cancelInvoice(IntegrationRepository integrations, OperationRepository operations,
                                StartOperation startOperation) {
        return new CancelInvoice(integrations, operations, startOperation);
    }

    @Bean
    InvoiceQueries invoiceQueries(OperationRepository operations, IntegrationRepository integrations,
                                  ConnectionRepository connections, SecretCipherPort cipher, SifenPort sifen) {
        return new InvoiceQueries(operations, integrations, connections, cipher, sifen);
    }
}
