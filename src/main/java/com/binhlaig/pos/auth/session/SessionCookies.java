package com.binhlaig.pos.auth.session;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SessionCookies {
    public static final String NAME = "pos_refresh";
    @Value("${app.session.cookie-secure:true}") private boolean secure;
    @Value("${app.session.cookie-same-site:Lax}") private String sameSite;
    @Value("${app.session.cookie-path:/api/auth}") private String path;
    @Value("${app.session.refresh-ttl-seconds:2592000}") private long ttl;
    @Value("${app.cors.allowed-origins:}") private String origins;
    private Set<String> allowed;

    @PostConstruct
    void initialize() {
        allowed = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toSet());
        if (allowed.contains("*") || allowed.contains("null")) throw new IllegalStateException("Explicit browser origins are required");
        if (!Set.of("Lax", "Strict", "None").contains(sameSite) || ("None".equals(sameSite) && !secure))
            throw new IllegalStateException("SameSite must be Lax, Strict, or None; None requires Secure");
        if (ttl <= 0 || !path.startsWith("/") || !"/api/auth".startsWith(path))
            throw new IllegalStateException("Invalid session TTL or cookie path");
    }
    public void requireOrigin(HttpServletRequest request) {
        // Mandatory even with SameSite=None. Missing/null Origin is deliberately rejected.
        if (!allowed.contains(request.getHeader("Origin")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An allowed Origin is required");
    }
    public String read(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies()).filter(c -> NAME.equals(c.getName())).map(jakarta.servlet.http.Cookie::getValue).findFirst().orElse(null);
    }
    public void write(HttpServletResponse response, String raw) {
        write(response, raw, java.time.OffsetDateTime.now().plusSeconds(ttl));
    }
    public void write(HttpServletResponse response, String raw, java.time.OffsetDateTime expiresAt) {
        long remaining = Math.max(0, java.time.Duration.between(java.time.OffsetDateTime.now(), expiresAt).getSeconds());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(NAME, raw == null ? "" : raw)
                .httpOnly(true).secure(secure).sameSite(sameSite).path(path).maxAge(raw == null ? 0 : remaining).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
