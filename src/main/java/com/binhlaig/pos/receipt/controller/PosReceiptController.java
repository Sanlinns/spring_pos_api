package com.binhlaig.pos.receipt.controller;

import com.binhlaig.pos.receipt.dto.AuthenticatedUserInfo;
import com.binhlaig.pos.receipt.dto.ReceiptCreateRequest;
import com.binhlaig.pos.receipt.dto.ReceiptListResponse;
import com.binhlaig.pos.receipt.dto.ReceiptResponse;
import com.binhlaig.pos.receipt.service.PosReceiptService;
import com.binhlaig.pos.shopfeature.FeatureKey;
import com.binhlaig.pos.shopfeature.ShopFeatureService;
import com.binhlaig.pos.auth.AccountContextService;
import com.binhlaig.pos.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/pos/receipts")
@RequiredArgsConstructor
public class PosReceiptController {

    private final PosReceiptService receiptService;
    private final AccountContextService accounts;
    private final ShopFeatureService shopFeatureService;

    @PostMapping
    public ReceiptResponse createReceipt(
            @RequestBody ReceiptCreateRequest request,
            Authentication authentication
    ) {
        AuthenticatedUserInfo userInfo = getLoginUserInfo(authentication);
        shopFeatureService.requireFeature(userInfo.getShopId(), userInfo.getShopCode(), FeatureKey.POS_REGISTER);
        return receiptService.createReceipt(request, userInfo);
    }

    @GetMapping("/my")
    public List<ReceiptListResponse> getMyReceipts(Authentication authentication) {
        AuthenticatedUserInfo userInfo = getLoginUserInfo(authentication);
        shopFeatureService.requireFeature(userInfo.getShopId(), userInfo.getShopCode(), FeatureKey.RECEIPTS);
        return receiptService.getMyReceipts(userInfo);
    }

    @GetMapping("/shop")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public List<ReceiptListResponse> getShopReceipts(Authentication authentication) {
        AuthenticatedUserInfo userInfo = getLoginUserInfo(authentication);
        shopFeatureService.requireFeature(userInfo.getShopId(), userInfo.getShopCode(), FeatureKey.RECEIPTS);
        return receiptService.getShopReceipts(userInfo);
    }

    @GetMapping("/{receiptNo}")
    public ReceiptListResponse getReceiptByNo(
            @PathVariable String receiptNo,
            Authentication authentication
    ) {
        AuthenticatedUserInfo userInfo = getLoginUserInfo(authentication);
        shopFeatureService.requireFeature(userInfo.getShopId(), userInfo.getShopCode(), FeatureKey.RECEIPTS);
        return receiptService.getReceiptByNo(receiptNo, userInfo);
    }

    private AuthenticatedUserInfo getLoginUserInfo(Authentication authentication) {
        var user = accounts.resolve(authentication);

        return AuthenticatedUserInfo.builder()
                .userId(user.getUserId())
                .staffAccountId(user.getStaffId())
                .username(user.getUsername())
                .name(user.getName())
                .role(user.getRole())
                .shopId(user.getShopId())
                .shopCode(user.getShopCode())
                .shopName(user.getShopName())
                .shopAddress(user.getAddress())
                .build();
    }
}
