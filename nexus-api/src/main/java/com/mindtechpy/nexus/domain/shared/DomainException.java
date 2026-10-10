package com.mindtechpy.nexus.domain.shared;

/**
 * Business rule violation. {@code code} is a stable kebab-case identifier exposed to API clients;
 * {@code kind} lets the web layer pick the HTTP status without the domain knowing about HTTP.
 */
public class DomainException extends RuntimeException {

    public enum Kind { INVALID, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, LOCKED, SATURATED }

    private final Kind kind;
    private final String code;

    public DomainException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public static DomainException invalid(String code, String message) {
        return new DomainException(Kind.INVALID, code, message);
    }

    public static DomainException notFound(String resource) {
        return new DomainException(Kind.NOT_FOUND, "not-found", resource + " no encontrado");
    }

    public static DomainException conflict(String code, String message) {
        return new DomainException(Kind.CONFLICT, code, message);
    }

    public static DomainException forbidden(String code, String message) {
        return new DomainException(Kind.FORBIDDEN, code, message);
    }

    public static DomainException unauthorized(String code, String message) {
        return new DomainException(Kind.UNAUTHORIZED, code, message);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
