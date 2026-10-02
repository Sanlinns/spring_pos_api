package com.binhlaig.pos.receipt.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptListResponse {
    private Long id;
    private String receiptNo;

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

    private Long shopId;
    private String shopCode;
    private String shopName;
    private String shopAddress;

    private Long createdByUserId;

    private Long createdByStaffId;

    private String createdByUsername;
    private String createdByName;
    private String createdByRole;

    private String status;
    private LocalDateTime createdAt;

    private List<ReceiptItemResponse> items;
}