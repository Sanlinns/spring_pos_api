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
        service = new ProductService(repository, storage, userRepository, new ObjectMapper(), planLimitService);
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
        when(repository.findByIdAndShopId(17L, 10L)).thenReturn(Optional.of(product));
        when(repository.save(product)).thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse disabled = service.updateAvailability(17L, false);
        ProductResponse enabled = service.updateAvailability(17L, true);

        assertThat(disabled.availableForSale()).isFalse();
        assertThat(enabled.availableForSale()).isTrue();
        assertThat(product.getProductQuantityAmount()).isEqualByComparingTo("15");
    }

    @Test
    void missingOrOtherShopProductIsNotFound() {
        when(repository.findByIdAndShopId(99L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAvailability(99L, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verify(repository).findByIdAndShopId(99L, 10L);
        verify(repository, never()).findById(99L);
    }

    private Product product(Long id, Long shopId, boolean available, String quantity) {
        return Product.builder().id(id).sku("COF").name("Coffee").productName("Coffee")
                .productPrice(BigDecimal.TEN).productQuantityAmount(new BigDecimal(quantity))
                .productDiscount(BigDecimal.ZERO).shopId(shopId).availableForSale(available).build();
    }
}
