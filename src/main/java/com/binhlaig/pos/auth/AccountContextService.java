package com.binhlaig.pos.auth;

import com.binhlaig.pos.admin.Shop;
import com.binhlaig.pos.admin.ShopRepository;
import com.binhlaig.pos.staff.repository.StaffRepository;
import com.binhlaig.pos.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AccountContextService {
    private final UserRepository users;
    private final StaffRepository staff;
    private final ShopRepository shops;

    public record Context(AccountPrincipal principal, Shop shop, String username, String name, String role) {
        public Long getUserId() { return principal.accountType() == AccountPrincipal.AccountType.USER ? principal.accountId() : null; }
        public Long getStaffId() { return principal.accountType() == AccountPrincipal.AccountType.STAFF ? principal.accountId() : null; }
        public Long getShopId() { return principal.shopId(); }
        public String getShopCode() { return shop.getShopCode(); }
        public String getShopName() { return shop.getShopName(); }
        public String getAddress() { return shop.getAddress(); }
        public String getUsername() { return username; }
        public String getName() { return name; }
        public String getRole() { return role; }
    }

    public Context resolve(Authentication authentication) {
        var principal = AccountPrincipal.require(authentication);
        Shop shop = shops.findById(principal.shopId()).orElseThrow(AccountContextService::unavailable);
        if (principal.accountType() == AccountPrincipal.AccountType.USER) {
            var user = principal.requireUser(users);
            return new Context(principal, shop, user.getUsername(), user.getUsername(), user.getRole().name());
        }
        var member = staff.findById(principal.accountId()).orElseThrow(AccountContextService::unavailable);
        if (!principal.shopId().equals(member.getShopId())) throw unavailable();
        // Staff role labels are not owner permissions, even if the DB contains "ADMIN".
        return new Context(principal, shop, null, member.getFullName(), "STAFF");
    }

    public Context requireOwner(Authentication authentication) {
        var context = resolve(authentication);
        if (context.principal().accountType() != AccountPrincipal.AccountType.USER
                || !Role.ADMIN.name().equals(context.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner account required");
        }
        return context;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account or shop unavailable");
    }
}
