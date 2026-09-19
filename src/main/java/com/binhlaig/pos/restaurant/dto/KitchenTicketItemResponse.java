package com.binhlaig.pos.restaurant.dto;

import com.binhlaig.pos.restaurant.entity.KitchenTicketItem;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class KitchenTicketItemResponse {

    private Long id;
    private Long ticketId;
    private Long menuItemId;
    private String itemName;
    private Integer quantity;
    private BigDecimal unitPrice;
    private String modifiers;
    private String kitchenNote;
    private String status;
    private String cancelReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static KitchenTicketItemResponse from(KitchenTicketItem item) {
        return KitchenTicketItemResponse.builder()
                .id(item.getId())
                .ticketId(item.getTicket() == null ? null : item.getTicket().getId())
                .menuItemId(item.getMenuItemId())
                .itemName(item.getItemName())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .modifiers(item.getModifiers())
                .kitchenNote(item.getKitchenNote())
                .status(item.getStatus() == null ? null : item.getStatus().name())
                .cancelReason(item.getCancelReason())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }
}
