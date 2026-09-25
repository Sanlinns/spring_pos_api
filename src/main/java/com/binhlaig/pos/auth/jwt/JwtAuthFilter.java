//package com.binhlaig.pos.auth.jwt;
//
//import com.binhlaig.pos.auth.JwtService;
//import com.binhlaig.pos.auth.SecurityUserDetailsService;
//import jakarta.servlet.FilterChain;
//import jakarta.servlet.ServletException;
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import lombok.RequiredArgsConstructor;
//import org.springframework.lang.NonNull;
//import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.security.core.userdetails.UserDetails;
//import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
//import org.springframework.stereotype.Component;
//import org.springframework.web.filter.OncePerRequestFilter;
//
//import java.io.IOException;
//
//@Component
//@RequiredArgsConstructor
//public class JwtAuthFilter extends OncePerRequestFilter {
//
//    private final JwtService jwtService;
//    private final SecurityUserDetailsService userDetailsService;
//
//    @Override
//    protected void doFilterInternal(
//            @NonNull HttpServletRequest request,
//            @NonNull HttpServletResponse response,
//            @NonNull FilterChain filterChain
//    ) throws ServletException, IOException {
//
//        final String authHeader = request.getHeader("Authorization");
//        final String jwt;
//        final String username;
//
//        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
//            filterChain.doFilter(request, response);
//            return;
//        }
//
//        jwt = authHeader.substring(7);
//        username = jwtService.extractUsername(jwt);
//
//        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
//            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
//
//            if (username.equals(userDetails.getUsername())) {
//                UsernamePasswordAuthenticationToken authToken =
//                        new UsernamePasswordAuthenticationToken(
//                                userDetails,
//                                null,
//                                userDetails.getAuthorities()
//                        );
//
//                authToken.setDetails(
//                        new WebAuthenticationDetailsSource().buildDetails(request)
//                );
//
//                SecurityContextHolder.getContext().setAuthentication(authToken);
//            }
//        }
//
//        filterChain.doFilter(request, response);
//    }
//}











package com.binhlaig.pos.auth.jwt;

import com.binhlaig.pos.auth.JwtService;
import com.binhlaig.pos.auth.SecurityUserDetailsService;
import com.binhlaig.pos.admin.AdminUser;
import com.binhlaig.pos.admin.AdminUserRepository;
import com.binhlaig.pos.admin.ShopRepository;
import com.binhlaig.pos.admin.ShopStatus;
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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SecurityUserDetailsService userDetailsService;
    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final ShopRepository shopRepository;
    private final AdminUserRepository adminUserRepository;

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

        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7);
        final String username;

        try {
            username = jwtService.extractUsername(jwt);
        } catch (ExpiredJwtException e) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"TOKEN_EXPIRED\",\"message\":\"Token expired. Please sign in again.\"}");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"INVALID_TOKEN\",\"message\":\"Invalid token.\"}");
            return;
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            String tokenType = jwtService.extractTokenType(jwt);
            String role = jwtService.extractRole(jwt);

            if ("SUPER_ADMIN".equals(tokenType) && "SUPER_ADMIN".equals(role)) {
                Long adminId = jwtService.extractAdminId(jwt);
                AdminUser adminUser = adminId == null ? null : adminUserRepository.findById(adminId).orElse(null);

                if (adminUser != null
                        && Boolean.TRUE.equals(adminUser.getActive())
                        && jwtService.isAdminTokenValid(jwt, adminUser.getId(), adminUser.getUsername())) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    username,
                                    null,
                                    List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
                            );

                    authToken.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request)
                    );

                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
                filterChain.doFilter(request, response);
                return;
            }

            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            User user = userRepository.findByUsername(username).orElse(null);

            if (user != null
                    && jwtService.isTokenValid(jwt, user)
                    && isShopActive(user.getShopId())
                    && isStaffActiveIfStaffToken(jwt)
                    && username.equals(userDetails.getUsername())) {
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );

                authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                );

                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isShopActive(Long shopId) {
        if (shopId == null) {
            return false;
        }
        return shopRepository.findById(shopId)
                .map(shop -> {
                    ShopStatus status = shop.getStatus();
                    return status != ShopStatus.SUSPENDED
                            && status != ShopStatus.CANCELLED
                            && status != ShopStatus.EXPIRED;
                })
                .orElse(false);
    }

    private boolean isStaffActiveIfStaffToken(String jwt) {
        if (!"STAFF".equals(jwtService.extractTokenType(jwt))) {
            return true;
        }

        Long staffId = jwtService.extractStaffId(jwt);
        Long shopId = jwtService.extractShopId(jwt);
        if (staffId == null || shopId == null) {
            return false;
        }

        Staff staff = staffRepository.findByStaffIdAndShopId(staffId, shopId).orElse(null);
        return staff != null
                && jwtService.isStaffTokenValid(jwt, staff)
                && (staff.getStatus() == null || !staff.getStatus().equalsIgnoreCase("inactive"));
    }
}
