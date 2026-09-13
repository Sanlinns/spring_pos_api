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
    @Test
    void unavailableProductCannotBeSavedAndStockIsUnchanged() {
        PosReceiptRepository receiptRepository = mock(PosReceiptRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        PosReceiptService service = new PosReceiptService(
                receiptRepository, productRepository, mock(PlanLimitService.class));
        Product product = Product.builder().id(17L).productName("Coffee")
                .productQuantityAmount(new BigDecimal("15")).availableForSale(false).build();
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
