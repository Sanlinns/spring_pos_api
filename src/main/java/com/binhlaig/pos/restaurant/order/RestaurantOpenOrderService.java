package com.binhlaig.pos.restaurant.order;

import com.binhlaig.pos.admin.PlanLimitService;
import com.binhlaig.pos.modules.product.Product;
import com.binhlaig.pos.modules.product.ProductRepository;
import com.binhlaig.pos.restaurant.auth.RestaurantAuthContext;
import com.binhlaig.pos.restaurant.auth.RestaurantSession;
import com.binhlaig.pos.restaurant.entity.RestaurantTable;
import com.binhlaig.pos.restaurant.entity.RestaurantTableStatus;
import com.binhlaig.pos.restaurant.payment.RestaurantOrder;
import com.binhlaig.pos.restaurant.payment.RestaurantOrderItem;
import com.binhlaig.pos.restaurant.payment.RestaurantOrderRepository;
import com.binhlaig.pos.restaurant.payment.RestaurantPayment;
import com.binhlaig.pos.restaurant.payment.RestaurantPaymentRepository;
import com.binhlaig.pos.restaurant.repository.RestaurantTableRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional
public class RestaurantOpenOrderService {

    private static final String OPEN_STATUS = "OPEN";
    private static final DateTimeFormatter ORDER_NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final RestaurantOrderRepository orderRepository;
    private final RestaurantPaymentRepository paymentRepository;
    private final RestaurantTableRepository tableRepository;
    private final RestaurantAuthContext authContext;
    private final ObjectMapper objectMapper;
    private final PlanLimitService planLimitService;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<RestaurantOpenOrderResponse> getOrders(String authorizationHeader) {
        RestaurantSession session = authContext.fromAuthorizationHeader(authorizationHeader);
        planLimitService.assertCanUseRestaurant(session.shopId());

        List<RestaurantOrder> orders = orderRepository.findShopOrdersWithItems(
                session.shopId(), session.shopCode());
        if (orders.isEmpty()) {
            return List.of();
        }

        List<Long> orderIds = orders.stream().map(RestaurantOrder::getId).toList();
        Map<Long, RestaurantPayment> latestPaidPaymentByOrder = paymentRepository
                .findShopPaymentsForOrders(session.shopId(), session.shopCode(), "PAID", orderIds)
                .stream()
                .filter(payment -> belongsToShop(payment, session))
                .collect(Collectors.toMap(
                        payment -> payment.getOrder().getId(),
                        Function.identity(),
                        (latest, ignoredOlder) -> latest
                ));

        return orders.stream()
                .map(order -> toResponse(order, latestPaidPaymentByOrder.get(order.getId())))
                .toList();
    }

    private boolean belongsToShop(RestaurantPayment payment, RestaurantSession session) {
        RestaurantOrder paymentOrder = payment.getOrder();
        return Objects.equals(payment.getShopId(), session.shopId())
                && Objects.equals(payment.getShopCode(), session.shopCode())
                && paymentOrder != null
                && Objects.equals(paymentOrder.getShopId(), session.shopId())
                && Objects.equals(paymentOrder.getShopCode(), session.shopCode());
    }

    @Transactional(readOnly = true)
    public RestaurantOpenOrderResponse getOpenOrderByTable(Long tableId, String authorizationHeader) {
        RestaurantSession session = authContext.fromAuthorizationHeader(authorizationHeader);
        planLimitService.assertCanUseTableOrder(session.shopId());
        RestaurantOrder order = orderRepository.findOpenByTableIdWithItems(session.shopId(), tableId, OPEN_STATUS)
                .orElse(null);
        return order == null ? null : toResponse(order);
    }

    public RestaurantOpenOrderResponse createOrUpdateOpenOrder(
            RestaurantOpenOrderRequest request,
            String authorizationHeader
    ) {
        RestaurantSession session = authContext.fromAuthorizationHeader(authorizationHeader);
        planLimitService.assertCanUseRestaurant(session.shopId());
        planLimitService.assertCanUseTableOrder(session.shopId());
        validate(request);
        validateProductsAvailable(request.getItems(), session.shopId());

        RestaurantTable table = tableRepository.findByIdAndShopId(request.getTableId(), session.shopId())
                .orElseThrow(() -> new RuntimeException("Restaurant table not found"));

        RestaurantOrder order = orderRepository
                .findFirstByShopIdAndTableIdAndStatusOrderByCreatedAtDesc(session.shopId(), request.getTableId(), OPEN_STATUS)
                .orElseGet(() -> RestaurantOrder.builder()
                        .orderNo(generateOrderNo())
                        .shopId(session.shopId())
                        .shopCode(session.shopCode())
                        .build());

        applyRequest(order, request, table.getTableNo(), session);
        order.replaceItems(request.getItems().stream()
                .map(this::toOrderItem)
                .toList());

        RestaurantOrder savedOrder = orderRepository.save(order);
        table.setStatus(RestaurantTableStatus.BUSY);
        tableRepository.save(table);

        return toResponse(savedOrder);
    }

