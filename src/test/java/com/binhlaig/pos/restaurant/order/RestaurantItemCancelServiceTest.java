package com.binhlaig.pos.restaurant.order;

import com.binhlaig.pos.admin.PlanLimitService;
import com.binhlaig.pos.modules.product.ProductRepository;
import com.binhlaig.pos.restaurant.auth.RestaurantAuthContext;
import com.binhlaig.pos.restaurant.auth.RestaurantSession;
import com.binhlaig.pos.restaurant.entity.*;
import com.binhlaig.pos.restaurant.payment.*;
import com.binhlaig.pos.restaurant.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RestaurantItemCancelServiceTest {
    private static final long SHOP = 10L;
    private static final String AUTH = "Bearer token";

    @Mock RestaurantOrderRepository orderRepository;
    @Mock RestaurantPaymentRepository paymentRepository;
    @Mock RestaurantTableRepository tableRepository;
    @Mock RestaurantAuthContext authContext;
    @Mock PlanLimitService planLimitService;
    @Mock ProductRepository productRepository;
    @Mock KitchenTicketRepository ticketRepository;
    @Mock KitchenTicketItemRepository ticketItemRepository;
    private RestaurantOpenOrderService service;

    @BeforeEach
    void setUp() {
        service = new RestaurantOpenOrderService(orderRepository, paymentRepository, tableRepository,
                authContext, new ObjectMapper(), planLimitService, productRepository,
                ticketRepository, ticketItemRepository);
        when(authContext.fromAuthorizationHeader(AUTH)).thenReturn(new RestaurantSession(SHOP, "S10"));
    }

    @Test
    void cancellingOneOfTwoItemsKeepsTheOtherVisibleAndActive() {
        KitchenTicket ticket = ticket(101L, SHOP, item(1L, 45L, 1), item(2L, 46L, 1));
        RestaurantOrder order = order("OPEN", orderItem(11L, 45L, 1), orderItem(12L, 46L, 1));
        stub(ticket, order);

        RestaurantItemCancelResponse response = service.cancelSentItem(request(List.of(101L), 45L, 1), AUTH);

        assertThat(ticket.getItems()).hasSize(2);
        assertThat(ticket.getItems().get(0).getStatus()).isEqualTo(KitchenItemStatus.CANCELLED);
        assertThat(ticket.getItems().get(1).getStatus()).isEqualTo(KitchenItemStatus.NEW);
        assertThat(ticket.getStatus()).isEqualTo(KitchenTicketStatus.NEW);
        assertThat(response.getActiveTicketIds()).containsExactly(101L);
        assertThat(order.getItems()).extracting(RestaurantOrderItem::getProductId).containsExactly(46L);
    }

    @Test
    void rejectsTicketFromAnotherShop() {
        when(ticketRepository.findAllByIdsAndShopIdForUpdate(List.of(101L), SHOP)).thenReturn(List.of());
        assertThatThrownBy(() -> service.cancelSentItem(request(List.of(101L), 45L, 1), AUTH))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(orderRepository);
    }

    @Test
    void rejectsPaidOrder() {
        KitchenTicket ticket = ticket(101L, SHOP, item(1L, 45L, 1));
        when(ticketRepository.findAllByIdsAndShopIdForUpdate(List.of(101L), SHOP)).thenReturn(List.of(ticket));
        when(orderRepository.findByShopAndTableForUpdate(SHOP, 2L)).thenReturn(List.of(order("PAID", orderItem(1L, 45L, 1))));
        assertThatThrownBy(() -> service.cancelSentItem(request(List.of(101L), 45L, 1), AUTH))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Paid order");
    }

    @Test
    void duplicateRequestDoesNotCancelAgain() {
        KitchenTicket ticket = ticket(101L, SHOP, item(1L, 45L, 1));
        ticket.getItems().get(0).setStatus(KitchenItemStatus.CANCELLED);
        RestaurantOrder order = order("OPEN", orderItem(1L, 45L, 1));
        when(ticketRepository.findAllByIdsAndShopIdForUpdate(List.of(101L), SHOP)).thenReturn(List.of(ticket));
        when(orderRepository.findByShopAndTableForUpdate(SHOP, 2L)).thenReturn(List.of(order));
        assertThatThrownBy(() -> service.cancelSentItem(request(List.of(101L), 45L, 1), AUTH))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("already cancelled");
        assertThat(order.getItems().get(0).getQuantity()).isEqualTo(1);
    }

    @Test
    void cancelsExactQuantitySplitAcrossTickets() {
        KitchenTicket first = ticket(101L, SHOP, item(1L, 45L, 1));
        KitchenTicket second = ticket(102L, SHOP, item(2L, 45L, 2));
        RestaurantOrder order = order("OPEN", orderItem(1L, 45L, 3), orderItem(2L, 46L, 1));
        when(ticketRepository.findAllByIdsAndShopIdForUpdate(List.of(101L, 102L), SHOP)).thenReturn(List.of(first, second));
        when(orderRepository.findByShopAndTableForUpdate(SHOP, 2L)).thenReturn(List.of(order));
        when(ticketRepository.findAllByShopIdAndTableId(SHOP, 2L)).thenReturn(List.of(first, second));

        service.cancelSentItem(request(List.of(101L, 102L), 45L, 3), AUTH);

        assertThat(first.getItems().get(0).getStatus()).isEqualTo(KitchenItemStatus.CANCELLED);
        assertThat(second.getItems().get(0).getStatus()).isEqualTo(KitchenItemStatus.CANCELLED);
        assertThat(order.getItems()).extracting(RestaurantOrderItem::getProductId).containsExactly(46L);
    }

    @Test
    void rejectsQuantityLargerThanSentQuantityWithoutChangingOrder() {
        KitchenTicket ticket = ticket(101L, SHOP, item(1L, 45L, 1));
        RestaurantOrder order = order("OPEN", orderItem(1L, 45L, 2));
        when(ticketRepository.findAllByIdsAndShopIdForUpdate(List.of(101L), SHOP)).thenReturn(List.of(ticket));
        when(orderRepository.findByShopAndTableForUpdate(SHOP, 2L)).thenReturn(List.of(order));

        assertThatThrownBy(() -> service.cancelSentItem(request(List.of(101L), 45L, 2), AUTH))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exceeds the matching sent quantity");
        assertThat(order.getItems().get(0).getQuantity()).isEqualTo(2);
        assertThat(ticket.getItems().get(0).getStatus()).isEqualTo(KitchenItemStatus.NEW);
    }

    @Test
    void orderItemIdDisambiguatesIdenticalOpenOrderLines() {
        KitchenTicket ticket = ticket(101L, SHOP, item(1L, 45L, 1));
        RestaurantOrderItem first = orderItem(11L, 45L, 1);
        RestaurantOrderItem second = orderItem(12L, 45L, 1);
        RestaurantOrder order = order("OPEN", first, second);
        stub(ticket, order);
        RestaurantItemCancelRequest request = request(List.of(101L), 45L, 1);
        request.setKitchenItemIds(List.of(1L));
        request.setOrderItemId(12L);

        service.cancelSentItem(request, AUTH);

        assertThat(order.getItems()).containsExactly(first);
    }

    private void stub(KitchenTicket ticket, RestaurantOrder order) {
        when(ticketRepository.findAllByIdsAndShopIdForUpdate(List.of(ticket.getId()), SHOP)).thenReturn(List.of(ticket));
        when(orderRepository.findByShopAndTableForUpdate(SHOP, 2L)).thenReturn(List.of(order));
        when(ticketRepository.findAllByShopIdAndTableId(SHOP, 2L)).thenReturn(List.of(ticket));
    }

    private RestaurantItemCancelRequest request(List<Long> ticketIds, long productId, int quantity) {
        RestaurantItemCancelRequest request = new RestaurantItemCancelRequest();
        request.setTicketIds(ticketIds); request.setProductId(productId);
        request.setUnitPrice(new BigDecimal("5000")); request.setModifiers(List.of());
        request.setKitchenNote(""); request.setQuantity(quantity); request.setReason("CUSTOMER_REQUEST");
        return request;
    }

    private KitchenTicket ticket(long id, long shop, KitchenTicketItem... items) {
        KitchenTicket ticket = KitchenTicket.builder().id(id).ticketNo("KT-" + id).orderType("DINE_IN")
                .tableId(2L).status(KitchenTicketStatus.NEW).priority("NORMAL").shopId(shop).shopCode("S10").build();
        for (KitchenTicketItem item : items) ticket.addItem(item);
        return ticket;
    }

    private KitchenTicketItem item(long id, long productId, int quantity) {
        return KitchenTicketItem.builder().id(id).menuItemId(productId).itemName("Item " + productId)
                .quantity(quantity).unitPrice(new BigDecimal("5000")).status(KitchenItemStatus.NEW).build();
    }

    private RestaurantOrder order(String status, RestaurantOrderItem... items) {
        RestaurantOrder order = RestaurantOrder.builder().id(20L).orderNo("RO-20").orderType("DINE_IN")
                .tableId(2L).status(status).subtotal(new BigDecimal("20000")).discount(BigDecimal.ZERO)
                .tax(BigDecimal.ZERO).serviceCharge(BigDecimal.ZERO).total(new BigDecimal("20000"))
                .shopId(SHOP).shopCode("S10").build();
        for (RestaurantOrderItem item : items) order.addItem(item);
        return order;
    }

    private RestaurantOrderItem orderItem(long id, long productId, int quantity) {
        BigDecimal price = new BigDecimal("5000");
        return RestaurantOrderItem.builder().id(id).productId(productId).itemName("Item " + productId)
                .quantity(quantity).unitPrice(price).totalPrice(price.multiply(BigDecimal.valueOf(quantity))).build();
    }
}
