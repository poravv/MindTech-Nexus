package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.domain.operation.ConnectorError;

/** Classified error of an operation as exposed by the runtime and admin APIs. */
record ConnectorErrorView(String kind, String code, String detail, Integer httpStatus) {

    static ConnectorErrorView of(ConnectorError error) {
        return error == null ? null : new ConnectorErrorView(error.kind().name(), error.code(), error.detail(), error.httpStatus());
    }
}
