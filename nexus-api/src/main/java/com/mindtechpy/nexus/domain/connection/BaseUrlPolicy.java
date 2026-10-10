package com.mindtechpy.nexus.domain.connection;

import com.mindtechpy.nexus.domain.shared.DomainException;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SSRF guard for connection base URLs (spec §5.4): http/https only, no credentials in the URL,
 * no literal IPs, and the host must match the allowlist ({@code *.suffix} or exact host).
 */
public record BaseUrlPolicy(List<String> allowedHosts) {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    public BaseUrlPolicy {
        allowedHosts = allowedHosts.stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
    }

    /** Returns the normalized URL (no trailing slash) or throws {@code invalid-base-url}. */
    public String validate(String baseUrl) {
        URI uri = parse(baseUrl);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw rejected("solo se admite http o https");
        }
        if (uri.getRawUserInfo() != null) {
            throw rejected("no se admiten credenciales en la URL");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw rejected("no se admiten query ni fragmento");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || host.startsWith("[") || IPV4.matcher(host).matches()) {
            throw rejected("el host debe ser un nombre DNS, no una IP");
        }
        if (allowedHosts.stream().noneMatch(pattern -> matches(pattern, host))) {
            throw rejected("host fuera de la allowlist");
        }
        return baseUrl.strip().replaceAll("/+$", "");
    }

    private static boolean matches(String pattern, String host) {
        if (pattern.startsWith("*.")) {
            return host.endsWith(pattern.substring(1));
        }
        return host.equals(pattern);
    }

    private static URI parse(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw rejected("la URL es obligatoria");
        }
        try {
            return new URI(baseUrl.strip());
        } catch (Exception e) {
            throw rejected("URL mal formada");
        }
    }

    private static DomainException rejected(String reason) {
        return DomainException.invalid("invalid-base-url", "base_url rechazada: " + reason);
    }
}
