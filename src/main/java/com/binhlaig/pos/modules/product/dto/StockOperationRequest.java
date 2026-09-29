package com.binhlaig.pos.modules.product.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record StockOperationRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String requestId,
        @NotNull Operation operation,
        @NotNull BigDecimal quantity,
        @Size(max = 500) String reason
) {
    public enum Operation { ADD_STOCK, STOCK_CORRECTION }
}
