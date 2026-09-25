package com.binhlaig.pos.admin.dto;

public record AdminShopRegisterResponse(
        String message,
        String username,
        String email,
        String phone,
        String role,
        Long shopId,
        String shopCode,
        String shopName,
        String address
) {
}
