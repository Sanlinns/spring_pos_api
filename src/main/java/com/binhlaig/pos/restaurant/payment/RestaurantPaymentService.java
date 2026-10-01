package com.binhlaig.pos.restaurant.payment;

import com.binhlaig.pos.admin.PlanLimitService;
import com.binhlaig.pos.modules.product.Product;
import com.binhlaig.pos.modules.product.ProductRepository;
import com.binhlaig.pos.restaurant.auth.RestaurantAuthContext;
import com.binhlaig.pos.restaurant.auth.RestaurantSession;
import com.binhlaig.pos.restaurant.entity.RestaurantTable;
import com.binhlaig.pos.restaurant.entity.RestaurantTableStatus;
import com.binhlaig.pos.restaurant.repository.RestaurantTableRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional
public class RestaurantPaymentService {

    private final RestaurantOrderRepository orderRepository;
    private final RestaurantPaymentRepository paymentRepository;
    private final RestaurantTableRepository tableRepository;
    private final RestaurantAuthContext authContext;
    private final ObjectMapper objectMapper;
    private final ProductRepository productRepository;
    private final PlanLimitService planLimitService;
    private final com.binhlaig.pos.modules.product.StockService stockService;
    private final com.binhlaig.pos.modules.product.StockRequestService stockRequests;

    public RestaurantPaymentResponse createPayment(RestaurantPaymentRequest request, String authorizationHeader) {
        RestaurantSession session = authContext.fromAuthorizationHeader(authorizationHeader);
        if (request == null) throw new IllegalArgumentException("Payment request required");
        return stockRequests.execute(session.shopId(), request.getRequestId(), "RESTAURANT",
                request, RestaurantPaymentResponse.class, () -> createPaymentOnce(request, session));
    }

    private RestaurantPaymentResponse createPaymentOnce(RestaurantPaymentRequest request, RestaurantSession session) {
        planLimitService.assertCanUseRestaurant(session.shopId());
        validate(request);

        RestaurantTable table = null;
        String tableNo = blankToNull(request.getTableNo());
        if (request.getTableId() != null) {
            table = tableRepository.findByIdAndShopId(request.getTableId(), session.shopId())
                    .orElseThrow(() -> new RuntimeException("Restaurant table not found"));
            tableNo = table.getTableNo();
        }

        RestaurantOrder order = findOpenOrder(request, session)
                .orElseGet(() -> RestaurantOrder.builder()
                        .orderNo("RO-" + java.util.UUID.randomUUID())
                        .shopId(session.shopId())
                        .shopCode(session.shopCode())
                        .build());

        reduceStock(request.getItems(), session.shopId(), request.getRequestId(), order);

        applyPaymentRequest(order, request, tableNo, session);
        order.replaceItems(request.getItems().stream()
                .map(this::toOrderItem)
                .toList());
        RestaurantOrder savedOrder = orderRepository.save(order);

        RestaurantPayment payment = RestaurantPayment.builder()
                .order(savedOrder)
                .paymentNo("RP-" + java.util.UUID.randomUUID())
                .paymentMethod(required(request.getPaymentMethod(), "paymentMethod is required"))
                .amount(requiredAmount(request.getTotal(), "total is required"))
                .cashReceived(request.getCashReceived())
                .changeAmount(request.getChangeAmount())
                .status("PAID")
                .note(blankToNull(request.getNote()))
                .shopId(session.shopId())
                .shopCode(session.shopCode())
                .build();

        RestaurantPayment savedPayment = paymentRepository.save(payment);

        if (isDineIn(request.getOrderType()) && table != null) {
            table.setStatus(RestaurantTableStatus.FREE);
            tableRepository.save(table);
        }

        return RestaurantPaymentResponse.builder()
                .orderId(savedOrder.getId())
                .orderNo(savedOrder.getOrderNo())
                .paymentId(savedPayment.getId())
                .paymentNo(savedPayment.getPaymentNo())
                .paymentMethod(savedPayment.getPaymentMethod())
                .amount(savedPayment.getAmount())
                .status(savedPayment.getStatus())
                .build();
    }

    @Transactional(readOnly = true)
    public List<RestaurantPaymentListResponse> getShopPayments(String authorizationHeader) {
        RestaurantSession session = authContext.fromAuthorizationHeader(authorizationHeader);
        planLimitService.assertCanUseRestaurant(session.shopId());
        return paymentRepository
                .findByShopIdOrderByCreatedAtDesc(session.shopId())
                .stream()
                .map(this::toListResponse)
                .toList();
    }

    private RestaurantPaymentListResponse toListResponse(RestaurantPayment payment) {
        BigDecimal amount = zeroIfNull(payment.getAmount());
        return RestaurantPaymentListResponse.builder()
                .id(payment.getId())
                .paymentNo(payment.getPaymentNo())
                .orderNo(payment.getOrder() == null ? null : payment.getOrder().getOrderNo())
                .paidAt(payment.getCreatedAt())
                .createdAt(payment.getCreatedAt())
                .total(amount)
                .totalAmount(amount)
                .shopId(payment.getShopId())
                .shopCode(payment.getShopCode())
                .build();
    }

