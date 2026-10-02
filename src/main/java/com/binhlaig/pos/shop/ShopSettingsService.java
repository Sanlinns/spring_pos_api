package com.binhlaig.pos.shop;

import com.binhlaig.pos.shop.dto.ShopSettingsRequest;
import com.binhlaig.pos.shop.dto.ShopSettingsResponse;
import com.binhlaig.pos.auth.AccountContextService;
import com.binhlaig.pos.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class ShopSettingsService {

    private final ShopSettingsRepository shopSettingsRepository;
    private final AccountContextService accounts;

    public ShopSettingsResponse getSettings(Authentication authentication) {
        AccountContextService.Context loginUser = accounts.resolve(authentication);

        ShopSettings settings = shopSettingsRepository.findByShopId(loginUser.getShopId())
                .orElseGet(() -> createDefaultSettings(loginUser));

        return toResponse(settings);
    }

    public ShopSettingsResponse updateSettings(
            ShopSettingsRequest request,
            Authentication authentication
    ) {
        AccountContextService.Context loginUser = accounts.requireOwner(authentication);

        ShopSettings settings = shopSettingsRepository.findByShopId(loginUser.getShopId())
                .orElseGet(() -> createDefaultSettings(loginUser));

        if (request.shopName() != null) {
            settings.setShopName(request.shopName().trim());
        }

        if (request.address() != null) {
            settings.setAddress(request.address().trim());
        }

        if (request.phone() != null) {
            settings.setPhone(request.phone().trim());
        }

        if (request.currencyCode() != null && !request.currencyCode().isBlank()) {
            settings.setCurrencyCode(request.currencyCode().trim().toUpperCase());
        }

        if (request.currencySymbol() != null && !request.currencySymbol().isBlank()) {
            settings.setCurrencySymbol(request.currencySymbol().trim());
        }

        if (request.currencyDecimalDigits() != null) {
            int digits = request.currencyDecimalDigits();

            if (digits < 0 || digits > 4) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "currencyDecimalDigits must be between 0 and 4"
                );
            }

            settings.setCurrencyDecimalDigits(digits);
        }

        if (request.currencyPosition() != null && !request.currencyPosition().isBlank()) {
            String position = request.currencyPosition().trim().toUpperCase();

            if (!position.equals("BEFORE") && !position.equals("AFTER")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "currencyPosition must be BEFORE or AFTER"
                );
            }

            settings.setCurrencyPosition(position);
        }

        if (request.taxPercent() != null) {
            BigDecimal tax = request.taxPercent();

            if (tax.compareTo(BigDecimal.ZERO) < 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "taxPercent cannot be negative"
                );
            }

            settings.setTaxPercent(tax);
        }

        ShopSettings saved = shopSettingsRepository.save(settings);
        return toResponse(saved);
    }

    private ShopSettings createDefaultSettings(AccountContextService.Context user) {
        ShopSettings settings = ShopSettings.builder()
                .shopId(user.getShopId())
                .shopCode(user.getShopCode())
                .shopName(user.getShopName())
                .address(user.getAddress())
                .phone(null)
                .currencyCode("MMK")
                .currencySymbol("Ks")
                .currencyDecimalDigits(0)
                .currencyPosition("BEFORE")
                .taxPercent(BigDecimal.ZERO)
                .build();

        return shopSettingsRepository.save(settings);
    }

    private ShopSettingsResponse toResponse(ShopSettings s) {
        return new ShopSettingsResponse(
                s.getShopId(),
                s.getShopCode(),
                s.getShopName(),
                s.getAddress(),
                s.getPhone(),
                s.getCurrencyCode(),
                s.getCurrencySymbol(),
                s.getCurrencyDecimalDigits(),
                s.getCurrencyPosition(),
                s.getTaxPercent()
        );
    }
}