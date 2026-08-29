//
//
//
//package com.binhlaig.pos.receipt.controller;
//
//import com.binhlaig.pos.receipt.dto.AuthenticatedUserInfo;
//import com.binhlaig.pos.receipt.dto.ReceiptCreateRequest;
//import com.binhlaig.pos.receipt.dto.ReceiptListResponse;
//import com.binhlaig.pos.receipt.dto.ReceiptResponse;
//import com.binhlaig.pos.receipt.service.PosReceiptService;
//import com.binhlaig.pos.user.User;
//import com.binhlaig.pos.user.UserRepository;
//import lombok.RequiredArgsConstructor;
//import org.springframework.security.core.Authentication;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.List;
//
//@RestController
//@RequestMapping("/api/pos/receipts")
//@RequiredArgsConstructor
//public class PosReceiptController {
//
//    private final PosReceiptService receiptService;
//    private final UserRepository userRepository;
//
//    @PostMapping
//    public ReceiptResponse createReceipt(
//            @RequestBody ReceiptCreateRequest request,
//            Authentication authentication
//    ) {
//        AuthenticatedUserInfo userInfo = getLoginUserInfo(authentication);
//        return receiptService.createReceipt(request, userInfo);
//    }
//
//    @GetMapping("/my")
//    public List<ReceiptListResponse> getMyReceipts(Authentication authentication) {
//        AuthenticatedUserInfo userInfo = getLoginUserInfo(authentication);
//        return receiptService.getMyReceipts(userInfo);
//    }
//
//    private AuthenticatedUserInfo getLoginUserInfo(Authentication authentication) {
//        if (
//                authentication == null ||
//                        authentication.getName() == null ||
//                        authentication.getName().isBlank()
//        ) {
//            throw new RuntimeException("Login user not found.");
//        }
//
//        String username = authentication.getName();
//
//        User user = userRepository.findByUsername(username)
//                .orElseThrow(() -> new RuntimeException("User not found: " + username));
//
//        return AuthenticatedUserInfo.builder()
//                .userId(user.getId())
//                .username(user.getUsername())
//                .name(user.getUsername())
//                .role(user.getRole() == null ? null : user.getRole().name())
//                .shopId(user.getShopId())
//                .shopCode(user.getShopCode())
//                .shopName(user.getShopName())
//                .shopAddress(user.getAddress())
//                .build();
//    }
//}








package com.binhlaig.pos.receipt.controller;

import com.binhlaig.pos.receipt.dto.AuthenticatedUserInfo;
import com.binhlaig.pos.receipt.dto.ReceiptCreateRequest;
import com.binhlaig.pos.receipt.dto.ReceiptListResponse;
import com.binhlaig.pos.receipt.dto.ReceiptResponse;
import com.binhlaig.pos.receipt.service.PosReceiptService;
import com.binhlaig.pos.shopfeature.FeatureKey;
import com.binhlaig.pos.shopfeature.ShopFeatureService;
import com.binhlaig.pos.user.User;
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
    private final UserRepository userRepository;
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
        if (
                authentication == null ||
                        authentication.getName() == null ||
                        authentication.getName().isBlank()
        ) {
            throw new RuntimeException("Login user not found.");
        }

        String username = authentication.getName();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        return AuthenticatedUserInfo.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .name(user.getUsername())
                .role(user.getRole() == null ? null : user.getRole().name())
                .shopId(user.getShopId())
                .shopCode(user.getShopCode())
                .shopName(user.getShopName())
                .shopAddress(user.getAddress())
                .build();
    }
}
