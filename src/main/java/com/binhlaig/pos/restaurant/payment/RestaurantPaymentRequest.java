package com.binhlaig.pos.restaurant.payment;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class RestaurantPaymentRequest {
    private String requestId;

    @JsonAlias({"orderType", "order_type"})
    @NotBlank(message = "orderType is required")
    private String orderType;

    @JsonAlias({"tableId", "table_id"})
    private Long tableId;

    @JsonAlias({"tableNo", "table_no"})
    private String tableNo;

    @JsonAlias({"staffId", "staff_id"})
    private String staffId;

    @JsonAlias({"staffName", "staff_name"})
    private String staffName;

    @JsonAlias({"paymentMethod", "payment_method"})
    private String paymentMethod;

    private BigDecimal subtotal;

    @JsonAlias({"serviceCharge", "service_charge"})
    private BigDecimal serviceCharge;

    private BigDecimal tax;
    private BigDecimal discount;
    private BigDecimal total;

    @JsonAlias({"serviceChargeRate", "service_charge_rate"})
    private BigDecimal serviceChargeRate;

    @JsonAlias({"serviceChargeRatePercent", "service_charge_rate_percent"})
    private BigDecimal serviceChargeRatePercent;

    @JsonAlias({"taxRate", "tax_rate"})
    private BigDecimal taxRate;

    @JsonAlias({"taxRatePercent", "tax_rate_percent"})
    private BigDecimal taxRatePercent;

    @JsonAlias({"cashReceived", "cash_received"})
    private BigDecimal cashReceived;

    @JsonAlias({"changeAmount", "change_amount"})
    private BigDecimal changeAmount;

    private String note;
    private List<RestaurantPaymentItemRequest> items;
}
