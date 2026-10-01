package com.binhlaig.pos.modules.product;

import com.binhlaig.pos.admin.PlanLimitService;
import com.binhlaig.pos.modules.product.dto.ProductResponse;
import com.binhlaig.pos.storage.FileStorageService;
import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductAvailabilityServiceTest {
    @Mock ProductRepository repository;
    @Mock FileStorageService storage;
    @Mock UserRepository userRepository;
    @Mock PlanLimitService planLimitService;
    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(repository, storage, userRepository, new ObjectMapper(), planLimitService, mock(StockService.class), mock(StockRequestService.class));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner", "n/a"));
        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(User.builder().username("owner").shopId(10L).build()));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerCanDisableAndReenableWithoutChangingQuantity() {
        Product product = product(17L, 10L, true, "15");
        when(repository.findByIdAndShopIdForUpdate(17L, 10L)).thenReturn(Optional.of(product));
        when(repository.save(product)).thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse disabled = service.updateAvailability(17L, false);
        ProductResponse enabled = service.updateAvailability(17L, true);

        assertThat(disabled.availableForSale()).isFalse();
        assertThat(enabled.availableForSale()).isTrue();
        assertThat(product.getProductQuantityAmount()).isEqualByComparingTo("15");
    }

    @Test
    void missingOrOtherShopProductIsNotFound() {
        when(repository.findByIdAndShopIdForUpdate(99L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAvailability(99L, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verify(repository).findByIdAndShopIdForUpdate(99L, 10L);
        verify(repository, never()).findById(99L);
    }


    @Test
    void trackedProductSoftDeleteIsIdempotentAndPreservesStock() throws Exception {
        Product product = product(17L, 10L, true, "15");
        product.setSoldQuantity(new BigDecimal("5"));
        product.setTotalStock(new BigDecimal("20"));
        when(repository.findByIdAndShopIdForUpdate(17L, 10L)).thenReturn(Optional.of(product));
        service.delete(17L);
        var deletedAt = product.getDeletedAt();
        service.delete(17L);
        assertThat(deletedAt).isNotNull();
        assertThat(product.getDeletedAt()).isEqualTo(deletedAt);
        assertThat(product.getProductQuantityAmount()).isEqualByComparingTo("15");
        assertThat(product.getTotalStock()).isEqualByComparingTo("20");
        assertThat(product.getSoldQuantity()).isEqualByComparingTo("5");
        verify(repository, times(1)).save(product);
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void deleteCannotAccessAnotherShop() {
        when(repository.findByIdAndShopIdForUpdate(99L, 10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verify(repository, never()).save(any());
        verify(repository, never()).findById(any());
    }

    @Test
    void archivedProductRemainsReadableForHistoryButCannotBeReenabled() {
        Product product = product(17L, 10L, true, "15");
        product.setDeletedAt(java.time.Instant.now());
        when(repository.findByIdAndShopId(17L, 10L)).thenReturn(Optional.of(product));
        when(repository.findByIdAndShopIdForUpdate(17L, 10L)).thenReturn(Optional.of(product));
        assertThat(service.getById(17L).id()).isEqualTo(17L);
        assertThatThrownBy(() -> service.updateAvailability(17L, true)).hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void activeListExcludesDisabledButManagementAllIncludesIt() {
        Product enabled = product(17L, 10L, true, "15");
        Product disabled = product(18L, 10L, false, "4");
        Product deleted = product(19L, 10L, true, "7");
        deleted.setDeletedAt(java.time.Instant.now());
        when(repository.findByShopId(10L)).thenReturn(java.util.List.of(enabled, disabled, deleted));

        assertThat(service.listMine(null, "enabled", null, null, null, null))
                .extracting(ProductResponse::id).containsExactly(17L);
        assertThat(service.listMine(null, "all", null, null, null, null))
                .extracting(ProductResponse::id).containsExactly(17L, 18L);
    }

    private Product product(Long id, Long shopId, boolean available, String quantity) {
        return Product.builder().id(id).sku("COF").name("Coffee").productName("Coffee")
                .productPrice(BigDecimal.TEN).productQuantityAmount(new BigDecimal(quantity))
                .productDiscount(BigDecimal.ZERO).shopId(shopId).availableForSale(available).build();
    }
}
