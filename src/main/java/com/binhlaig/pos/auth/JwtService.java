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
import java.util.Objects;
import java.util.function.Function;

@Service
public class JwtService {

    private final String jwtSecret;
    private final long expirationMs;
    private SecretKey signInKey;

    public JwtService(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.jwt.expiration-ms:900000}") long expirationMs
    ) {
        this.jwtSecret = jwtSecret == null ? "" : jwtSecret.trim();

        if (expirationMs <= 0) {
            throw new IllegalArgumentException(
                    "app.jwt.expiration-ms must be greater than zero"
            );
        }

        this.expirationMs = expirationMs;
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
            throw new IllegalStateException(
                    "JWT_SECRET must be a Base64-encoded HMAC key",
                    ex
            );
        }

        if (decoded.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must decode to at least 32 bytes"
            );
        }

        signInKey = Keys.hmacShaKeyFor(decoded);
    }

    private SecretKey getSignInKey() {
        if (signInKey == null) {
            throw new IllegalStateException(
                    "JWT signing key has not been initialized"
            );
        }

        return signInKey;
    }

    // For STAFF tokens, this returns the staff subject,
    // not a username from the users table.
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public Long extractShopId(String token) {
        return toLong(extractAllClaims(token).get("shopId"));
    }

    public String extractShopCode(String token) {
        return extractAllClaims(token).get("shopCode", String.class);
    }

    public String extractRole(String token) {
        return extractAllClaims(token).get("role", String.class);
    }

    public String extractBusinessType(String token) {
        return extractAllClaims(token).get("businessType", String.class);
    }

    public Long extractStaffId(String token) {
        return toLong(extractAllClaims(token).get("staffId"));
    }

    public Long extractAdminId(String token) {
        return toLong(extractAllClaims(token).get("adminId"));
    }

    public String extractTokenType(String token) {
        return extractAllClaims(token).get("type", String.class);
    }

    public <T> T extractClaim(
            String token,
            Function<Claims, T> claimResolver
    ) {
        return claimResolver.apply(extractAllClaims(token));
    }

    public String generateToken(User user) {
        return generateToken(user, null);
    }

    public String extractSessionId(String token) {
        return extractAllClaims(token).get("sid", String.class);
    }

    public Long extractAccountId(String token) {
        return toLong(extractAllClaims(token).get("accountId"));
    }

    public String generateToken(User user, String sessionId) {
        if (user == null
                || user.getUsername() == null
                || user.getUsername().isBlank()
                || user.getRole() == null
                || user.getShopId() == null
                || user.getShopCode() == null
                || user.getShopCode().isBlank()) {
            throw new IllegalArgumentException(
                    "Valid user identity and shop are required"
            );
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "USER");
        claims.put("sid", sessionId);
        claims.put("accountId", user.getId());
        claims.put("role", user.getRole().name());
        claims.put("shopId", user.getShopId());
        claims.put("shopCode", user.getShopCode());
        claims.put("businessType", businessTypeName(user));

        return createToken(claims, String.valueOf(user.getId()));
    }

    public String staffSubject(Staff staff) {
        if (staff == null
                || staff.getId() == null
                || staff.getShopId() == null) {
            throw new IllegalArgumentException(
                    "Persisted staff and shop ID are required"
            );
        }

        // getId(): staff table primary key.
        // getStaffId(): staff login/business ID.
        return "staff:" + staff.getShopId() + ":" + staff.getId();
    }

    public String generateStaffToken(Staff staff) {
        return generateStaffToken(staff, null);
    }

    public String generateStaffToken(Staff staff, User shopUser) {
        return generateStaffToken(staff, shopUser, null);
    }

    public String generateStaffToken(Staff staff, User shopUser, String sessionId) {
        String subject = staffSubject(staff);

        if (staff.getStaffId() == null
                || staff.getShopCode() == null
                || staff.getShopCode().isBlank()) {
            throw new IllegalArgumentException(
                    "Staff ID and shop code are required"
            );
        }

        if (shopUser != null
                && !Objects.equals(
                staff.getShopId(),
                shopUser.getShopId()
        )) {
            throw new IllegalArgumentException("Staff shop mismatch");
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "STAFF");
        claims.put("sid", sessionId);
        claims.put("accountId", staff.getId());
        claims.put("staffId", staff.getStaffId());
        claims.put("shopId", staff.getShopId());
        claims.put("shopCode", staff.getShopCode());

        // Informational only.
        // The filter must load current staff authorities from DB.
        claims.put("role", staff.getRole());

        if (shopUser != null) {
            claims.put("businessType", businessTypeName(shopUser));
        }

        return createToken(claims, subject);
    }

    public String generateAdminToken(
            Long adminId,
            String username,
            String role
    ) {
        if (adminId == null
                || username == null
                || username.isBlank()
                || !"SUPER_ADMIN".equals(role)) {
            throw new IllegalArgumentException(
                    "Valid super admin identity is required"
            );
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "SUPER_ADMIN");
        claims.put("adminId", adminId);
        claims.put("username", username);
        claims.put("role", "SUPER_ADMIN");

        return createToken(claims, username);
    }

    private String createToken(
            Map<String, Object> claims,
            String subject
    ) {
        long now = System.currentTimeMillis();

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(now))
                .setExpiration(
                        new Date(Math.addExact(now, expirationMs))
                )
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
        if (user == null
                || user.getUsername() == null
                || user.getShopId() == null
                || user.getShopCode() == null) {
            return false;
        }

        Claims claims = extractAllClaims(token);

        return "USER".equals(claims.get("type", String.class))
                && String.valueOf(user.getId()).equals(claims.getSubject())
                && user.getId().equals(toLong(claims.get("accountId")))
                && user.getShopId().equals(
                toLong(claims.get("shopId"))
        )
                && user.getShopCode().equalsIgnoreCase(
                claims.get("shopCode", String.class)
        )
                && hasValidExpiration(claims);
    }

    public boolean isStaffTokenValid(String token, Staff staff) {
        if (staff == null
                || staff.getId() == null
                || staff.getStaffId() == null
                || staff.getShopId() == null
                || staff.getShopCode() == null) {
            return false;
        }

        Claims claims = extractAllClaims(token);

        return "STAFF".equals(claims.get("type", String.class))
                && staffSubject(staff).equals(claims.getSubject())
                && staff.getStaffId().equals(
                toLong(claims.get("staffId"))
        )
                && staff.getShopId().equals(
                toLong(claims.get("shopId"))
        )
                && staff.getShopCode().equalsIgnoreCase(
                claims.get("shopCode", String.class)
        )
                && hasValidExpiration(claims);
    }

    public boolean isAdminTokenValid(
            String token,
            Long adminId,
            String username
    ) {
        if (adminId == null || username == null) {
            return false;
        }

        Claims claims = extractAllClaims(token);

        return "SUPER_ADMIN".equals(
                claims.get("type", String.class)
        )
                && "SUPER_ADMIN".equals(
                claims.get("role", String.class)
        )
                && adminId.equals(toLong(claims.get("adminId")))
                && username.equals(claims.getSubject())
                && hasValidExpiration(claims);
    }

    private boolean hasValidExpiration(Claims claims) {
        Date expiration = claims.getExpiration();

        return expiration != null
                && expiration.after(new Date());
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number number) {
            return number.longValue();
        }

        return Long.parseLong(value.toString());
    }

    private String businessTypeName(User user) {
        return user.getBusinessType() == null
                ? "SUPERMARKET"
                : user.getBusinessType().name();
    }
}













