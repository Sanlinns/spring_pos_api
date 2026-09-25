package com.binhlaig.pos.owner;

import com.binhlaig.pos.owner.dto.OwnerProfileResponse;
import com.binhlaig.pos.owner.dto.ShopCodeUpdateRequest;
import com.binhlaig.pos.owner.dto.ShopCodeUpdateResponse;
import com.binhlaig.pos.owner.dto.UpdateOwnerProfileRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/owner")
public class OwnerController {

    private final OwnerService ownerService;

    @GetMapping("/profile")
    public OwnerProfileResponse getProfile(Authentication authentication) {
        return ownerService.getProfile(authentication);
    }

    @PutMapping("/profile")
    public OwnerProfileResponse updateProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateOwnerProfileRequest request) {
        return ownerService.updateProfile(authentication, request);
    }

    @PutMapping("/shop-code")
    public ShopCodeUpdateResponse updateShopCode(
            Authentication authentication,
            @Valid @RequestBody ShopCodeUpdateRequest request) {
        return ownerService.updateShopCode(authentication, request);
    }
}
