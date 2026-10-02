package com.binhlaig.pos.shopfeature;

import com.binhlaig.pos.shopfeature.dto.ShopFeatureResponse;
import com.binhlaig.pos.auth.AccountContextService;
import com.binhlaig.pos.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shop/features")
@RequiredArgsConstructor
public class ShopFeatureController {

    private final ShopFeatureService shopFeatureService;
    private final AccountContextService accounts;

    @GetMapping("/my")
    public ShopFeatureResponse getMyFeatures(Authentication authentication) {
        var user = accounts.resolve(authentication);
        return shopFeatureService.getOrCreateForCurrentShop(user.getShopId(), user.getShopCode());
    }

}
