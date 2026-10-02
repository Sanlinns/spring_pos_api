package com.binhlaig.pos.auth;

import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/** Created by JwtAuthFilter only after JWT and persistent session validation. */
public record AccountPrincipal(AccountType accountType, Long accountId, Long shopId,
                               UUID sessionId) implements Principal {
    public enum AccountType { USER, STAFF }

    public AccountPrincipal {
        Objects.requireNonNull(accountType);
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(shopId);
        Objects.requireNonNull(sessionId);
    }

    @Override public String getName() { return accountType + ":" + accountId; }

    public static AccountPrincipal require(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Validated account session required");
        }
        return principal;
    }

    public User requireUser(UserRepository users) {
        if (accountType != AccountType.USER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "USER account required");
        }
        User user = users.findById(accountId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account unavailable"));
        if (!shopId.equals(user.getShopId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account shop changed");
        }
        return user;
    }
}