// old function

//package com.binhlaig.pos.auth;
//
//import com.binhlaig.pos.staff.entity.Staff;
//import com.binhlaig.pos.user.User;
//import io.jsonwebtoken.Claims;
//import io.jsonwebtoken.Jwts;
//import io.jsonwebtoken.SignatureAlgorithm;
//import io.jsonwebtoken.security.Keys;
//import jakarta.annotation.PostConstruct;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Service;
//
//import javax.crypto.SecretKey;
//import java.util.Base64;
//import java.util.Date;
//import java.util.HashMap;
//import java.util.Map;
//import java.util.function.Function;
//
//@Service
//public class JwtService {
//
//    private final String jwtSecret;
//    private SecretKey signInKey;
//
//    public JwtService(@Value("${app.jwt.secret}") String jwtSecret) {
//        this.jwtSecret = jwtSecret == null ? "" : jwtSecret.trim();
//    }
//
//    @PostConstruct
//    void validateSecret() {
//        if (jwtSecret.isBlank()) {
//            throw new IllegalStateException("JWT_SECRET is required");
//        }
//
//        byte[] decoded;
//        try {
//            decoded = Base64.getDecoder().decode(jwtSecret);
//        } catch (IllegalArgumentException ex) {
//            throw new IllegalStateException("JWT_SECRET must be a Base64-encoded HMAC key", ex);
//        }
//
//        if (decoded.length < 32) {
//            throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes");
//        }
//
//        signInKey = Keys.hmacShaKeyFor(decoded);
//    }
//
//    private SecretKey getSignInKey() {
//        return signInKey;
//    }
//
//    public String extractUsername(String token) {
//        return extractClaim(token, Claims::getSubject);
//    }
//
//    public Date extractExpiration(String token) {
//        return extractClaim(token, Claims::getExpiration);
//    }
//
//    public Long extractShopId(String token) {
//        Object shopId = extractAllClaims(token).get("shopId");
//        return toLong(shopId);
//    }
//
//    public String extractShopCode(String token) {
//        Object shopCode = extractAllClaims(token).get("shopCode");
//        return shopCode != null ? shopCode.toString() : null;
//    }
//
//    public String extractRole(String token) {
//        Object role = extractAllClaims(token).get("role");
//        return role != null ? role.toString() : null;
//    }
//
//    public String extractBusinessType(String token) {
//        Object businessType = extractAllClaims(token).get("businessType");
//        return businessType != null ? businessType.toString() : null;
//    }
//
//    public Long extractStaffId(String token) {
//        Object staffId = extractAllClaims(token).get("staffId");
//        return toLong(staffId);
//    }
//
//    public Long extractAdminId(String token) {
//        Object adminId = extractAllClaims(token).get("adminId");
//        return toLong(adminId);
//    }
//
//    public String extractTokenType(String token) {
//        Object type = extractAllClaims(token).get("type");
//        return type != null ? type.toString() : null;
//    }
//
//    public <T> T extractClaim(String token, Function<Claims, T> claimResolver) {
//        final Claims claims = extractAllClaims(token);
//        return claimResolver.apply(claims);
//    }
//
//    public String generateToken(User user) {
//        Map<String, Object> claims = new HashMap<>();
//        claims.put("role", user.getRole().name());
//        claims.put("shopId", user.getShopId());
//        claims.put("shopCode", user.getShopCode());
//        claims.put("businessType", businessTypeName(user));
//        claims.put("type", "USER");
//
//        return createToken(claims, user.getUsername());
//    }
//
//    /**
//     * Do not use this method anymore.
//     * Staff token subject must be users.username, not staff.staffId.
//     */
//    public String generateStaffToken(Staff staff) {
//        throw new RuntimeException(
//                "Wrong token generator used: generateStaffToken(staff). Use generateStaffToken(staff, user)."
//        );
//    }
//
//    public String generateStaffToken(Staff staff, User user) {
//        if (staff == null) {
//            throw new RuntimeException("Staff is required to generate staff token");
//        }
//
//        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
//            throw new RuntimeException("User username is required to generate staff token");
//        }
//
//        Map<String, Object> claims = new HashMap<>();
//        claims.put("role", staff.getRole());
//        claims.put("shopId", staff.getShopId());
//        claims.put("shopCode", staff.getShopCode());
//        claims.put("businessType", businessTypeName(user));
//        claims.put("staffId", staff.getStaffId());
//        claims.put("type", "STAFF");
//
//        return createToken(claims, user.getUsername());
//    }
//
//    public String generateAdminToken(Long adminId, String username, String role) {
//        Map<String, Object> claims = new HashMap<>();
//        claims.put("adminId", adminId);
//        claims.put("username", username);
//        claims.put("role", role);
//        claims.put("type", "SUPER_ADMIN");
//
//        return createToken(claims, username);
//    }
//
//    private String createToken(Map<String, Object> claims, String subject) {
//        return Jwts.builder()
//                .setClaims(claims)
//                .setSubject(subject)
//                .setIssuedAt(new Date(System.currentTimeMillis()))
//                .setExpiration(new Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24))
//                .signWith(getSignInKey(), SignatureAlgorithm.HS256)
//                .compact();
//    }
//
//    private Claims extractAllClaims(String token) {
//        return Jwts.parserBuilder()
//                .setSigningKey(getSignInKey())
//                .build()
//                .parseClaimsJws(token)
//                .getBody();
//    }
//
//    public boolean isTokenValid(String token, User user) {
//        final String username = extractUsername(token);
//        final Long tokenShopId = extractShopId(token);
//        final String tokenShopCode = extractShopCode(token);
//
//        return username != null
//                && user != null
//                && username.equals(user.getUsername())
//                && user.getShopId() != null
//                && user.getShopId().equals(tokenShopId)
//                && user.getShopCode() != null
//                && user.getShopCode().equalsIgnoreCase(tokenShopCode)
//                && !isTokenExpired(token);
//    }
//
//    public boolean isStaffTokenValid(String token, Staff staff) {
//        final Long staffId = extractStaffId(token);
//        final String tokenType = extractTokenType(token);
//
//        return staffId != null
//                && staff.getStaffId() != null
//                && staffId.equals(staff.getStaffId())
//                && "STAFF".equals(tokenType)
//                && !isTokenExpired(token);
//    }
//
//    public boolean isAdminTokenValid(String token, Long adminId, String username) {
//        final Long tokenAdminId = extractAdminId(token);
//        final String tokenType = extractTokenType(token);
//        final String subject = extractUsername(token);
//
//        return adminId != null
//                && tokenAdminId != null
//                && adminId.equals(tokenAdminId)
//                && username != null
//                && username.equals(subject)
//                && "SUPER_ADMIN".equals(tokenType)
//                && !isTokenExpired(token);
//    }
//
//    private boolean isTokenExpired(String token) {
//        Date expiration = extractExpiration(token);
//        return expiration != null && expiration.before(new Date());
//    }
//
//    private Long toLong(Object value) {
//        if (value == null) {
//            return null;
//        }
//
//        if (value instanceof Integer) {
//            return ((Integer) value).longValue();
//        }
//
//        if (value instanceof Long) {
//            return (Long) value;
//        }
//
//        if (value instanceof Number) {
//            return ((Number) value).longValue();
//        }
//
//        return Long.parseLong(value.toString());
//    }
//
//    private String businessTypeName(User user) {
//        return user.getBusinessType() == null ? "SUPERMARKET" : user.getBusinessType().name();
//    }
//}
