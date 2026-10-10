package com.mindtechpy.nexus.infrastructure.web;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/** RFC 9457 problem details with the stable {@code type}, {@code code} and {@code correlationId} of spec §5.4. */
@Component
public class ProblemResponses {

    public static final String TYPE_BASE = "https://nexus.mindtechpy.net/problems/";

    private final JsonMapper json;

    public ProblemResponses(JsonMapper json) {
        this.json = json;
    }

    public static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        return decorate(problem, code);
    }

    /** Adds type/code/correlationId to a problem built elsewhere (e.g. by Spring MVC). */
    public static ProblemDetail decorate(ProblemDetail problem, String code) {
        problem.setType(URI.create(TYPE_BASE + code));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return problem;
    }

    public static String codeFor(HttpStatus status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** For filters and security handlers that run outside Spring MVC. */
    public void write(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), problem(status, code, detail));
    }
}
