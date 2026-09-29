package com.binhlaig.pos.receipt.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptCreateRequest {
    private String requestId;
    private String staffId;
    private String staffName;
    private String staffRole;

    private String paymentMethod;

    private BigDecimal subtotal;
    private BigDecimal taxAmount;
    private BigDecimal discountPercent;
    private BigDecimal grandTotal;
    private BigDecimal cashGiven;
    private BigDecimal changeAmount;

    private List<ReceiptItemRequest> items;
}
