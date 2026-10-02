package com.binhlaig.pos.modules.product;

import com.binhlaig.pos.admin.PlanLimitService;
import com.binhlaig.pos.modules.product.dto.StockOperationRequest;
import com.binhlaig.pos.receipt.dto.*;
import com.binhlaig.pos.receipt.repository.PosReceiptRepository;
import com.binhlaig.pos.receipt.service.PosReceiptService;
import com.binhlaig.pos.restaurant.auth.*;
import com.binhlaig.pos.restaurant.payment.*;
import com.binhlaig.pos.restaurant.repository.RestaurantTableRepository;
import com.binhlaig.pos.storage.FileStorageService;
import com.binhlaig.pos.user.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

// Explicitly opt in with a DISPOSABLE database; never reads .env or the application's DB URL.
@EnabledIfEnvironmentVariable(named = "STOCK_TEST_URL", matches = "jdbc:postgresql://.*")
@ActiveProfiles("stock-integration")
@SpringBootTest(classes = StockIntegrationTest.Config.class, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=OFF",
        "spring.datasource.username=postgres", "spring.datasource.password=",
        "spring.jpa.open-in-view=false"
})
class StockIntegrationTest {
    @Configuration(proxyBeanMethods = false) @Profile("stock-integration")
    @EnableAutoConfiguration(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
    @EntityScan("com.binhlaig.pos")
    @EnableJpaRepositories(basePackageClasses = {ProductRepository.class, PosReceiptRepository.class, RestaurantPaymentRepository.class})
    @Import({StockService.class, StockRequestService.class, ProductService.class,
            PosReceiptService.class, RestaurantPaymentService.class})
    static class Config {}

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("STOCK_TEST_URL");
        if (!url.endsWith("/stock_test")) throw new IllegalArgumentException("Use a disposable stock_test database");
        registry.add("spring.datasource.url", () -> url);
    }

    @Autowired ProductRepository products;
    @Autowired StockMovementRepository movements;
    @Autowired StockRequestRepository requests;
    @Autowired PosReceiptRepository receipts;
    @Autowired RestaurantOrderRepository orders;
    @Autowired StockService stock;
    @Autowired ProductService productService;
    @Autowired PosReceiptService pos;
    @Autowired RestaurantPaymentService restaurant;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean PlanLimitService limits;
    @MockitoBean FileStorageService storage;
    @MockitoBean com.binhlaig.pos.auth.AccountContextService accounts;
    @MockitoBean RestaurantAuthContext restaurantAuth;
    @MockitoBean RestaurantTableRepository tables;

