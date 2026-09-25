package com.binhlaig.pos.auth;

import com.binhlaig.pos.staff.entity.Staff;
import com.binhlaig.pos.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {

    private final String jwtSecret;
    private SecretKey signInKey;

    public JwtService(@Value("${app.jwt.secret}") String jwtSecret) {
        this.jwtSecret = jwtSecret == null ? "" : jwtSecret.trim();
    }

    @PostConstruct
    void validateSecret() {
        if (jwtSecret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET is required");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(jwtSecret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("JWT_SECRET must be a Base64-encoded HMAC key", ex);
        }

        if (decoded.length < 32) {
            throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes");
        }

        signInKey = Keys.hmacShaKeyFor(decoded);
    }

    private SecretKey getSignInKey() {
        return signInKey;
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public Long extractShopId(String token) {
        Object shopId = extractAllClaims(token).get("shopId");
        return toLong(shopId);
    }

    public String extractShopCode(String token) {
        Object shopCode = extractAllClaims(token).get("shopCode");
        return shopCode != null ? shopCode.toString() : null;
    }

    public String extractRole(String token) {
        Object role = extractAllClaims(token).get("role");
        return role != null ? role.toString() : null;
    }

    public String extractBusinessType(String token) {
        Object businessType = extractAllClaims(token).get("businessType");
        return businessType != null ? businessType.toString() : null;
    }

    public Long extractStaffId(String token) {
        Object staffId = extractAllClaims(token).get("staffId");
        return toLong(staffId);
    }

    public Long extractAdminId(String token) {
        Object adminId = extractAllClaims(token).get("adminId");
        return toLong(adminId);
    }

    public String extractTokenType(String token) {
        Object type = extractAllClaims(token).get("type");
        return type != null ? type.toString() : null;
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimResolver) {
        final Claims claims = extractAllClaims(token);
        return claimResolver.apply(claims);
    }

    public String generateToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole().name());
        claims.put("shopId", user.getShopId());
        claims.put("shopCode", user.getShopCode());
        claims.put("businessType", businessTypeName(user));
        claims.put("type", "USER");

        return createToken(claims, user.getUsername());
    }

    /**
     * Do not use this method anymore.
     * Staff token subject must be users.username, not staff.staffId.
     */
    public String generateStaffToken(Staff staff) {
        throw new RuntimeException(
                "Wrong token generator used: generateStaffToken(staff). Use generateStaffToken(staff, user)."
        );
    }

    public String generateStaffToken(Staff staff, User user) {
        if (staff == null) {
            throw new RuntimeException("Staff is required to generate staff token");
        }

        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            throw new RuntimeException("User username is required to generate staff token");
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("role", staff.getRole());
        claims.put("shopId", staff.getShopId());
        claims.put("shopCode", staff.getShopCode());
        claims.put("businessType", businessTypeName(user));
        claims.put("staffId", staff.getStaffId());
        claims.put("type", "STAFF");

        return createToken(claims, user.getUsername());
    }

    public String generateAdminToken(Long adminId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("adminId", adminId);
        claims.put("username", username);
        claims.put("role", role);
        claims.put("type", "SUPER_ADMIN");

        return createToken(claims, username);
    }

    private String createToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24))
                .signWith(getSignInKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSignInKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public boolean isTokenValid(String token, User user) {
        final String username = extractUsername(token);
        final Long tokenShopId = extractShopId(token);
        final String tokenShopCode = extractShopCode(token);

        return username != null
                && user != null
                && username.equals(user.getUsername())
                && user.getShopId() != null
                && user.getShopId().equals(tokenShopId)
                && user.getShopCode() != null
                && user.getShopCode().equalsIgnoreCase(tokenShopCode)
                && !isTokenExpired(token);
    }

    public boolean isStaffTokenValid(String token, Staff staff) {
        final Long staffId = extractStaffId(token);
        final String tokenType = extractTokenType(token);

        return staffId != null
                && staff.getStaffId() != null
                && staffId.equals(staff.getStaffId())
                && "STAFF".equals(tokenType)
                && !isTokenExpired(token);
    }

    public boolean isAdminTokenValid(String token, Long adminId, String username) {
        final Long tokenAdminId = extractAdminId(token);
        final String tokenType = extractTokenType(token);
        final String subject = extractUsername(token);

        return adminId != null
                && tokenAdminId != null
                && adminId.equals(tokenAdminId)
                && username != null
                && username.equals(subject)
                && "SUPER_ADMIN".equals(tokenType)
                && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        Date expiration = extractExpiration(token);
        return expiration != null && expiration.before(new Date());
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }

        if (value instanceof Long) {
            return (Long) value;
        }

        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        return Long.parseLong(value.toString());
    }

    private String businessTypeName(User user) {
        return user.getBusinessType() == null ? "SUPERMARKET" : user.getBusinessType().name();
    }
}