    private void applyRequest(
            RestaurantOrder order,
            RestaurantOpenOrderRequest request,
            String tableNo,
            RestaurantSession session
    ) {
        order.setOrderType(normalizeOrderType(request.getOrderType()));
        order.setTableId(request.getTableId());
        order.setTableNo(tableNo);
        order.setStaffId(blankToNull(request.getStaffId()));
        order.setStaffName(blankToNull(request.getStaffName()));
        order.setSubtotal(zeroIfNull(request.getSubtotal()));
        order.setServiceCharge(zeroIfNull(request.getServiceCharge()));
        order.setTax(zeroIfNull(request.getTax()));
        order.setDiscount(zeroIfNull(request.getDiscount()));
        order.setTotal(requiredAmount(request.getTotal(), "total is required"));
        order.setStatus(OPEN_STATUS);
        order.setNote(blankToNull(request.getNote()));
        order.setShopId(session.shopId());
        order.setShopCode(session.shopCode());
    }

    private RestaurantOrderItem toOrderItem(RestaurantOpenOrderItemRequest request) {
        return RestaurantOrderItem.builder()
                .productId(request.getProductId())
                .itemName(required(request.getItemName(), "itemName is required"))
                .quantity(request.getQuantity() == null ? 1 : request.getQuantity())
                .unitPrice(requiredAmount(request.getUnitPrice(), "unitPrice is required"))
                .totalPrice(requiredAmount(request.getTotalPrice(), "totalPrice is required"))
                .modifiers(toJson(request.getModifiers()))
                .kitchenNote(blankToNull(request.getKitchenNote()))
                .build();
    }

    private RestaurantOpenOrderResponse toResponse(RestaurantOrder order) {
        return toResponse(order, null);
    }

    private void validateProductsAvailable(List<RestaurantOpenOrderItemRequest> items, Long shopId) {
        for (RestaurantOpenOrderItemRequest item : items) {
            if (item.getProductId() == null) {
                throw new IllegalArgumentException("productId is required");
            }
            Product product = productRepository.findByIdAndShopId(item.getProductId(), shopId)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found in this shop."));
            if (!Boolean.TRUE.equals(product.getAvailableForSale())) {
                throw new IllegalStateException(
                        product.getProductName() + " is currently unavailable for sale.");
            }
            if (zeroIfNull(product.getProductQuantityAmount()).compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(product.getProductName() + " is out of stock.");
            }
        }
    }

    private RestaurantOpenOrderResponse toResponse(RestaurantOrder order, RestaurantPayment payment) {
        List<RestaurantOpenOrderItemResponse> items = order.getItems().stream()
                .map(item -> RestaurantOpenOrderItemResponse.from(item, fromJson(item.getModifiers())))
                .toList();
        return RestaurantOpenOrderResponse.from(order, items, payment);
    }

    private void validate(RestaurantOpenOrderRequest request) {
        if (request == null) {
            throw new RuntimeException("Open order request is required");
        }
        normalizeOrderType(request.getOrderType());
        if (request.getTableId() == null) {
            throw new RuntimeException("tableId is required");
        }
        requiredAmount(request.getTotal(), "total is required");
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new RuntimeException("At least one restaurant order item is required");
        }
    }

    private String generateOrderNo() {
        return "RO-" + LocalDateTime.now().format(ORDER_NO_FORMAT);
    }

    private String toJson(List<String> modifiers) {
        if (modifiers == null || modifiers.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(modifiers);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Invalid modifiers", ex);
        }
    }

    private List<String> fromJson(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException ex) {
            return List.of(value);
        }
    }

    private BigDecimal requiredAmount(BigDecimal value, String message) {
        if (value == null) {
            throw new RuntimeException(message);
        }
        return value;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String required(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new RuntimeException(message);
        }
        return value.trim();
    }

    private String normalizeOrderType(String value) {
        String orderType = value;
        if (orderType == null || orderType.isBlank()) {
            orderType = "DINE_IN";
        }

        String normalized = orderType.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("DINE_IN")
                && !normalized.equals("TAKEAWAY")
                && !normalized.equals("DELIVERY")) {
            throw new RuntimeException("Invalid orderType: " + value);
        }

        return normalized;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