    @BeforeEach void login() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("stock-test", "n/a"));
        when(accounts.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(context(10L));
        when(restaurantAuth.fromAuthorizationHeader("test")).thenReturn(new RestaurantSession(10L, "TEST"));
    }
    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    private Product create(String amount) {
        return new TransactionTemplate(transactions).execute(tx -> {
            Product p = Product.builder().sku(UUID.randomUUID().toString()).name("Test").productName("Test")
                    .productPrice(BigDecimal.ONE).productQuantityAmount(new BigDecimal(amount))
                    .productDiscount(BigDecimal.ZERO).shopId(10L).availableForSale(true).build();
            stock.initialize(p);
            products.saveAndFlush(p);
            stock.recordOpening(p);
            return p;
        });
    }
    private ReceiptCreateRequest sale(Product p, int qty) {
        return ReceiptCreateRequest.builder().requestId(UUID.randomUUID().toString()).staffId("test")
                .paymentMethod("CASH").items(List.of(ReceiptItemRequest.builder().productId(p.getId().toString()).qty(qty).build())).build();
    }
    private ReceiptResponse checkout(ReceiptCreateRequest request) {
        return pos.createReceipt(request, AuthenticatedUserInfo.builder().shopId(10L).build());
    }

    @Test void softDeletionMigrationRetainsExistingRowsAsActive() throws Exception {
        String migration = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/migration/V27__product_soft_deletion.sql"));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            // The temp table shadows products only on this connection and disappears at commit.
            jdbc.execute("CREATE TEMP TABLE products (id BIGINT, shop_id BIGINT, product_quantity_amount NUMERIC) ON COMMIT DROP");
            jdbc.execute("INSERT INTO products VALUES (1, 10, 15)");
            jdbc.execute(migration);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE deleted_at IS NULL AND product_quantity_amount = 15", Long.class)).isEqualTo(1L);
        });
    }

    @Test void softDeletionPreservesReceiptAndStockHistoryAndExcludesActiveQueries() throws Exception {
        Product p = create("10");
        var originalSale = sale(p, 2);
        var receipt = checkout(originalSale);
        long historyCount = movements.findAll().stream().filter(m -> m.getProductId().equals(p.getId())).count();
        long activeCount = products.countByShopId(10L);
        when(accounts.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(context(11L));
        assertThatThrownBy(() -> productService.delete(p.getId())).hasMessageContaining("404");
        assertThat(products.findById(p.getId()).orElseThrow().getDeletedAt()).isNull();
        when(accounts.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(context(10L));
        productService.delete(p.getId());
        var deletedAt = products.findById(p.getId()).orElseThrow().getDeletedAt();
        productService.delete(p.getId());
        Product archived = products.findById(p.getId()).orElseThrow();
        assertThat(archived.getDeletedAt()).isEqualTo(deletedAt);
        assertThat(archived.getProductQuantityAmount()).isEqualByComparingTo("8");
        assertThat(products.countByShopId(10L)).isEqualTo(activeCount - 1);
        assertThat(products.findByShopId(10L)).extracting(Product::getId).doesNotContain(p.getId());
        assertThat(products.searchByShopId(10L, p.getSku())).isEmpty();
        assertThat(productService.listMine(null, "all", null, null, null, null))
                .extracting(com.binhlaig.pos.modules.product.dto.ProductResponse::id).doesNotContain(p.getId());
        assertThat(movements.findAll().stream().filter(m -> m.getProductId().equals(p.getId())).count()).isEqualTo(historyCount);
        assertThat(receipts.findById(receipt.getId())).isPresent();
        assertThat(pos.getReceiptByNo(receipt.getReceiptNo(), AuthenticatedUserInfo.builder().shopId(10L).build())
                .getItems()).extracting(ReceiptItemResponse::getProductName).containsExactly("Test");
        // Replay returns the historical snapshot, while a new sale is rejected.
        assertThat(checkout(originalSale).getId()).isEqualTo(receipt.getId());
        assertThatThrownBy(() -> checkout(sale(p, 1))).hasMessageContaining("unavailable for sale");
        assertThat(productService.getById(p.getId()).remainingStock()).isEqualByComparingTo("8");
    }

    @Test void archivedProductsCannotStartRestaurantSalesButExistingOrdersComplete() throws Exception {
        Product p = create("10");
        RestaurantPaymentItemRequest item = new RestaurantPaymentItemRequest();
        item.setProductId(p.getId()); item.setQuantity(2); item.setItemName("Test");
        item.setUnitPrice(BigDecimal.ONE); item.setTotalPrice(BigDecimal.TWO);
        RestaurantPaymentRequest request = new RestaurantPaymentRequest();
        request.setRequestId(UUID.randomUUID().toString()); request.setOrderType("TAKEAWAY");
        request.setPaymentMethod("CASH"); request.setTotal(BigDecimal.TWO); request.setItems(List.of(item));
        RestaurantOrder order = new TransactionTemplate(transactions).execute(tx -> {
            RestaurantOrder existing = RestaurantOrder.builder().orderNo(UUID.randomUUID().toString())
                    .shopId(10L).shopCode("TEST").tableId(778L).status("OPEN").orderType("TAKEAWAY").total(BigDecimal.TWO).build();
            existing.addItem(RestaurantOrderItem.builder().productId(p.getId()).itemName("Test")
                    .quantity(2).unitPrice(BigDecimal.ONE).totalPrice(BigDecimal.TWO).build());
            return orders.saveAndFlush(existing);
        });
        productService.delete(p.getId());
        assertThatThrownBy(() -> restaurant.createPayment(request, "test")).hasMessageContaining("unavailable for sale");
        request.setTableId(778L);
        when(tables.findByIdAndShopId(778L, 10L)).thenReturn(Optional.of(
                com.binhlaig.pos.restaurant.entity.RestaurantTable.builder().id(778L).tableNo("TEST").shopId(10L).build()));
        item.setQuantity(3);
        assertThatThrownBy(() -> restaurant.createPayment(request, "test")).hasMessageContaining("unavailable for sale");
        item.setQuantity(2);
        var payment = restaurant.createPayment(request, "test");
        assertThat(payment.getOrderId()).isEqualTo(order.getId());
        assertThat(products.findById(p.getId()).orElseThrow().getProductQuantityAmount()).isEqualByComparingTo("8");
        assertThat(restaurant.getShopPayments("test")).extracting(RestaurantPaymentListResponse::getOrderNo).contains(order.getOrderNo());
    }

    @Test void createApiInitializesTrackingAndEditRejectsOverwrite() throws Exception {
        var response = productService.create(UUID.randomUUID().toString(), "Test", BigDecimal.ONE,
                new BigDecimal("100"), null, null, ProductType.OTHER, BigDecimal.ZERO, null, null);
        assertThat(response.totalStock()).isEqualByComparingTo("100");
        assertThat(response.stockTrackingBasis()).isEqualTo("FROM_CREATION");
        assertThatThrownBy(() -> productService.update(response.id(), null, null, null, BigDecimal.TEN,
                null, null, null, null, null, null)).hasMessageContaining("cannot change stock");
        var edited = productService.update(response.id(), null, "New name", null, null,
                null, null, null, null, null, null);
        assertThat(edited.remainingStock()).isEqualByComparingTo("100");
    }

    @Test void posReplenishmentCorrectionAndReplayPreserveEquation() {
        Product p = create("100");
        ReceiptCreateRequest request = sale(p, 30);
        var first = checkout(request);
        assertThat(checkout(request).getId()).isEqualTo(first.getId());
        String addId = UUID.randomUUID().toString();
        var add = new StockOperationRequest(addId, StockOperationRequest.Operation.ADD_STOCK, new BigDecimal("50"), "Delivery");
        var result = productService.operateStock(p.getId(), add);
        assertThat(result.totalStock()).isEqualByComparingTo("150");
        assertThat(result.soldQuantity()).isEqualByComparingTo("30");
        assertThat(result.remainingStock()).isEqualByComparingTo("120");
        productService.operateStock(p.getId(), add);
        var corrected = productService.operateStock(p.getId(), new StockOperationRequest(UUID.randomUUID().toString(),
                StockOperationRequest.Operation.STOCK_CORRECTION, new BigDecimal("-2"), "Count"));
        assertThat(corrected.totalStock()).isEqualByComparingTo("150");
        assertThat(corrected.soldQuantity()).isEqualByComparingTo("30");
        assertThat(corrected.remainingStock()).isEqualByComparingTo("118");
        assertThat(corrected.stockCorrection()).isEqualByComparingTo("-2");
        assertThat(movements.findAll().stream().filter(m -> m.getProductId().equals(p.getId())).count()).isEqualTo(4);
        request.getItems().getFirst().setQty(31);
        assertThatThrownBy(() -> checkout(request)).hasMessageContaining("409");
    }

    @Test void failedLaterLineRollsBackEarlierStockMovementAndRequest() {
        Product first = create("10");
        Product second = create("1");
        ReceiptCreateRequest request = sale(first, 3);
        request.setItems(List.of(request.getItems().getFirst(), ReceiptItemRequest.builder().productId(second.getId().toString()).qty(2).build()));
        long receiptCount = receipts.count();
        assertThatThrownBy(() -> checkout(request)).isInstanceOf(IllegalArgumentException.class);
        assertThat(products.findById(first.getId()).orElseThrow().getProductQuantityAmount()).isEqualByComparingTo("10");
        assertThat(products.findById(first.getId()).orElseThrow().getSoldQuantity()).isZero();
        assertThat(movements.findAll().stream().filter(m -> m.getProductId().equals(first.getId())).count()).isEqualTo(1);
        assertThat(requests.findByShopIdAndRequestKey(10L, request.getRequestId())).isEmpty();
        assertThat(receipts.count()).isEqualTo(receiptCount);
    }

    @Test void simultaneousRetriesProduceOneReceiptAndOneSale() throws Exception {
        Product p = create("10");
        ReceiptCreateRequest request = sale(p, 4);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch go = new CountDownLatch(1);
            Callable<Long> task = () -> { go.await(); return checkout(request).getId(); };
            Future<Long> a = executor.submit(task), b = executor.submit(task);
            go.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
        }
        assertThat(products.findById(p.getId()).orElseThrow().getProductQuantityAmount()).isEqualByComparingTo("6");
        assertThat(products.findById(p.getId()).orElseThrow().getSoldQuantity()).isEqualByComparingTo("4");
        assertThat(movements.findAll().stream().filter(m -> m.getProductId().equals(p.getId())).count()).isEqualTo(2);
    }

    @Test void competingSalesCannotOversell() throws Exception {
        Product p = create("5");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch go = new CountDownLatch(1);
            Callable<Boolean> task = () -> { go.await(); try { checkout(sale(p, 4)); return true; } catch (IllegalArgumentException e) { return false; } };
            Future<Boolean> a = executor.submit(task), b = executor.submit(task);
            go.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(products.findById(p.getId()).orElseThrow().getProductQuantityAmount()).isEqualByComparingTo("1");
        assertThat(products.findById(p.getId()).orElseThrow().getSoldQuantity()).isEqualByComparingTo("4");
    }

    @Test void restaurantSaleReplaysOnceAndSharesRequestNamespaceWithPos() {
        Product p = create("10");
        RestaurantPaymentItemRequest item = new RestaurantPaymentItemRequest();
        item.setProductId(p.getId()); item.setQuantity(3); item.setItemName("Test");
        item.setUnitPrice(BigDecimal.ONE); item.setTotalPrice(new BigDecimal("3"));
        RestaurantPaymentRequest request = new RestaurantPaymentRequest();
        request.setRequestId(UUID.randomUUID().toString()); request.setOrderType("TAKEAWAY");
        request.setPaymentMethod("CASH"); request.setTotal(new BigDecimal("3")); request.setItems(List.of(item));
        var first = restaurant.createPayment(request, "test");
        assertThat(restaurant.createPayment(request, "test").getPaymentId()).isEqualTo(first.getPaymentId());
        ReceiptCreateRequest duplicate = sale(p, 3); duplicate.setRequestId(request.getRequestId());
        assertThatThrownBy(() -> checkout(duplicate)).hasMessageContaining("409");
        assertThat(products.findById(p.getId()).orElseThrow().getSoldQuantity()).isEqualByComparingTo("3");
        assertThat(products.findById(p.getId()).orElseThrow().getProductQuantityAmount()).isEqualByComparingTo("7");
        assertThat(movements.findAll().stream().filter(m -> m.getProductId().equals(p.getId())).count()).isEqualTo(2);
    }

    @Test void stockOperationsCannotAccessAnotherShopAndRequireRequestId() {
        Product p = create("10");
        when(accounts.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(context(11L));
        assertThatThrownBy(() -> productService.operateStock(p.getId(), new StockOperationRequest("wrong-shop",
                StockOperationRequest.Operation.ADD_STOCK, BigDecimal.ONE, null))).hasMessageContaining("404");
        var sale = sale(p, 1); sale.setRequestId(null);
        assertThatThrownBy(() -> checkout(sale)).hasMessageContaining("requestId is required");
        assertThat(products.findById(p.getId()).orElseThrow().getProductQuantityAmount()).isEqualByComparingTo("10");
    }

    @Test void distinctSimultaneousCheckoutsBothCommitWithoutLostUpdates() throws Exception {
        Product p = create("10");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch go = new CountDownLatch(1);
            Callable<Long> task = () -> { go.await(); return checkout(sale(p, 2)).getId(); };
            Future<Long> a = executor.submit(task), b = executor.submit(task);
            go.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isNotEqualTo(b.get(20, TimeUnit.SECONDS));
        }
        assertThat(products.findById(p.getId()).orElseThrow().getSoldQuantity()).isEqualByComparingTo("4");
        assertThat(products.findById(p.getId()).orElseThrow().getProductQuantityAmount()).isEqualByComparingTo("6");
        assertThat(movements.findAll().stream().filter(m -> m.getProductId().equals(p.getId())).count()).isEqualTo(3);
    }

    @Test void restaurantFailureRollsBackEarlierSaleAndCanRetrySameRequest() {
        Product p = create("5");
        RestaurantPaymentItemRequest item = new RestaurantPaymentItemRequest();
        item.setProductId(p.getId()); item.setQuantity(2); item.setItemName("Test");
        item.setUnitPrice(BigDecimal.ONE); item.setTotalPrice(BigDecimal.TWO);
        RestaurantPaymentItemRequest missing = new RestaurantPaymentItemRequest();
        missing.setProductId(Long.MAX_VALUE); missing.setQuantity(1);
        RestaurantPaymentRequest request = new RestaurantPaymentRequest();
        request.setRequestId(UUID.randomUUID().toString()); request.setOrderType("TAKEAWAY");
        request.setPaymentMethod("CASH"); request.setTotal(BigDecimal.TWO); request.setItems(List.of(item, missing));
        assertThatThrownBy(() -> restaurant.createPayment(request, "test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(products.findById(p.getId()).orElseThrow().getSoldQuantity()).isZero();
        assertThat(products.findById(p.getId()).orElseThrow().getProductQuantityAmount()).isEqualByComparingTo("5");
        assertThat(movements.findAll().stream().filter(m -> m.getProductId().equals(p.getId())).count()).isEqualTo(1);
        assertThat(requests.findByShopIdAndRequestKey(10L, request.getRequestId())).isEmpty();
        request.setItems(List.of(item));
        restaurant.createPayment(request, "test");
        assertThat(products.findById(p.getId()).orElseThrow().getProductQuantityAmount()).isEqualByComparingTo("3");
    }
    private com.binhlaig.pos.auth.AccountContextService.Context context(Long shopId) {
        return new com.binhlaig.pos.auth.AccountContextService.Context(
                new com.binhlaig.pos.auth.AccountPrincipal(com.binhlaig.pos.auth.AccountPrincipal.AccountType.USER,1L,shopId,java.util.UUID.randomUUID()),
                com.binhlaig.pos.admin.Shop.builder().id(shopId).shopCode("TEST").build(),"stock-test","stock-test","ADMIN");
    }}
