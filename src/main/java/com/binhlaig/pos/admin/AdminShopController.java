package com.binhlaig.pos.admin;

import com.binhlaig.pos.admin.dto.AdminShopCheckResponse;
import com.binhlaig.pos.admin.dto.AdminShopRegisterResponse;
import com.binhlaig.pos.admin.dto.AdminShopResponse;
import com.binhlaig.pos.admin.dto.ChangeShopPlanRequest;
import com.binhlaig.pos.admin.dto.ExtendShopRequest;
import com.binhlaig.pos.admin.dto.SuspendShopRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/shops")
public class AdminShopController {

    private final AdminShopService adminShopService;

    @GetMapping
    public List<AdminShopResponse> getShops() {
        return adminShopService.getShops();
    }

    @GetMapping("/{shopId}")
    public AdminShopResponse getShop(@PathVariable Long shopId) {
        return adminShopService.getShop(shopId);
    }

    @GetMapping("/check")
    public AdminShopCheckResponse checkShop(
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) String shopCode
    ) {
        return adminShopService.checkShop(shopId, shopCode);
    }

    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdminShopRegisterResponse registerShop(
            @RequestParam String username,
            @RequestParam String email,
            @RequestParam(required = false) String phone,
            @RequestParam String password,
            @RequestParam(required = false, defaultValue = "ADMIN") String role,
            @RequestParam Long shopId,
            @RequestParam String shopCode,
            @RequestParam String shopName,
            @RequestParam String address,
            @RequestParam String businessType,
            @RequestParam(required = false, defaultValue = "TRIAL") String status,
            @RequestParam(required = false, defaultValue = "TRIAL") String subscriptionPlan,
            @RequestParam(required = false, defaultValue = "14") Integer subscriptionDays,
            @RequestPart(required = false) MultipartFile image
    ) throws Exception {
        return adminShopService.registerShop(
                username,
                email,
                phone,
                password,
                role,
                shopId,
                shopCode,
                shopName,
                address,
                businessType,
                status,
                subscriptionPlan,
                subscriptionDays,
                image
        );
    }

    @PatchMapping("/{shopId}/activate")
    public AdminShopResponse activateShop(@PathVariable Long shopId) {
        return adminShopService.activateShop(shopId);
    }

    @PatchMapping("/{shopId}/suspend")
    public AdminShopResponse suspendShop(
            @PathVariable Long shopId,
            @RequestBody(required = false) SuspendShopRequest request
    ) {
        return adminShopService.suspendShop(shopId, request);
    }

    @PatchMapping("/{shopId}/expire")
    public AdminShopResponse expireShop(@PathVariable Long shopId) {
        return adminShopService.expireShop(shopId);
    }

    @PatchMapping("/{shopId}/extend")
    public AdminShopResponse extendShop(
            @PathVariable Long shopId,
            @RequestBody(required = false) ExtendShopRequest request
    ) {
        return adminShopService.extendShop(shopId, request);
    }

    @PatchMapping("/{shopId}/plan")
    public AdminShopResponse changePlan(
            @PathVariable Long shopId,
            @RequestBody(required = false) ChangeShopPlanRequest request
    ) {
        return adminShopService.changePlan(shopId, request);
    }
}
