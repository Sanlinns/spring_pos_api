package com.binhlaig.pos.owner.dto;

import jakarta.validation.constraints.NotBlank;

public record ShopCodeUpdateRequest(
        @NotBlank(message = "New shop code is required")
        String newShopCode) {
}
