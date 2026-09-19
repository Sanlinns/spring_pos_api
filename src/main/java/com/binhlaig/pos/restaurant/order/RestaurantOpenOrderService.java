package com.binhlaig.pos.restaurant.order;

import com.binhlaig.pos.admin.PlanLimitService;
import com.binhlaig.pos.modules.product.Product;
import com.binhlaig.pos.modules.product.ProductRepository;
import com.binhlaig.pos.restaurant.auth.RestaurantAuthContext;
import com.binhlaig.pos.restaurant.auth.RestaurantSession;
import com.binhlaig.pos.restaurant.entity.RestaurantTable;
import com.binhlaig.pos.restaurant.entity.RestaurantTableStatus;
import com.binhlaig.pos.restaurant.entity.KitchenItemStatus;
import com.binhlaig.pos.restaurant.entity.KitchenTicket;
import com.binhlaig.pos.restaurant.entity.KitchenTicketItem;
import com.binhlaig.pos.restaurant.entity.KitchenTicketStatus;
import com.binhlaig.pos.restaurant.payment.RestaurantOrder;
import com.binhlaig.pos.restaurant.payment.RestaurantOrderItem;
import com.binhlaig.pos.restaurant.payment.RestaurantOrderRepository;
import com.binhlaig.pos.restaurant.payment.RestaurantPayment;
import com.binhlaig.pos.restaurant.payment.RestaurantPaymentRepository;
import com.binhlaig.pos.restaurant.repository.RestaurantTableRepository;
import com.binhlaig.pos.restaurant.repository.KitchenTicketRepository;
import com.binhlaig.pos.restaurant.repository.KitchenTicketItemRepository;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.RoundingMode;

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
    private final KitchenTicketRepository kitchenTicketRepository;
    private final KitchenTicketItemRepository kitchenTicketItemRepository;

    public RestaurantItemCancelResponse cancelSentItem(
            RestaurantItemCancelRequest request,
            String authorizationHeader
    ) {
        RestaurantSession session = authContext.fromAuthorizationHeader(authorizationHeader);
        planLimitService.assertCanUseRestaurant(session.shopId());
        validateCancelRequest(request);

        List<Long> ticketIds = request.getTicketIds().stream().distinct().sorted().toList();
        if (ticketIds.size() != request.getTicketIds().size()) {
            throw badRequest("ticketIds must not contain duplicates");
        }

        List<KitchenTicket> tickets = kitchenTicketRepository
                .findAllByIdsAndShopIdForUpdate(ticketIds, session.shopId());
        if (tickets.size() != ticketIds.size()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "One or more kitchen tickets do not belong to this shop");
        }

        Set<Long> tableIds = tickets.stream().map(KitchenTicket::getTableId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (tableIds.size() != 1 || tickets.stream().anyMatch(t -> t.getTableId() == null)) {
            throw conflict("Tickets must belong to the same dine-in table");
        }
        Long trustedTableId = tableIds.iterator().next();

        List<RestaurantOrder> tableOrders = orderRepository
                .findByShopAndTableForUpdate(session.shopId(), trustedTableId);
        RestaurantOrder order = tableOrders.stream()
                .filter(o -> OPEN_STATUS.equals(o.getStatus()))
                .findFirst()
                .orElseThrow(() -> conflict(tableOrders.stream().anyMatch(o -> "PAID".equals(o.getStatus()))
                        ? "Paid order items cannot be cancelled"
                        : "No cancellable open order exists for these tickets"));
        if (!"DINE_IN".equals(order.getOrderType())) {
            throw conflict("Only dine-in open order items can be cancelled from kitchen tickets");
        }

        List<KitchenTicketItem> selected = selectKitchenItems(tickets, request);
        RestaurantOrderItem orderItem = selectOrderItem(order, request);
        if (orderItem.getQuantity() < request.getQuantity()) {
            throw conflict("Open order quantity is smaller than the requested cancel quantity");
        }

        cancelKitchenQuantity(selected, request.getQuantity(), request.getReason());
        reduceOrderQuantity(order, orderItem, request.getQuantity());
        recalculateTotals(order);
        tickets.forEach(this::recalculateTicketStatus);

        if (order.getItems().isEmpty()) {
            order.setStatus("CANCELLED");
            tableRepository.findByIdAndShopId(trustedTableId, session.shopId()).ifPresent(table -> {
                table.setStatus(RestaurantTableStatus.FREE);
                tableRepository.save(table);
            });
        }

        List<Long> activeTicketIds = kitchenTicketRepository
                .findAllByShopIdAndTableId(session.shopId(), trustedTableId).stream()
                // There is no order_id on the legacy kitchen schema. Do not include tickets
                // from an older order that previously used the same table.
                .filter(t -> order.getCreatedAt() == null || t.getCreatedAt() == null
                        || !t.getCreatedAt().isBefore(order.getCreatedAt()))
                .filter(t -> t.getItems().stream().anyMatch(i -> i.getStatus() != KitchenItemStatus.CANCELLED))
                .map(KitchenTicket::getId).sorted().toList();
        return RestaurantItemCancelResponse.builder().activeTicketIds(activeTicketIds).build();
    }

    private void validateCancelRequest(RestaurantItemCancelRequest request) {
        if (request == null || request.getTicketIds() == null || request.getTicketIds().isEmpty()) {
            throw badRequest("ticketIds is required");
        }
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw badRequest("quantity must be at least 1");
        }
        if (request.getProductId() == null || request.getUnitPrice() == null) {
            throw badRequest("productId and unitPrice are required");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw badRequest("reason is required");
        }
    }

    private List<KitchenTicketItem> selectKitchenItems(
            List<KitchenTicket> tickets,
            RestaurantItemCancelRequest request
    ) {
        List<KitchenTicketItem> all = tickets.stream().flatMap(t -> t.getItems().stream()).toList();
        List<KitchenTicketItem> matches;
        boolean explicitIds = request.getKitchenItemIds() != null && !request.getKitchenItemIds().isEmpty();
        if (explicitIds) {
            Set<Long> ids = new HashSet<>(request.getKitchenItemIds());
            if (ids.size() != request.getKitchenItemIds().size()) {
                throw badRequest("kitchenItemIds must not contain duplicates");
            }
            matches = all.stream().filter(i -> ids.contains(i.getId())).toList();
            if (matches.size() != ids.size()) {
                throw conflict("A kitchenItemId is not part of the supplied tickets");
            }
            if (matches.stream().anyMatch(i -> !sameSignature(i, request))) {
                throw conflict("Kitchen item details do not match the cancellation request");
            }
        } else {
            matches = all.stream().filter(i -> sameSignature(i, request)).toList();
        }
        if (matches.isEmpty()) {
            throw conflict("No kitchen item exactly matches product, price, modifiers and kitchen note");
        }
        List<KitchenTicketItem> activeMatches = matches.stream()
                .filter(i -> i.getStatus() != KitchenItemStatus.CANCELLED).toList();
        if (activeMatches.isEmpty()) {
            throw conflict("Kitchen item is already cancelled");
        }
        if (!explicitIds && activeMatches.size() != matches.size()) {
            throw conflict("Ambiguous kitchen item match; send kitchenItemIds for the exact items");
        }
        if (explicitIds && activeMatches.size() != matches.size()) {
            throw conflict("One or more kitchen items are already cancelled");
        }
        int available = activeMatches.stream().mapToInt(KitchenTicketItem::getQuantity).sum();
        if (available < request.getQuantity()) {
            throw conflict("Requested quantity exceeds the matching sent quantity");
        }
        if (activeMatches.size() > 1 && available != request.getQuantity()) {
            throw conflict("Ambiguous kitchen item match; send kitchenItemIds for the exact items");
        }
        return activeMatches.stream().sorted(Comparator
                .comparing((KitchenTicketItem i) -> i.getTicket().getId())
                .thenComparing(KitchenTicketItem::getId)).toList();
    }

    private boolean sameSignature(KitchenTicketItem item, RestaurantItemCancelRequest request) {
        return Objects.equals(item.getMenuItemId(), request.getProductId())
                && amountsEqual(item.getUnitPrice(), request.getUnitPrice())
                && Objects.equals(normalizeJson(item.getModifiers()), normalizeJson(request.getModifiers()))
                && Objects.equals(blankToNull(item.getKitchenNote()), blankToNull(request.getKitchenNote()));
    }

    private RestaurantOrderItem selectOrderItem(RestaurantOrder order, RestaurantItemCancelRequest request) {
        if (request.getOrderItemId() != null) {
            RestaurantOrderItem item = order.getItems().stream()
                    .filter(i -> Objects.equals(i.getId(), request.getOrderItemId()))
                    .findFirst()
                    .orElseThrow(() -> conflict("orderItemId is not part of the open order"));
            if (!sameSignature(item, request)) {
                throw conflict("Open order item details do not match the cancellation request");
            }
            return item;
        }
        List<RestaurantOrderItem> matches = order.getItems().stream()
                .filter(i -> sameSignature(i, request))
                .toList();
        if (matches.size() != 1) {
            throw conflict(matches.isEmpty()
                    ? "No open order item exactly matches the kitchen item"
                    : "Ambiguous open order item match; persist a kitchen-to-order item link");
        }
        return matches.get(0);
    }

    private boolean sameSignature(RestaurantOrderItem item, RestaurantItemCancelRequest request) {
        return Objects.equals(item.getProductId(), request.getProductId())
                && amountsEqual(item.getUnitPrice(), request.getUnitPrice())
                && Objects.equals(normalizeJson(item.getModifiers()), normalizeJson(request.getModifiers()))
                && Objects.equals(blankToNull(item.getKitchenNote()), blankToNull(request.getKitchenNote()));
    }

    private void cancelKitchenQuantity(List<KitchenTicketItem> items, int requested, String reason) {
        int remaining = requested;
        for (KitchenTicketItem item : items) {
            if (remaining == 0) break;
            int cancelled = Math.min(item.getQuantity(), remaining);
            if (cancelled < item.getQuantity()) {
                KitchenTicketItem activeRemainder = KitchenTicketItem.builder()
                        .ticket(item.getTicket()).menuItemId(item.getMenuItemId()).itemName(item.getItemName())
                        .quantity(item.getQuantity() - cancelled).unitPrice(item.getUnitPrice())
                        .modifiers(item.getModifiers()).kitchenNote(item.getKitchenNote()).status(item.getStatus()).build();
                item.getTicket().getItems().add(activeRemainder);
                kitchenTicketItemRepository.save(activeRemainder);
                item.setQuantity(cancelled);
            }
            item.setStatus(KitchenItemStatus.CANCELLED);
            item.setCancelReason(reason.trim());
            remaining -= cancelled;
        }
    }

    private void reduceOrderQuantity(RestaurantOrder order, RestaurantOrderItem item, int quantity) {
        int oldQuantity = item.getQuantity();
        int newQuantity = oldQuantity - quantity;
        if (newQuantity == 0) {
            order.getItems().remove(item);
        } else {
            item.setQuantity(newQuantity);
            BigDecimal perUnitTotal = item.getTotalPrice()
                    .divide(BigDecimal.valueOf(oldQuantity), 8, RoundingMode.HALF_UP);
            item.setTotalPrice(perUnitTotal.multiply(BigDecimal.valueOf(newQuantity))
                    .setScale(2, RoundingMode.HALF_UP));
        }
    }

    private void recalculateTotals(RestaurantOrder order) {
        BigDecimal oldSubtotal = zeroIfNull(order.getSubtotal());
        BigDecimal subtotal = order.getItems().stream().map(RestaurantOrderItem::getTotalPrice)
                .map(this::zeroIfNull).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        order.setSubtotal(subtotal);
        order.setDiscount(scaleComponent(order.getDiscount(), oldSubtotal, subtotal));
        order.setTax(scaleComponent(order.getTax(), oldSubtotal, subtotal));
        order.setServiceCharge(scaleComponent(order.getServiceCharge(), oldSubtotal, subtotal));
        order.setTotal(subtotal.subtract(order.getDiscount()).add(order.getTax())
                .add(order.getServiceCharge()).setScale(2, RoundingMode.HALF_UP));
    }

    private BigDecimal scaleComponent(BigDecimal value, BigDecimal oldSubtotal, BigDecimal newSubtotal) {
        if (value == null || oldSubtotal.signum() == 0) return BigDecimal.ZERO.setScale(2);
        return value.multiply(newSubtotal).divide(oldSubtotal, 2, RoundingMode.HALF_UP);
    }

    private void recalculateTicketStatus(KitchenTicket ticket) {
        List<KitchenTicketItem> active = ticket.getItems().stream()
                .filter(i -> i.getStatus() != KitchenItemStatus.CANCELLED).toList();
        if (active.isEmpty()) ticket.setStatus(KitchenTicketStatus.CANCELLED);
        else if (active.stream().allMatch(i -> i.getStatus() == KitchenItemStatus.DONE)) ticket.setStatus(KitchenTicketStatus.DONE);
        else if (active.stream().allMatch(i -> i.getStatus() == KitchenItemStatus.READY || i.getStatus() == KitchenItemStatus.DONE)) ticket.setStatus(KitchenTicketStatus.READY);
        else if (active.stream().anyMatch(i -> i.getStatus() == KitchenItemStatus.COOKING)) ticket.setStatus(KitchenTicketStatus.COOKING);
        else ticket.setStatus(KitchenTicketStatus.NEW);
    }

    private String normalizeJson(Object value) {
        if (value == null) return null;
        try {
            var node = value instanceof String text ? objectMapper.readTree(text) : objectMapper.valueToTree(value);
            return node == null || node.isNull() || (node.isArray() && node.isEmpty()) ? null : node.toString();
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw badRequest("Invalid modifiers");
        }
    }

    private boolean amountsEqual(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

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
