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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductBarcodeServiceTest {

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
        lenient().when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(User.builder().username("owner").shopId(10L).build()));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exactBarcodeIsFoundOnlyInAuthenticatedShop() {
        Product product = product(1L, 10L, "490100100001", "Coffee", "COF-1");
        when(repository.findByBarcodeAndShopId("490100100001", 10L)).thenReturn(Optional.of(product));

        ProductResponse response = service.getByBarcode(" 490100100001 ");

        assertThat(response.id()).isEqualTo(1L);
        verify(repository).findByBarcodeAndShopId("490100100001", 10L);
        verify(repository, never()).findByBarcodeAndShopId("490100100001", 20L);
    }

    @Test
    void missingOrOtherShopBarcodeReturnsNotFound() {
        when(repository.findByBarcodeAndShopId("490100100001", 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByBarcode("490100100001"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND")
                .hasMessageContaining("Product not found for barcode");
        verify(repository).findByBarcodeAndShopId("490100100001", 10L);
    }

    @Test
    void blankBarcodeReturnsBadRequestWithoutQueryingRepository() {
        assertThatThrownBy(() -> service.getByBarcode("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Barcode is required");
        verify(repository, never()).findByBarcodeAndShopId(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void searchKeepsNameAndSkuMatchingAndAddsExactBarcode() {
        List<Product> matches = List.of(
                product(1L, 10L, "111", "Coffee", "COF-1"),
                product(2L, 10L, "222", "Tea", "TEA-1"));
        when(repository.searchByShopId(10L, "COF")).thenReturn(matches);

        assertThat(service.listMine(" COF ", null, null, null, null)).hasSize(2);
        verify(repository).searchByShopId(10L, "COF");

        when(repository.searchByShopId(10L, "222")).thenReturn(List.of(matches.get(1)));
        assertThat(service.listMine("222", null, null, null, null))
                .extracting(ProductResponse::barcode)
                .containsExactly("222");
        verify(repository).searchByShopId(10L, "222");
    }

    private Product product(Long id, Long shopId, String barcode, String name, String sku) {
        return Product.builder()
                .id(id).shopId(shopId).barcode(barcode).sku(sku).name(name).productName(name)
                .productPrice(BigDecimal.TEN).productQuantityAmount(BigDecimal.ONE)
                .productDiscount(BigDecimal.ZERO).build();
    }
}
