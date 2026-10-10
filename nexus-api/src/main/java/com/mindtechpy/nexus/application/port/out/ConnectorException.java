package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.operation.ConnectorError;

/** A connector call failed; {@link #error()} carries the classification the engine acts on. */
public class ConnectorException extends RuntimeException {

    private final transient ConnectorError error;

    public ConnectorException(ConnectorError error) {
        super(error.kind() + ":" + error.code());
        this.error = error;
    }

    public ConnectorError error() {
        return error;
    }
}
