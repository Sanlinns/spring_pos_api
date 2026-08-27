package com.binhlaig.pos.restaurant.order;

import com.binhlaig.pos.restaurant.payment.RestaurantOrderItem;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
public class RestaurantOpenOrderItemResponse {

    private Long id;
    private Long productId;
    private Long menuItemId;
    private String itemName;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
    private List<String> modifiers;
    private String kitchenNote;

    public static RestaurantOpenOrderItemResponse from(RestaurantOrderItem item, List<String> modifiers) {
        return RestaurantOpenOrderItemResponse.builder()
                .id(item.getId())
                .productId(item.getProductId())
                .menuItemId(item.getProductId())
                .itemName(item.getItemName())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .totalPrice(item.getTotalPrice())
                .modifiers(modifiers)
                .kitchenNote(item.getKitchenNote() == null ? "" : item.getKitchenNote())
                .build();
    }
}
