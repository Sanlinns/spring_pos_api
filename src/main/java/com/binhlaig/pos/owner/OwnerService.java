package com.binhlaig.pos.owner;

import com.binhlaig.pos.admin.Shop;
import com.binhlaig.pos.admin.ShopRepository;
import com.binhlaig.pos.auth.Role;
import com.binhlaig.pos.owner.dto.OwnerProfileResponse;
import com.binhlaig.pos.owner.dto.ShopCodeUpdateRequest;
import com.binhlaig.pos.owner.dto.ShopCodeUpdateResponse;
import com.binhlaig.pos.owner.dto.UpdateOwnerProfileRequest;
import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import com.binhlaig.pos.auth.AccountPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class OwnerService {

    private static final String UPDATED_MESSAGE =
            "Shop code updated successfully. Please sign in again.";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9-]+(?:\\.[A-Z0-9-]+)+$",
            Pattern.CASE_INSENSITIVE);

    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public OwnerProfileResponse getProfile(Authentication authentication) {
        User owner = resolveOwner(authentication);
        return toResponse(owner, resolveShop(owner));
    }

    @Transactional
    public OwnerProfileResponse updateProfile(
            Authentication authentication, UpdateOwnerProfileRequest request) {
        User owner = resolveOwner(authentication);
        Shop shop = resolveShop(owner);

        if (request.email() != null) {
            String email = trimToNull(request.email());
            email = email == null ? null : email.toLowerCase(Locale.ROOT);
            if (email != null && !EMAIL_PATTERN.matcher(email).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email must be valid");
            }
            if (email != null && userRepository.existsByEmailIgnoreCaseAndIdNot(email, owner.getId())) {
                throw conflict("Email already exists");
            }
            owner.setEmail(email);
        }
        if (request.phone() != null) {
            owner.setPhone(trimToNull(request.phone()));
        }
        if (request.shopName() != null) {
            String shopName = requiredTrimmed(request.shopName(), "Shop name must not be blank");
            owner.setShopName(shopName);
            shop.setShopName(shopName);
        }
        if (request.address() != null) {
            String address = requiredTrimmed(request.address(), "Address must not be blank");
            owner.setAddress(address);
            shop.setAddress(address);
        }

        shop.setUpdatedAt(OffsetDateTime.now());
        shopRepository.save(shop);
        try {
            userRepository.saveAndFlush(owner);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("Email already exists");
        }
        return toResponse(owner, shop);
    }

    @Transactional
    public ShopCodeUpdateResponse updateShopCode(
            Authentication authentication, ShopCodeUpdateRequest request) {
        User owner = resolveOwner(authentication);
        Shop shop = resolveShop(owner);
        String newCode = request.newShopCode().trim().toUpperCase(Locale.ROOT);

        if (!newCode.matches("^[A-Z0-9-]{3,30}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Shop code must be 3-30 characters using only letters, numbers, and hyphens");
        }
        if (newCode.equals(shop.getShopCode())) {
            return new ShopCodeUpdateResponse(UPDATED_MESSAGE, true);
        }
        if (shopRepository.existsByShopCodeIgnoreCase(newCode)) {
            throw conflict("Shop code already exists");
        }

        Long immutableShopId = owner.getShopId();
        shop.setShopCode(newCode);
        shop.setUpdatedAt(OffsetDateTime.now());
        try {
            shopRepository.saveAndFlush(shop);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("Shop code already exists");
        }

        List<User> shopUsers = userRepository.findAllByShopId(immutableShopId);
        shopUsers.forEach(user -> user.setShopCode(newCode));
        userRepository.saveAllAndFlush(shopUsers);

        updateCurrentOperationalShopCodes(immutableShopId, newCode);

        if (!immutableShopId.equals(owner.getShopId()) || !immutableShopId.equals(shop.getId())) {
            throw new IllegalStateException("Shop identity changed unexpectedly");
        }
        return new ShopCodeUpdateResponse(UPDATED_MESSAGE, true);
    }

    private void updateCurrentOperationalShopCodes(Long shopId, String newCode) {
        jdbcTemplate.update("update staff set shop_code = ? where shop_id = ?", newCode, shopId);
        jdbcTemplate.update("update products set shop_code = ? where shop_id = ?", newCode, shopId);
        jdbcTemplate.update("update restaurant_tables set shop_code = ? where shop_id = ?", newCode, shopId);
        jdbcTemplate.update("update shop_settings set shop_code = ? where shop_id = ?", newCode, shopId);
        jdbcTemplate.update("update receipt_settings set shop_code = ? where shop_id = ?", newCode, shopId);
        jdbcTemplate.update("update shop_features set shop_code = ? where shop_id = ?", newCode, shopId);
        jdbcTemplate.update("update timecard_schedules set shop_code = ? where shop_id = ?", newCode, shopId);
    }

    private User resolveOwner(Authentication authentication) {
        User owner = AccountPrincipal.require(authentication).requireUser(userRepository);
        if (owner.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only owner ADMIN users can manage the owner profile");
        }
        if (owner.getShopId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Owner has no shop");
        }
        return owner;
    }

    private Shop resolveShop(User owner) {
        return shopRepository.findById(owner.getShopId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Owner shop not found"));
    }

    private OwnerProfileResponse toResponse(User owner, Shop shop) {
        return new OwnerProfileResponse(
                owner.getId(),
                owner.getUsername(),
                owner.getEmail(),
                owner.getPhone(),
                owner.getShopId(),
                shop.getShopCode(),
                shop.getShopName(),
                shop.getAddress(),
                shop.getBusinessType());
    }

    private String requiredTrimmed(String value, String message) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        String trimmed = value == null ? null : value.trim();
        return trimmed == null || trimmed.isBlank() ? null : trimmed;
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
