package com.binhlaig.pos.auth.session;

import com.binhlaig.pos.admin.*;
import com.binhlaig.pos.auth.*;
import com.binhlaig.pos.auth.Role;
import com.binhlaig.pos.auth.dto.*;
import com.binhlaig.pos.auth.email.PasswordResetEmailService;
import com.binhlaig.pos.auth.jwt.JwtAuthFilter;
import com.binhlaig.pos.auth.passwordreset.PasswordResetTokenRepository;
import com.binhlaig.pos.config.SecurityConfig;
import com.binhlaig.pos.common.GlobalExceptionHandler;
import com.binhlaig.pos.modules.product.ProductRepository;
import com.binhlaig.pos.receipt.repository.PosReceiptRepository;
import com.binhlaig.pos.shopfeature.ShopFeatureRepository;
import com.binhlaig.pos.staff.entity.Staff;
import com.binhlaig.pos.staff.repository.StaffRepository;
import com.binhlaig.pos.storage.FileStorageService;
import com.binhlaig.pos.user.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Only runs against an explicitly supplied disposable session_test database. */
@EnabledIfEnvironmentVariable(named="SESSION_TEST_URL", matches="jdbc:postgresql://.*")
@ActiveProfiles("session-integration")
@SpringBootTest(classes=SessionIntegrationTest.Config.class, properties={
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false",
        "spring.datasource.username=postgres", "spring.datasource.password=session-test-only",
        "app.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "app.jwt.expiration-ms=900000",
        "app.cors.allowed-origins=http://localhost:3000", "app.session.cookie-secure=false",
        "spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=OFF"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionIntegrationTest {
    @Configuration(proxyBeanMethods=false) @Profile("session-integration")
    @EnableAutoConfiguration
    @EntityScan("com.binhlaig.pos")
    @EnableJpaRepositories(basePackageClasses={UserRepository.class, StaffRepository.class, ShopRepository.class, PasswordResetTokenRepository.class, ProductRepository.class, PosReceiptRepository.class, com.binhlaig.pos.shop.ShopSettingsRepository.class, com.binhlaig.pos.receiptsetting.repository.ReceiptSettingRepository.class})
    @Import({SessionService.class, SessionStore.class, SessionCookies.class, JwtService.class, AuthService.class,
            PasswordResetService.class, SecurityUserDetailsService.class, JwtAuthFilter.class, SecurityConfig.class,
            AuthController.class, DeviceController.class, GlobalExceptionHandler.class, Probe.class, PlanLimitService.class,
            com.binhlaig.pos.owner.OwnerController.class, com.binhlaig.pos.owner.OwnerService.class,
            com.binhlaig.pos.me.MeController.class, com.binhlaig.pos.me.MeService.class,
            com.binhlaig.pos.receipt.controller.PosReceiptController.class, AccountContextService.class, com.binhlaig.pos.receipt.service.PosReceiptService.class, com.binhlaig.pos.modules.product.ProductController.class, com.binhlaig.pos.modules.product.ProductService.class, com.binhlaig.pos.modules.product.StockService.class, com.binhlaig.pos.modules.product.StockRequestService.class, com.binhlaig.pos.shop.ShopSettingsController.class, com.binhlaig.pos.shop.ShopSettingsService.class, com.binhlaig.pos.shopfeature.ShopFeatureController.class, com.binhlaig.pos.receiptsetting.controller.ReceiptSettingController.class, com.binhlaig.pos.receiptsetting.service.ReceiptSettingService.class})
    static class Config {}
    @RestController static class Probe {
        @GetMapping({"/api/owner/session-test", "/api/pos/session-test", "/api/admin/session-test"})
        String protectedResource() { return "ok"; }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("SESSION_TEST_URL");
        if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/session_test"))
            throw new IllegalArgumentException("Use a local disposable session_test database");
        r.add("spring.datasource.url", () -> url);
    }
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired SessionService sessions;
    @Autowired SessionStore store;
    @Autowired JwtService jwt;
    @Autowired PasswordResetService resets;
    @Autowired UserRepository users;
    @Autowired StaffRepository staff;
    @Autowired PasswordEncoder encoder;
    @Autowired PlanLimitService plans;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean FileStorageService storage;
    @MockitoBean PasswordResetEmailService emails;
    @MockitoBean ShopFeatureRepository features;
    @Autowired ProductRepository products;
    @Autowired PosReceiptRepository receipts;
    
    @MockitoBean com.binhlaig.pos.shopfeature.ShopFeatureService featureService;
    User owner;
    Staff employee;
    static final String DEVICE = "installation-00000001";

    @BeforeAll void migrate() {
        jdbc.execute("drop table if exists consumed_refresh_tokens, login_sessions, flyway_schema_history cascade");
        // Execute actual additive migrations against isolated prerequisite tables.
        products.saveAndFlush(com.binhlaig.pos.modules.product.Product.builder().sku("legacy").name("Legacy").productName("Legacy").productPrice(java.math.BigDecimal.ONE).productQuantityAmount(java.math.BigDecimal.ONE).productDiscount(java.math.BigDecimal.ZERO).createdByUserId(777L).createdByUsername("legacy-owner").openingBalance(java.math.BigDecimal.ONE).totalStock(java.math.BigDecimal.ONE).soldQuantity(java.math.BigDecimal.ZERO).stockCorrection(java.math.BigDecimal.ZERO).historicalSoldQuantity(java.math.BigDecimal.ZERO).stockTrackingStartedAt(java.time.Instant.now()).stockTrackingBasis("OPENING_BALANCE").build());
        jdbc.execute("alter table products drop column created_by_staff_id");
        jdbc.execute("alter table pos_receipts drop column created_by_staff_id");
        jdbc.update("insert into pos_receipts(receipt_no,staff_id,payment_method,subtotal,tax_amount,discount_percent,grand_total,cash_given,change_amount,status,created_by_user_id,created_by_username) values ('legacy','legacy','CASH',1,0,0,1,1,0,'COMPLETED',777,'legacy-owner')");
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).baselineOnMigrate(true).baselineVersion("27")
                .target("29").load().migrate();
        for (String table : List.of("products", "pos_receipts")) {
            assertThat(jdbc.queryForObject("select created_by_user_id from " + table + " where created_by_username='legacy-owner'",Long.class)).isEqualTo(777L);
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where created_by_staff_id is not null",Long.class)).isZero();
        }
        jdbc.execute("alter table shops alter column created_at set default now()");
        jdbc.execute("alter table subscription_plans alter column created_at set default now()");
        jdbc.execute("alter table password_reset_tokens alter column created_at set default now()");
        jdbc.execute("alter table admin_users alter column created_at set default now()");
    }
    @BeforeEach void setup() {
        jdbc.execute("truncate stock_requests, stock_movements, pos_receipt_items, pos_receipts, products, shop_settings, consumed_refresh_tokens, login_sessions, password_reset_tokens, users, staff, shops, subscription_plans, shop_usage_monthly, shop_plan_overrides, admin_users cascade");
        jdbc.update("insert into shops(id,shop_code,shop_name,business_type,status,subscription_plan) values (10,'SHOP','Test','SUPERMARKET','ACTIVE','TRIAL'), (20,'OTHER','Other','SUPERMARKET','ACTIVE','TRIAL')");
        jdbc.update("insert into subscription_plans(code,name,price_monthly,max_devices,allow_restaurant,allow_fashion,allow_analytics,allow_kitchen,allow_table_order,active) values ('TRIAL','Test',0,2,true,true,true,true,true,true)");
        owner = users.saveAndFlush(User.builder().username("owner").email("owner@example.com").password(encoder.encode("Password123!"))
                .role(Role.ADMIN).shopId(10L).shopCode("SHOP").build());
        employee = staff.saveAndFlush(Staff.builder().fullName("owner").email("staff@example.com").staffId(900L)
                .password(encoder.encode("Password123!")).shopId(10L).shopCode("SHOP").role("ADMIN").status("active").build());
    }
    AuthService.LoginResult login(String device) { return auth.login(new LoginRequest("owner","Password123!","SHOP"), device, "Browser"); }
    AuthService.LoginResult staffLogin(String device) {
        var req = new StaffLoginRequest(); req.setStaffId(900L); req.setShopCode("SHOP"); req.setPassword("Password123!");
        return auth.staffLogin(req, device, "Till");
    }
    void assertAccess(String token, String path, int status) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().is(status));
    }
    void resetToken(String token) {
        jdbc.update("insert into password_reset_tokens(user_id,token_hash,expires_at,used) values (?,?,?,false)",
                owner.getId(), SessionService.hash(token), OffsetDateTime.now().plusMinutes(10));
    }

    @Test void ownerAndStaffHaveSeparateIdentitiesAndAuthorities() throws Exception {
        var user = login(DEVICE); var worker = staffLogin(DEVICE);
        assertThat(jwt.extractUsername(user.response().getToken())).isEqualTo(owner.getId().toString());
        assertThat(jwt.extractAccountId(worker.response().getToken())).isEqualTo(employee.getId());
        assertAccess(user.response().getToken(), "/api/owner/session-test", 200);
        assertAccess(user.response().getToken(), "/api/pos/session-test", 200);
        assertAccess(user.response().getToken(), "/api/owner/profile", 200);
        assertAccess(user.response().getToken(), "/api/pos/receipts/my", 200);
        assertAccess(user.response().getToken(), "/api/me/profile", 200);
        assertAccess(worker.response().getToken(), "/api/pos/session-test", 200);
        assertAccess(worker.response().getToken(), "/api/owner/session-test", 403);
        assertAccess(worker.response().getToken(), "/api/owner/profile", 403);
        assertAccess(worker.response().getToken(), "/api/me/profile", 403);
        assertAccess(worker.response().getToken(), "/api/owner/shops/10/devices", 403);
        assertThat(store.activeDevices(10L)).isEqualTo(1);
    }
    @Test void legacyTokensAndSessionIdentityMismatchesAreRejected() throws Exception {
        assertAccess(jwt.generateToken(owner), "/api/pos/session-test", 401);
        assertAccess(jwt.generateStaffToken(employee, owner), "/api/pos/session-test", 401);
        var worker = staffLogin(DEVICE);
        assertAccess(jwt.generateToken(owner, jwt.extractSessionId(worker.response().getToken())), "/api/pos/session-test", 401);
    }
    @Test void rotationAndReplayRevokeTheEntireSessionAndCommitOnRejection() throws Exception {
        var initial = login(DEVICE);
        var rotated = auth.refresh(initial.refreshToken());
        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(store.byHash(SessionService.hash(initial.refreshToken()))).isEmpty();
        assertThat(store.consumed(SessionService.hash(initial.refreshToken()))).isPresent();
        assertThatThrownBy(() -> auth.refresh(initial.refreshToken())).isInstanceOf(SessionService.InvalidRefresh.class);
        // Query in a new transaction/connection: verifies noRollbackFor really committed.
        assertThat(store.activeDevices(10L)).isZero();
        assertThatThrownBy(() -> auth.refresh(rotated.refreshToken())).isInstanceOf(SessionService.InvalidRefresh.class);
        assertAccess(rotated.response().getToken(), "/api/pos/session-test", 401);
    }
    @Test void remoteLogoutRevokesAllAccountsOnSelectedDeviceOnly() throws Exception {
        var admin = login("installation-admin001");
        var user = login(DEVICE); var worker = staffLogin(DEVICE);
        mvc.perform(delete("/api/owner/shops/10/devices/" + DEVICE).header("Authorization", "Bearer " + admin.response().getToken()))
                .andExpect(status().isNoContent());
        for (var result : List.of(user, worker)) {
            assertAccess(result.response().getToken(), "/api/pos/session-test", 401);
            assertThatThrownBy(() -> auth.refresh(result.refreshToken())).isInstanceOf(SessionService.InvalidRefresh.class);
        }
        assertAccess(admin.response().getToken(), "/api/owner/session-test", 200);
    }
    @Test void passwordResetImmediatelyRevokesOnlyThatUsersSessions() throws Exception {
        var user = login(DEVICE); var worker = staffLogin(DEVICE);
        resetToken("reset-once");
        resets.resetPassword(new ResetPasswordRequest("reset-once", "NewPassword123!", "NewPassword123!"));
        assertAccess(user.response().getToken(), "/api/pos/session-test", 401);
        assertThatThrownBy(() -> auth.refresh(user.refreshToken())).isInstanceOf(SessionService.InvalidRefresh.class);
        assertThatThrownBy(() -> login("installation-oldpassword")).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        assertAccess(worker.response().getToken(), "/api/pos/session-test", 200);
        assertThat(resets.validateResetToken("reset-once").valid()).isFalse();
        org.mockito.Mockito.verify(emails).sendPasswordChangedEmail("owner@example.com");
    }
    @Test void crossShopDeviceManagementIsForbidden() throws Exception {
        var user = login(DEVICE);
        assertAccess(user.response().getToken(), "/api/owner/shops/20/devices", 403);
        mvc.perform(delete("/api/owner/shops/20/devices/" + DEVICE).header("Authorization", "Bearer " + user.response().getToken()))
                .andExpect(status().isForbidden());
        assertThat(store.activeDevices(10L)).isEqualTo(1);
    }
    @Test void concurrentLoginsCannotExceedShopLimit() throws Exception {
        jdbc.update("update subscription_plans set max_devices=1");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> attempt(start, false));
            var b = executor.submit(() -> attempt(start, true));
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(store.activeDevices(10L)).isEqualTo(1);
    }
    boolean attempt(CountDownLatch start, boolean worker) throws Exception {
        start.await();
        try { if (worker) staffLogin("installation-staff001"); else login(DEVICE); return true; }
        catch (org.springframework.web.server.ResponseStatusException e) { assertThat(e.getStatusCode().value()).isEqualTo(409); return false; }
    }
    @Test void repeatedLoginAndExpiredRevokedSessionsDoNotConsumeExtraDevices() throws Exception {
        var first = login(DEVICE); var second = login(DEVICE);
        assertThat(store.activeDevices(10L)).isEqualTo(1);
        assertAccess(first.response().getToken(), "/api/pos/session-test", 401);
        jdbc.update("update login_sessions set expires_at=? where id=?", OffsetDateTime.now().minusSeconds(1), UUID.fromString(jwt.extractSessionId(second.response().getToken())));
        assertThat(store.activeDevices(10L)).isZero();
        assertAccess(second.response().getToken(), "/api/pos/session-test", 401);
        assertThatThrownBy(() -> auth.refresh(second.refreshToken())).isInstanceOf(SessionService.InvalidRefresh.class);
        var third = login(DEVICE);
        assertThat(plans.getCurrentUsage(10L).getDeviceCount()).isEqualTo(1);
        mvc.perform(get("/api/me/plan").header("Authorization", "Bearer " + third.response().getToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usage.deviceCount").value(1));
        assertThat(plans.resetCurrentUsage(10L).getDeviceCount()).isEqualTo(1);
        sessions.logout(third.response().getToken());
        assertThat(plans.refreshUsage(10L).getDeviceCount()).isZero();
    }
    @Test void browserCookiesOriginProtectionAndLogout() throws Exception {
        String body = "{\"username\":\"owner\",\"password\":\"Password123!\",\"shopCode\":\"SHOP\"}";
        mvc.perform(post("/api/auth/login").contentType("application/json").content(body)
                .header("X-Device-ID",DEVICE).header("X-Device-Name","Browser")).andExpect(status().isForbidden());
        var result = mvc.perform(post("/api/auth/login").contentType("application/json").content(body)
                .header("Origin","http://localhost:3000").header("X-Device-ID",DEVICE).header("X-Device-Name","Browser"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Cache-Control","no-store")).andReturn();
        var cookie = result.getResponse().getCookie(SessionCookies.NAME);
        assertThat(cookie).isNotNull(); assertThat(cookie.isHttpOnly()).isTrue(); assertThat(cookie.getPath()).isEqualTo("/api/auth");
        mvc.perform(post("/api/auth/refresh").cookie(cookie).header("Origin","https://evil.example"))
                .andExpect(status().isForbidden());
        var refreshed = mvc.perform(post("/api/auth/refresh").cookie(cookie).header("Origin","http://localhost:3000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").doesNotExist()).andReturn();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(refreshed.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/auth/logout").header("Origin","http://localhost:3000").header("Authorization","Bearer " + token))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge(SessionCookies.NAME,0));
        assertAccess(token, "/api/pos/session-test", 401);
        mvc.perform(post("/api/auth/refresh").cookie(refreshed.getResponse().getCookie(SessionCookies.NAME)).header("Origin","http://localhost:3000"))
                .andExpect(status().isUnauthorized());
    }
    @Test void refreshChecksCurrentShopAndAccountStatus() {
        var worker = staffLogin(DEVICE);
        jdbc.update("update staff set status='inactive' where id=?", employee.getId());
        assertThatThrownBy(() -> auth.refresh(worker.refreshToken())).isInstanceOf(SessionService.InvalidRefresh.class);
        var user = login(DEVICE);
        jdbc.update("update shops set status='SUSPENDED' where id=10");
        assertThatThrownBy(() -> auth.refresh(user.refreshToken())).isInstanceOf(SessionService.InvalidRefresh.class);
    }
    @Test void superAdminFlowDoesNotRequireSessionId() throws Exception {
        Long id = jdbc.queryForObject("insert into admin_users(username,password_hash,role,active) values ('root','unused','SUPER_ADMIN',true) returning id",Long.class);
        assertAccess(jwt.generateAdminToken(id,"root","SUPER_ADMIN"), "/api/admin/session-test", 200);
    }

    @Test void concurrentRefreshReuseRevokesEvenTheWinningRotation() throws Exception {
        var initial = login(DEVICE);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> rotate = () -> {
                start.await();
                try { auth.refresh(initial.refreshToken()); return true; }
                catch (SessionService.RefreshReplay ex) { return false; }
            };
            var first = executor.submit(rotate); var second = executor.submit(rotate);
            start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
        }
        assertThat(store.activeDevices(10L)).isZero();
        assertAccess(initial.response().getToken(), "/api/pos/session-test", 401);
    }

    @Test void passwordAndRevocationRollBackTogether() throws Exception {
        var initial = login(DEVICE); resetToken("rollback-reset");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            resets.resetPassword(new ResetPasswordRequest("rollback-reset","NewPassword123!","NewPassword123!"));
            tx.setRollbackOnly();
        });
        assertThat(encoder.matches("Password123!", users.findById(owner.getId()).orElseThrow().getPassword())).isTrue();
        assertAccess(initial.response().getToken(), "/api/pos/session-test", 200);
        assertThat(auth.refresh(initial.refreshToken())).isNotNull();
        assertThat(resets.validateResetToken("rollback-reset").valid()).isTrue();
        org.mockito.Mockito.verify(emails,org.mockito.Mockito.never()).sendPasswordChangedEmail(org.mockito.ArgumentMatchers.anyString());
    }

    @Test void resetSerializesWithWaitingLoginAndRefresh() throws Exception {
        var initial = login(DEVICE); resetToken("concurrent-reset");
        var changed = new CountDownLatch(1); var commit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var reset = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                resets.resetPassword(new ResetPasswordRequest("concurrent-reset","NewPassword123!","NewPassword123!"));
                changed.countDown();
                try { if (!commit.await(10,TimeUnit.SECONDS)) throw new AssertionError("Commit timed out"); }
                catch (InterruptedException ex) { throw new RuntimeException(ex); }
            }));
            assertThat(changed.await(10,TimeUnit.SECONDS)).isTrue();
            var waitingLogin = executor.submit(() -> {
                assertThatThrownBy(() -> login("installation-waiting001"))
                        .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
            });
            var waitingRefresh = executor.submit(() -> {
                assertThatThrownBy(() -> auth.refresh(initial.refreshToken())).isInstanceOf(SessionService.InvalidRefresh.class);
            });
            try {
                assertThatThrownBy(() -> waitingLogin.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThatThrownBy(() -> waitingRefresh.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { commit.countDown(); }
            reset.get(15,TimeUnit.SECONDS); waitingLogin.get(15,TimeUnit.SECONDS); waitingRefresh.get(15,TimeUnit.SECONDS);
        }
        assertThat(store.activeDevices(10L)).isZero();
        assertAccess(initial.response().getToken(), "/api/pos/session-test", 401);
    }
    @Test void staffUsesRealProductAndReceiptPathsDespiteSubjectCollision() throws Exception {
        var worker = staffLogin(DEVICE);
        String token = worker.response().getToken();
        users.saveAndFlush(User.builder().username(jwt.extractUsername(token)).password(encoder.encode("OtherPassword!"))
                .role(Role.ADMIN).shopId(20L).shopCode("OTHER").build());
        var created = mvc.perform(multipart("/api/products").param("sku","STAFF-SKU")
                .param("product_name","Staff product").param("product_price","100")
                .param("product_quantity_amount","10").param("shopId","20")
                .param("createdByUserId",owner.getId().toString()).header("Authorization","Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get("id").asLong();
        var product = products.findById(id).orElseThrow();
        assertThat(product.getShopId()).isEqualTo(10L);
        assertThat(product.getCreatedByUserId()).isNull();
        assertThat(product.getCreatedByStaffId()).isEqualTo(employee.getId());
        assertAccess(token,"/api/products/" + id,200);
        mvc.perform(get("/api/products").header("Authorization","Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        String body = """
                {"requestId":"staff-sale","staffId":"900","paymentMethod":"CASH","grandTotal":100,
                 "items":[{"productId":"%s","productName":"Staff product","qty":1,"price":100,"lineTotal":100}]}
                """.formatted(id);
        mvc.perform(post("/api/pos/receipts").header("Authorization","Bearer " + token)
                .contentType("application/json").content(body)).andExpect(status().isOk());
        var receipt = receipts.findAll().getFirst();
        assertThat(receipt.getShopId()).isEqualTo(10L);
        assertThat(receipt.getCreatedByStaffId()).isEqualTo(employee.getId());
        assertThat(receipt.getCreatedByUserId()).isNull();
        mvc.perform(get("/api/pos/receipts/my").header("Authorization","Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].createdByStaffId").value(employee.getId()));
        mvc.perform(put("/api/shop/settings").header("Authorization","Bearer " + token)
                .contentType("application/json").content("{\"shopName\":\"Stolen\"}"))
                .andExpect(status().isForbidden());
        assertAccess(token,"/api/shop/settings",200);
        assertAccess(token,"/api/shop/features/my",200);
        org.mockito.Mockito.verify(featureService).getOrCreateForCurrentShop(10L,"SHOP");
        assertAccess(token,"/api/receipt-settings/my-shop",200);
        mvc.perform(put("/api/receipt-settings/my-shop").header("Authorization","Bearer " + token)
                .contentType("application/json").content("{\"shopName\":\"Stolen\"}"))
                .andExpect(status().isForbidden());
        var own = login(DEVICE).response().getToken();
        assertAccess(own,"/api/me/profile",200);
        assertAccess(own,"/api/products/" + id,200);
        mvc.perform(put("/api/shop/settings").header("Authorization","Bearer " + own)
                .contentType("application/json").content("{\"shopName\":\"Owner shop\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/pos/receipts").header("Authorization","Bearer " + own)
                .contentType("application/json").content(body.replace("staff-sale","owner-sale"))).andExpect(status().isOk());
        assertThat(receipts.findAll()).anySatisfy(r -> {
            assertThat(r.getCreatedByUserId()).isEqualTo(owner.getId());
            assertThat(r.getCreatedByStaffId()).isNull();
        });
    }

    @Test void exactWhitespaceResetPasswordCanLoginAndTrimmedPasswordCannot() throws Exception {
        resetToken("whitespace-reset");
        mvc.perform(post("/api/auth/reset-password").contentType("application/json").content("""
                {"token":"whitespace-reset","newPassword":"  NewPassword123!  ","confirmPassword":"  NewPassword123!  "}
                """)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").header("Origin","http://localhost:3000")
                .header("X-Device-ID",DEVICE).header("X-Device-Name","Browser")
                .contentType("application/json").content("""
                {"username":" owner ","shopCode":" shop ","password":"  NewPassword123!  "}
                """)).andExpect(status().isOk());
        assertThatThrownBy(() -> auth.login(new LoginRequest("owner","NewPassword123!","SHOP"),DEVICE,"Browser"))
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        employee.setPassword(encoder.encode("  StaffPassword!  ")); staff.saveAndFlush(employee);
        var request = new StaffLoginRequest(); request.setShopCode(" shop "); request.setStaffId(900L); request.setPassword("  StaffPassword!  ");
        assertThat(auth.staffLogin(request,DEVICE,"Till")).isNotNull();
        request.setPassword("StaffPassword!");
        assertThatThrownBy(() -> auth.staffLogin(request,DEVICE,"Till")).isInstanceOf(RuntimeException.class);
    }

    @Test void anotherUserAndCrossShopSessionIdsAreRejected() throws Exception {
        var original = login(DEVICE);
        User other = users.saveAndFlush(User.builder().username("other").password(encoder.encode("Password123!"))
                .role(Role.ADMIN).shopId(10L).shopCode("SHOP").build());
        assertAccess(jwt.generateToken(other,jwt.extractSessionId(original.response().getToken())),"/api/pos/session-test",401);
        other.setShopId(20L); other.setShopCode("OTHER"); users.saveAndFlush(other);
        var second = auth.login(new LoginRequest("other","Password123!","OTHER"),DEVICE,"Other");
        assertAccess(jwt.generateToken(owner,jwt.extractSessionId(second.response().getToken())),"/api/pos/session-test",401);
    }

    @Test void concurrentResetsConsumeTokenOnceAndRevokeOldSession() throws Exception {
        var original = login(DEVICE); resetToken("same-reset");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> reset = () -> {
                start.await();
                try { resets.resetPassword(new ResetPasswordRequest("same-reset","NewPassword123!","NewPassword123!")); return true; }
                catch (IllegalArgumentException e) { return false; }
            };
            var a=executor.submit(reset); var b=executor.submit(reset); start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertAccess(original.response().getToken(),"/api/pos/session-test",401);
        assertThatThrownBy(() -> auth.refresh(original.refreshToken())).isInstanceOf(SessionService.InvalidRefresh.class);
        assertThat(resets.validateResetToken("same-reset").valid()).isFalse();
        org.mockito.Mockito.verify(emails).sendPasswordChangedEmail("owner@example.com");
    }    @Test void registrationPreservesExactPasswordAndNormalizesUsername() throws Exception {
        var registered = auth.registerMultipart(new RegisterMultipartRequest(" new-owner ","  NewPassword123!  ",
                "new@example.com",null,"New shop","Address",BusinessType.SUPERMARKET),null);
        var saved = users.findByUsername("new-owner").orElseThrow();
        assertThat(encoder.matches("  NewPassword123!  ",saved.getPassword())).isTrue();
        assertThat(encoder.matches("NewPassword123!",saved.getPassword())).isFalse();
        assertThat(auth.login(new LoginRequest("new-owner","  NewPassword123!  ",registered.getShopCode()),DEVICE,"Browser")).isNotNull();
        assertThatThrownBy(() -> auth.registerMultipart(new RegisterMultipartRequest("blank","        ",
                "blank@example.com",null,"Blank","Address",BusinessType.SUPERMARKET),null)).isInstanceOf(RuntimeException.class);
    }}