    private java.util.Optional<RestaurantOrder> findOpenOrder(
            RestaurantPaymentRequest request,
            RestaurantSession session
    ) {
        if (request.getTableId() == null) {
            return java.util.Optional.empty();
        }
        return orderRepository.findFirstByShopIdAndTableIdAndStatusOrderByCreatedAtDesc(
                session.shopId(),
                request.getTableId(),
                "OPEN"
        );
    }

    private void applyPaymentRequest(
            RestaurantOrder order,
            RestaurantPaymentRequest request,
            String tableNo,
            RestaurantSession session
    ) {
        order.setOrderType(normalizeOrderType(request.getOrderType()));
        order.setTableId(request.getTableId());
        order.setTableNo(tableNo);
        String staffId = blankToNull(request.getStaffId());
        String staffName = blankToNull(request.getStaffName());
        if (staffId != null || order.getId() == null) {
            order.setStaffId(staffId);
        }
        if (staffName != null || order.getId() == null) {
            order.setStaffName(staffName);
        }
        order.setSubtotal(zeroIfNull(request.getSubtotal()));
        order.setServiceCharge(zeroIfNull(request.getServiceCharge()));
        order.setTax(zeroIfNull(request.getTax()));
        order.setDiscount(zeroIfNull(request.getDiscount()));
        order.setTotal(requiredAmount(request.getTotal(), "total is required"));
        order.setStatus("PAID");
        order.setNote(blankToNull(request.getNote()));
        order.setShopId(session.shopId());
        order.setShopCode(session.shopCode());
    }

    private RestaurantOrderItem toOrderItem(RestaurantPaymentItemRequest request) {
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

    private void reduceStock(List<RestaurantPaymentItemRequest> items, Long shopId, String requestId, RestaurantOrder order) {
        // Existing orders may complete, but archived products cannot be added or increased.
        java.util.Map<Long, Integer> existingQuantities = new java.util.HashMap<>();
        if (order.getId() != null) {
            order.getItems().forEach(item -> existingQuantities.merge(item.getProductId(), item.getQuantity(), Integer::sum));
        }
        int line = 0;
        for (RestaurantPaymentItemRequest item : items) {
            if (item == null) {
                throw new IllegalArgumentException("Restaurant order item is required");
            }

            Long productId = item.getProductId();
            if (productId == null) {
                throw new IllegalArgumentException("productId is required");
            }

            int cartQty = item.getQuantity() == null ? 1 : item.getQuantity();
            if (cartQty <= 0) {
                throw new IllegalArgumentException("Qty must be greater than 0.");
            }

            Product product = productRepository
                    .findByIdAndShopIdForUpdate(productId, shopId)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found in this shop."));

            if (product.getDeletedAt() != null) {
                int remaining = existingQuantities.getOrDefault(productId, 0) - cartQty;
                if (remaining < 0) {
                    throw new IllegalStateException(product.getProductName() + " is currently unavailable for sale.");
                }
                existingQuantities.put(productId, remaining);
            }

            BigDecimal available = zeroIfNull(product.getProductQuantityAmount());
            BigDecimal requested = BigDecimal.valueOf(cartQty);
            if (available.compareTo(requested) < 0) {
                String productName = product.getProductName() == null || product.getProductName().isBlank()
                        ? required(item.getItemName(), "itemName is required")
                        : product.getProductName();
                throw new IllegalArgumentException(
                        productName
                                + " stock မလုံလောက်ပါ။ Available: "
                                + formatQuantity(available)
                                + ", Cart: "
                                + cartQty
                );
            }

            stockService.sell(product, requested, "RESTAURANT:" + requestId + ":" + line++);
            productRepository.save(product);
        }
    }

    private void validate(RestaurantPaymentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Payment request is required");
        }
        normalizeOrderType(request.getOrderType());
        required(request.getPaymentMethod(), "paymentMethod is required");
        requiredAmount(request.getTotal(), "total is required");
        List<RestaurantPaymentItemRequest> items = request.getItems();
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("At least one restaurant order item is required");
        }
    }

    private boolean isDineIn(String orderType) {
        return "DINE_IN".equals(normalizeOrderType(orderType));
    }

    private String toJson(List<String> modifiers) {
        if (modifiers == null || modifiers.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(modifiers);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid modifiers", ex);
        }
    }

    private BigDecimal requiredAmount(BigDecimal value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String required(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
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
            throw new IllegalArgumentException("Invalid orderType: " + value);
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

    private String formatQuantity(BigDecimal value) {
        return zeroIfNull(value).stripTrailingZeros().toPlainString();
    }
}
