package com.binhlaig.pos.owner.dto;

import jakarta.validation.constraints.Size;

public record UpdateOwnerProfileRequest(
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        @Size(max = 30, message = "Phone must be at most 30 characters")
        String phone,

        @Size(max = 180, message = "Shop name must be at most 180 characters")
        String shopName,

        String address) {
}
