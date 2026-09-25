package com.binhlaig.pos.owner.dto;

public record OwnerProfileResponse(
        Long id,
        String username,
        String email,
        String phone,
        Long shopId,
        String shopCode,
        String shopName,
        String address,
        String businessType) {
}
