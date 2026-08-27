package com.binhlaig.pos.restaurant.order;

import com.binhlaig.pos.restaurant.payment.RestaurantOrder;
import com.binhlaig.pos.restaurant.payment.RestaurantPayment;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class RestaurantOpenOrderResponse {

    private Long id;
    private Long orderId;
    private String orderNo;
    private String orderType;
    private Long tableId;
    private String tableNo;
    private String staffId;
    private String staffName;
    private BigDecimal subtotal;
    private BigDecimal serviceCharge;
    private BigDecimal tax;
    private BigDecimal discount;
    private BigDecimal total;
    private String status;
    private String paymentNo;
    private String paymentMethod;
    private BigDecimal cashReceived;
    private BigDecimal changeAmount;
    private LocalDateTime paidAt;
    private Long shopId;
    private String shopCode;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<RestaurantOpenOrderItemResponse> items;

    public static RestaurantOpenOrderResponse from(
            RestaurantOrder order,
            List<RestaurantOpenOrderItemResponse> items,
            RestaurantPayment payment
    ) {
        return RestaurantOpenOrderResponse.builder()
                .id(order.getId())
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .orderType(order.getOrderType())
                .tableId(order.getTableId())
                .tableNo(order.getTableNo())
                .staffId(order.getStaffId())
                .staffName(order.getStaffName())
                .subtotal(order.getSubtotal())
                .serviceCharge(order.getServiceCharge())
                .tax(order.getTax())
                .discount(order.getDiscount())
                .total(order.getTotal())
                .status(order.getStatus())
                .paymentNo(payment == null ? null : payment.getPaymentNo())
                .paymentMethod(payment == null ? null : payment.getPaymentMethod())
                .cashReceived(payment == null ? null : payment.getCashReceived())
                .changeAmount(payment == null ? null : payment.getChangeAmount())
                .paidAt(payment == null ? null : payment.getCreatedAt())
                .shopId(order.getShopId())
                .shopCode(order.getShopCode())
                .note(order.getNote())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .items(items)
                .build();
    }

    public static RestaurantOpenOrderResponse from(
            RestaurantOrder order,
            List<RestaurantOpenOrderItemResponse> items
    ) {
        return from(order, items, null);
    }
}
