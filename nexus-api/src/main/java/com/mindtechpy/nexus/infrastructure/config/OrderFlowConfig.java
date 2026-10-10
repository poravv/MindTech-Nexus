package com.mindtechpy.nexus.infrastructure.config;

import com.mindtechpy.nexus.application.port.out.*;
import com.mindtechpy.nexus.application.saga.Saga;
import com.mindtechpy.nexus.application.saga.catalog.CatalogSyncSaga;
import com.mindtechpy.nexus.application.saga.order.CreateOrderSaga;
import com.mindtechpy.nexus.application.saga.order.OrderStatusSaga;
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
    Saga catalogSyncSaga(PosPort pos, GiftHubPort giftHub, EntityMappingRepository mappings) {
        return new CatalogSyncSaga(pos, giftHub, mappings);
    }
}
