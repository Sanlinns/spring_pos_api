package com.binhlaig.pos.auth.jwt;

import com.binhlaig.pos.admin.AdminUser;
import com.binhlaig.pos.admin.AdminUserRepository;
import com.binhlaig.pos.admin.ShopRepository;
import com.binhlaig.pos.admin.ShopStatus;
import com.binhlaig.pos.auth.JwtService;
import com.binhlaig.pos.auth.AccountPrincipal;
import com.binhlaig.pos.staff.entity.Staff;
import com.binhlaig.pos.staff.repository.StaffRepository;
import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final ShopRepository shopRepository;
    private final AdminUserRepository adminUserRepository;
    private final com.binhlaig.pos.auth.session.SessionService sessions;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String jwt = authHeader.substring(7).trim();

        /*
         * Keep downstream controller/service execution outside this try.
         * Only JWT authentication failures should become token errors.
         */
        try {
            String subject = jwtService.extractUsername(jwt);
            String tokenType = jwtService.extractTokenType(jwt);

            if (subject == null || subject.isBlank()) {
                reject(
                        response,
                        "INVALID_TOKEN",
                        "Invalid token."
                );
                return;
            }

            if (SecurityContextHolder.getContext()
                    .getAuthentication() == null) {

                boolean authenticated;

                if ("STAFF".equals(tokenType)) {
                    authenticated = authenticateStaff(jwt, request);
                } else if ("USER".equals(tokenType)) {
                    authenticated = authenticateUser(
                            jwt, subject, request
                    );
                } else if ("SUPER_ADMIN".equals(tokenType)) {
                    authenticated = authenticateAdmin(jwt, request);
                } else {
                    authenticated = false;
                }

                if (!authenticated) {
                    reject(
                            response,
                            "INVALID_TOKEN",
                            "Invalid token or account is unavailable. "
                                    + "Please sign in again."
                    );
                    return;
                }
            }
        } catch (ExpiredJwtException ex) {
            reject(
                    response,
                    "TOKEN_EXPIRED",
                    "Token expired. Please sign in again."
            );
            return;
        } catch (JwtException
                 | IllegalArgumentException
                 | UsernameNotFoundException ex) {
            reject(
                    response,
                    "INVALID_TOKEN",
                    "Invalid token. Please sign in again."
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean authenticateStaff(
            String jwt,
            HttpServletRequest request
    ) {
        Long staffId = jwtService.extractAccountId(jwt);
        Long shopId = jwtService.extractShopId(jwt);

        if (staffId == null || shopId == null) {
            return false;
        }

        Staff staff = staffRepository
                .findById(staffId)
                .orElse(null);

        if (staff == null
                || !sessions.validAccess(jwt, "STAFF", staff.getId(), staff.getShopId())
                || !jwtService.isStaffTokenValid(jwt, staff)
                || !isStaffActive(staff)
                || !isShopActive(staff.getShopId())) {
            return false;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        new AccountPrincipal(AccountPrincipal.AccountType.STAFF, staff.getId(), staff.getShopId(), java.util.UUID.fromString(jwtService.extractSessionId(jwt))),
                        null,
                        staffAuthorities(staff)
                );

        setAuthentication(authentication, request);
        return true;
    }

    private boolean authenticateUser(
            String jwt,
            String username,
            HttpServletRequest request
    ) {
        User user = userRepository
                .findById(Long.valueOf(username))
                .orElse(null);

        if (user == null
                || !sessions.validAccess(jwt, "USER", user.getId(), user.getShopId())
                || !jwtService.isTokenValid(jwt, user)
                || !isShopActive(user.getShopId())) {
            return false;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        new AccountPrincipal(AccountPrincipal.AccountType.USER, user.getId(), user.getShopId(), java.util.UUID.fromString(jwtService.extractSessionId(jwt))),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
                );

        setAuthentication(authentication, request);
        return true;
    }

    private boolean authenticateAdmin(
            String jwt,
            HttpServletRequest request
    ) {
        Long adminId = jwtService.extractAdminId(jwt);

        if (adminId == null) {
            return false;
        }

        AdminUser adminUser = adminUserRepository
                .findById(adminId)
                .orElse(null);

        if (adminUser == null
                || !Boolean.TRUE.equals(adminUser.getActive())
                || !jwtService.isAdminTokenValid(
                jwt,
                adminUser.getId(),
                adminUser.getUsername()
        )) {
            return false;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        adminUser.getUsername(),
                        null,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_SUPER_ADMIN"
                                )
                        )
                );

        setAuthentication(authentication, request);
        return true;
    }

    private List<SimpleGrantedAuthority> staffAuthorities(Staff staff) {
        String role = staff.getRole() == null
                ? ""
                : staff.getRole().trim().toUpperCase(Locale.ROOT);

        /*
         * Explicit staff-only mapping.
         * Never concatenate "ROLE_" with an arbitrary DB role.
         * A staff role of ADMIN/OWNER/SUPER_ADMIN grants only ROLE_STAFF.
         */
        return switch (role) {
            case "CASHIER" -> List.of(
                    new SimpleGrantedAuthority("ROLE_STAFF"),
                    new SimpleGrantedAuthority("ROLE_CASHIER")
            );
            case "KITCHEN" -> List.of(
                    new SimpleGrantedAuthority("ROLE_STAFF"),
                    new SimpleGrantedAuthority("ROLE_KITCHEN")
            );
            case "MANAGER" -> List.of(
                    new SimpleGrantedAuthority("ROLE_STAFF"),
                    new SimpleGrantedAuthority("ROLE_STAFF_MANAGER")
            );
            default -> List.of(
                    new SimpleGrantedAuthority("ROLE_STAFF")
            );
        };
    }

    private boolean isStaffActive(Staff staff) {
        // Preserve the existing status rule.
        return staff.getStatus() == null
                || !"inactive".equalsIgnoreCase(
                staff.getStatus().trim()
        );
    }

    private boolean isShopActive(Long shopId) {
        if (shopId == null) {
            return false;
        }

        return shopRepository.findById(shopId)
                .map(shop -> {
                    ShopStatus status = shop.getStatus();

                    return status != null
                            && (shop.getSubscriptionEndDate() == null || !shop.getSubscriptionEndDate().isBefore(java.time.LocalDate.now()))
                            && status != ShopStatus.SUSPENDED
                            && status != ShopStatus.CANCELLED
                            && status != ShopStatus.EXPIRED;
                })
                .orElse(false);
    }

    private void setAuthentication(
            UsernamePasswordAuthenticationToken authentication,
            HttpServletRequest request
    ) {
        authentication.setDetails(
                new WebAuthenticationDetailsSource()
                        .buildDetails(request)
        );

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);
    }

    private void reject(
            HttpServletResponse response,
            String error,
            String message
    ) throws IOException {
        SecurityContextHolder.clearContext();

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        // error/message are fixed strings supplied by this filter.
        response.getWriter().write(
                "{\"error\":\"" + error
                        + "\",\"message\":\"" + message + "\"}"
        );
    }
}
