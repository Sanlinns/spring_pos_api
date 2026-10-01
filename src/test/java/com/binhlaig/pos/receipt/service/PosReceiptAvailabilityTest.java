package com.binhlaig.pos.receipt.service;

import com.binhlaig.pos.admin.PlanLimitService;
import com.binhlaig.pos.modules.product.Product;
import com.binhlaig.pos.modules.product.ProductRepository;
import com.binhlaig.pos.receipt.dto.*;
import com.binhlaig.pos.receipt.repository.PosReceiptRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PosReceiptAvailabilityTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void unavailableOrDeletedProductCannotBeSavedAndStockIsUnchanged(boolean deleted) {
        PosReceiptRepository receiptRepository = mock(PosReceiptRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        com.binhlaig.pos.modules.product.StockRequestService requests = mock(com.binhlaig.pos.modules.product.StockRequestService.class);
        when(requests.execute(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(5)).get());
        PosReceiptService service = new PosReceiptService(
                receiptRepository, productRepository, mock(PlanLimitService.class),
                mock(com.binhlaig.pos.modules.product.StockService.class), requests);
        Product product = Product.builder().id(17L).productName("Coffee")
                .productQuantityAmount(new BigDecimal("15")).availableForSale(deleted).deletedAt(deleted ? java.time.Instant.now() : null).build();
        when(productRepository.findByIdAndShopIdForUpdate(17L, 10L)).thenReturn(Optional.of(product));
        ReceiptCreateRequest request = ReceiptCreateRequest.builder().staffId("1").paymentMethod("CASH")
                .items(List.of(ReceiptItemRequest.builder().productId("17").qty(1).build())).build();
        AuthenticatedUserInfo user = AuthenticatedUserInfo.builder().shopId(10L).build();

        assertThatThrownBy(() -> service.createReceipt(request, user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Coffee is currently unavailable for sale.");
        verify(receiptRepository, never()).save(any());
        org.assertj.core.api.Assertions.assertThat(product.getProductQuantityAmount()).isEqualByComparingTo("15");
    }
}
