package com.binhlaig.pos.restaurant.payment;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
@lombok.extern.jackson.Jacksonized
public class RestaurantPaymentResponse {

    private Long orderId;
    private String orderNo;
    private Long paymentId;
    private String paymentNo;
    private String paymentMethod;
    private BigDecimal amount;
    private String status;
}
