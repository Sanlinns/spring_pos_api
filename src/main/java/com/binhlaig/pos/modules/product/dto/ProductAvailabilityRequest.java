package com.binhlaig.pos.modules.product.dto;

import jakarta.validation.constraints.NotNull;

public record ProductAvailabilityRequest(
        @NotNull(message = "availableForSale is required")
        Boolean availableForSale
) {}
