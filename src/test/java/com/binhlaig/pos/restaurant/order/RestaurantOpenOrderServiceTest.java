package com.binhlaig.pos.restaurant.order;

import com.binhlaig.pos.admin.PlanLimitService;
import com.binhlaig.pos.restaurant.auth.RestaurantAuthContext;
import com.binhlaig.pos.restaurant.auth.RestaurantSession;
import com.binhlaig.pos.restaurant.payment.RestaurantOrder;
import com.binhlaig.pos.restaurant.payment.RestaurantOrderItem;
import com.binhlaig.pos.restaurant.payment.RestaurantOrderRepository;
import com.binhlaig.pos.restaurant.payment.RestaurantPayment;
import com.binhlaig.pos.restaurant.payment.RestaurantPaymentRepository;
import com.binhlaig.pos.restaurant.repository.RestaurantTableRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestaurantOpenOrderServiceTest {

    private static final Long SHOP_ID = 2246L;
    private static final String SHOP_CODE = "SHP-34Z";
    private static final String AUTHORIZATION = "Bearer token";

    @Mock private RestaurantOrderRepository orderRepository;
    @Mock private RestaurantPaymentRepository paymentRepository;
    @Mock private RestaurantTableRepository tableRepository;
    @Mock private RestaurantAuthContext authContext;
    @Mock private PlanLimitService planLimitService;

    private RestaurantOpenOrderService service;

    @BeforeEach
    void setUp() {
        service = new RestaurantOpenOrderService(
                orderRepository,
                paymentRepository,
                tableRepository,
                authContext,
                new ObjectMapper(),
                planLimitService
        );
        when(authContext.fromAuthorizationHeader(AUTHORIZATION))
                .thenReturn(new RestaurantSession(SHOP_ID, SHOP_CODE));
    }

    @Test
    void paidOrderIncludesPaymentStaffAndItems() {
        RestaurantOrder order = order(1L, "PAID", SHOP_ID, SHOP_CODE);
        RestaurantPayment payment = payment(order, SHOP_ID, SHOP_CODE, "RP-20260827204437");
        when(orderRepository.findShopOrdersWithItems(SHOP_ID, SHOP_CODE)).thenReturn(List.of(order));
        when(paymentRepository.findShopPaymentsForOrders(SHOP_ID, SHOP_CODE, "PAID", List.of(1L)))
                .thenReturn(List.of(payment));

        RestaurantOpenOrderResponse response = service.getOrders(AUTHORIZATION).get(0);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getStatus()).isEqualTo("PAID");
        assertThat(response.getPaymentMethod()).isEqualTo("CASH");
        assertThat(response.getPaymentNo()).isEqualTo("RP-20260827204437");
        assertThat(response.getStaffId()).isEqualTo("360026");
        assertThat(response.getStaffName()).isEqualTo("angel");
        assertThat(response.getCashReceived()).isEqualByComparingTo("10000");
        assertThat(response.getChangeAmount()).isEqualByComparingTo("4960");
        assertThat(response.getPaidAt()).isEqualTo(payment.getCreatedAt());
        assertThat(response.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo(1L);
            assertThat(item.getMenuItemId()).isEqualTo(1L);
            assertThat(item.getModifiers()).isEmpty();
            assertThat(item.getKitchenNote()).isEmpty();
        });
    }

    @Test
    void unpaidOrderHasNullPaymentFields() {
        RestaurantOrder order = order(2L, "OPEN", SHOP_ID, SHOP_CODE);
        when(orderRepository.findShopOrdersWithItems(SHOP_ID, SHOP_CODE)).thenReturn(List.of(order));
        when(paymentRepository.findShopPaymentsForOrders(SHOP_ID, SHOP_CODE, "PAID", List.of(2L)))
                .thenReturn(List.of());

        RestaurantOpenOrderResponse response = service.getOrders(AUTHORIZATION).get(0);

        assertThat(response.getPaymentNo()).isNull();
        assertThat(response.getPaymentMethod()).isNull();
        assertThat(response.getCashReceived()).isNull();
        assertThat(response.getChangeAmount()).isNull();
        assertThat(response.getPaidAt()).isNull();
    }

    @Test
    void ignoresPaymentThatDoesNotBelongToAuthenticatedShop() {
        RestaurantOrder order = order(3L, "PAID", SHOP_ID, SHOP_CODE);
        RestaurantPayment foreignPayment = payment(order, 9999L, "OTHER", "RP-FOREIGN");
        when(orderRepository.findShopOrdersWithItems(SHOP_ID, SHOP_CODE)).thenReturn(List.of(order));
        when(paymentRepository.findShopPaymentsForOrders(SHOP_ID, SHOP_CODE, "PAID", List.of(3L)))
                .thenReturn(List.of(foreignPayment));

        RestaurantOpenOrderResponse response = service.getOrders(AUTHORIZATION).get(0);

        assertThat(response.getPaymentNo()).isNull();
        assertThat(response.getPaymentMethod()).isNull();
    }

    private RestaurantOrder order(Long id, String status, Long shopId, String shopCode) {
        RestaurantOrder order = RestaurantOrder.builder()
                .id(id)
                .orderNo("RO-20260827204150")
                .orderType("DINE_IN")
                .tableId(2L)
                .tableNo("T-02")
                .staffId("360026")
                .staffName("angel")
                .subtotal(new BigDecimal("4800"))
                .serviceCharge(new BigDecimal("240"))
                .tax(BigDecimal.ZERO)
                .discount(BigDecimal.ZERO)
                .total(new BigDecimal("5040"))
                .status(status)
                .shopId(shopId)
                .shopCode(shopCode)
                .build();
        order.addItem(RestaurantOrderItem.builder()
                .id(1L)
                .productId(1L)
                .itemName("Item name")
                .quantity(1)
                .unitPrice(new BigDecimal("4800"))
                .totalPrice(new BigDecimal("4800"))
                .build());
        return order;
    }

    private RestaurantPayment payment(
            RestaurantOrder order,
            Long shopId,
            String shopCode,
            String paymentNo
    ) {
        return RestaurantPayment.builder()
                .id(1L)
                .order(order)
                .paymentNo(paymentNo)
                .paymentMethod("CASH")
                .amount(new BigDecimal("5040"))
                .cashReceived(new BigDecimal("10000"))
                .changeAmount(new BigDecimal("4960"))
                .status("PAID")
                .shopId(shopId)
                .shopCode(shopCode)
                .createdAt(LocalDateTime.of(2026, 8, 27, 20, 44, 37))
                .build();
    }
}
