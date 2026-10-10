package com.mindtechpy.nexus.infrastructure.security;

import com.mindtechpy.nexus.infrastructure.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed one-minute window of {@value #LIMIT_PER_MINUTE} login attempts per client IP (spec §5.2).
 * In memory: valid for a single replica, like the rest of v1 rate limiting (ADR-002).
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

    static final int LIMIT_PER_MINUTE = 10;
    private static final String LOGIN_PATH = "/api/admin/auth/login";
    private static final int MAX_TRACKED_IPS = 10_000;

    private record Window(long minute, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final ProblemResponses problems;
    private final Clock clock = Clock.systemUTC();

    public LoginRateLimitFilter(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && LOGIN_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long minute = clock.millis() / 60_000;
        if (windows.size() > MAX_TRACKED_IPS) {
            windows.values().removeIf(window -> window.minute() < minute);
        }
        Window window = windows.merge(request.getRemoteAddr(), new Window(minute, 1),
                (current, fresh) -> current.minute() == minute ? new Window(minute, current.count() + 1) : fresh);
        if (window.count() > LIMIT_PER_MINUTE) {
            response.setHeader("Retry-After", String.valueOf(60 - (clock.millis() / 1000) % 60));
            problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Demasiados intentos de login; reintente en un minuto");
            return;
        }
        chain.doFilter(request, response);
    }
}
