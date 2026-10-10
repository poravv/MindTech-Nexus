package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.RetryLaterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/** Maps every error leaving the web layer to {@code application/problem+json}. */
@RestControllerAdvice
class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);
    private static final int DATABASE_RETRY_AFTER_SECONDS = 5;

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException e) {
        HttpStatus status = switch (e.kind()) {
            case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case LOCKED -> HttpStatus.LOCKED;
            case SATURATED -> HttpStatus.TOO_MANY_REQUESTS;
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (e instanceof RetryLaterException retryLater) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryLater.retryAfter().toSeconds()));
        }
        return response.body(ProblemResponses.problem(status, e.code(), e.getMessage()));
    }

    /** Database unreachable or overloaded: nothing was persisted, so the client must retry (never a false 202). */
    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class})
    ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception e) {
        log.warn("Base de datos no disponible: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(DATABASE_RETRY_AFTER_SECONDS))
                .body(ProblemResponses.problem(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable",
                        "Servicio temporalmente no disponible; reintente"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("Error no controlado", e);
        return ResponseEntity.internalServerError()
                .body(ProblemResponses.problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Error interno"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        ProblemDetail problem = ProblemResponses.problem(HttpStatus.BAD_REQUEST, "validation-failed", "Solicitud inválida");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    /** A body rejected by a domain constructor during deserialization (e.g. an invalid policy) keeps its domain code. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        if (NestedExceptionUtils.getRootCause(e) instanceof DomainException domain) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                    .body(ProblemResponses.problem(HttpStatus.UNPROCESSABLE_CONTENT, domain.code(), domain.getMessage()));
        }
        return super.handleHttpMessageNotReadable(e, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(e, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem && problem.getProperties() == null) {
            ProblemResponses.decorate(problem, ProblemResponses.codeFor(HttpStatus.valueOf(statusCode.value())));
        }
        return response;
    }
}
