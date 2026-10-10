package com.mindtechpy.nexus.application.saga.order;

import com.mindtechpy.nexus.application.port.out.ConnectorException;
import com.mindtechpy.nexus.application.saga.StepContext;
import com.mindtechpy.nexus.application.saga.StepContext.Endpoint;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;

import java.time.Duration;

/** An ORDER integration goes from GiftHub (source, where orders come from) to the POS (target). */
record OrderEndpoints(Endpoint giftHub, Endpoint pos, Duration readTimeout) {

    /** @throws ConnectorException {@code REJECTED integration-misconfigured} before any remote call */
    static OrderEndpoints of(StepContext context) {
        if (context.source() == null || context.source().connection().connector() != ConnectorType.GIFTHUB
                || context.target().connection().connector() != ConnectorType.POS) {
            throw new ConnectorException(ConnectorError.of(ErrorKind.REJECTED, "integration-misconfigured",
                    "La integración ORDER debe tener GiftHub como origen y el POS como destino"));
        }
        return new OrderEndpoints(context.source(), context.target(),
                Duration.ofMillis(context.integration().policy().readTimeoutMs()));
    }
}
