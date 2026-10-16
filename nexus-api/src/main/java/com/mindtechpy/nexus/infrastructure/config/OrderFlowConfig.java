package com.mindtechpy.nexus.infrastructure.config;

import com.mindtechpy.nexus.application.port.out.*;
import com.mindtechpy.nexus.application.saga.Saga;
import com.mindtechpy.nexus.application.saga.catalog.CatalogSyncSaga;
import com.mindtechpy.nexus.application.saga.order.CreateOrderSaga;
import com.mindtechpy.nexus.application.saga.order.OrderPaidSaga;
import com.mindtechpy.nexus.application.saga.order.OrderStatusSaga;
import com.mindtechpy.nexus.application.usecase.ReceiveGiftHubEvent;
import com.mindtechpy.nexus.application.usecase.ReportOrderStatus;
import com.mindtechpy.nexus.application.usecase.RunCatalogSync;
import com.mindtechpy.nexus.application.usecase.StartOperation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the GiftHub ↔ POS flows (S5): order and catalog sagas and their entry points. */
@Configuration(proxyBeanMethods = false)
class OrderFlowConfig {

    @Bean
    Saga createOrderSaga(PosPort pos, GiftHubPort giftHub, EntityMappingRepository mappings) {
        return new CreateOrderSaga(pos, giftHub, mappings);
    }

    @Bean
    Saga orderStatusSaga(PosPort pos, GiftHubPort giftHub) {
        return new OrderStatusSaga(pos, giftHub);
    }

    @Bean
    Saga orderPaidSaga(PosPort pos, OperationRepository operations) {
        return new OrderPaidSaga(pos, operations);
    }

    @Bean
    Saga catalogSyncSaga(PosPort pos, GiftHubPort giftHub, EntityMappingRepository mappings) {
        return new CatalogSyncSaga(pos, giftHub, mappings);
    }

    @Bean
    ReceiveGiftHubEvent receiveGiftHubEvent(ConnectionRepository connections, IntegrationRepository integrations,
                                            OperationRepository operations, InboundEventRepository inboundEvents,
                                            StartOperation startOperation, SecretCipherPort cipher,
                                            RateLimiterPort rateLimiter, ClockPort clock, TransactionRunner tx) {
        return new ReceiveGiftHubEvent(connections, integrations, operations, inboundEvents, startOperation, cipher,
                rateLimiter, clock, tx);
    }

    @Bean
    ReportOrderStatus reportOrderStatus(OperationRepository operations, StartOperation startOperation) {
        return new ReportOrderStatus(operations, startOperation);
    }

    @Bean
    RunCatalogSync runCatalogSync(IntegrationRepository integrations, OperationRepository operations,
                                  StartOperation startOperation, AuditRepository audit, ClockPort clock,
                                  TransactionRunner tx) {
        return new RunCatalogSync(integrations, operations, startOperation, audit, clock, tx);
    }
}
