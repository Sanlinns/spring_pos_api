package com.binhlaig.pos.restaurant.order;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class RestaurantItemCancelRequest {

    @NotEmpty(message = "ticketIds is required")
    private List<@NotNull Long> ticketIds;

    /** Preferred, unambiguous contract. Legacy clients may omit this field. */
    @JsonAlias({"kitchenItemIds", "kitchen_item_ids"})
    private List<Long> kitchenItemIds;

    /** Preferred companion to kitchenItemIds when identical order lines exist. */
    @JsonAlias({"orderItemId", "order_item_id"})
    private Long orderItemId;

    private Long tableId;

    @JsonAlias({"productId", "product_id", "menuItemId", "menu_item_id"})
    @NotNull(message = "productId is required")
    private Long productId;

    @JsonAlias({"unitPrice", "unit_price"})
    @NotNull(message = "unitPrice is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "unitPrice cannot be negative")
    private BigDecimal unitPrice;

    private Object modifiers;

    @JsonAlias({"kitchenNote", "kitchen_note"})
    private String kitchenNote;

    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be at least 1")
    private Integer quantity;

    @NotBlank(message = "reason is required")
    private String reason;
}
