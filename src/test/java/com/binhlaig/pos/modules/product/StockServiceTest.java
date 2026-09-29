package com.binhlaig.pos.modules.product;

import com.binhlaig.pos.modules.product.dto.ProductResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockServiceTest {
    private final StockMovementRepository movements = mock(StockMovementRepository.class);
    private final StockService service = new StockService(movements);

    private Product product(String opening) {
        Product p = Product.builder().id(1L).productQuantityAmount(new BigDecimal(opening)).build();
        service.initialize(p);
        return p;
    }

    @Test void openingSaleAndReplenishmentHaveIndependentTotals() {
        Product p = product("100");
        service.recordOpening(p);
        service.sell(p, new BigDecimal("30"), "POS:a:0");
        service.add(p, new BigDecimal("50"), "STOCK:b", null);
        ProductResponse response = ProductResponse.from(p);
        assertThat(response.totalStock()).isEqualByComparingTo("150");
        assertThat(response.soldQuantity()).isEqualByComparingTo("30");
        assertThat(response.remainingStock()).isEqualByComparingTo("120");
        assertThat(response.product_quantity_amount()).isEqualByComparingTo(response.remainingStock());
        assertThat(response.openingBalance()).isEqualByComparingTo("100");
        assertThat(response.historicalSoldQuantity()).isZero();
        assertThat(response.stockTrackingBasis()).isEqualTo("FROM_CREATION");
        var captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(movements, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(StockMovement::getOperation)
                .containsExactly("OPENING_BALANCE", "SALE", "ADD_STOCK");
        assertThat(captor.getAllValues().get(1).getQuantityDelta()).isEqualByComparingTo("-30");
    }

    @Test void signedCorrectionsNeverChangeSalesOrReplenishment() {
        Product p = product("100");
        service.sell(p, new BigDecimal("30"), "POS:a:0");
        service.correct(p, new BigDecimal("-2"), "STOCK:b", "Count discrepancy");
        service.correct(p, new BigDecimal("5"), "STOCK:c", "Recount");
        assertThat(p.getTotalStock()).isEqualByComparingTo("100");
        assertThat(p.getSoldQuantity()).isEqualByComparingTo("30");
        assertThat(p.getStockCorrection()).isEqualByComparingTo("3");
        assertThat(p.getProductQuantityAmount()).isEqualByComparingTo("73");
    }

    @Test void invalidOperationsDoNotChangeBalanceOrWriteMovements() {
        Product p = product("2");
        assertThatThrownBy(() -> service.sell(p, new BigDecimal("3"), "a")).hasMessageContaining("Insufficient");
        assertThatThrownBy(() -> service.add(p, BigDecimal.ZERO, "b", null)).hasMessageContaining("positive");
        assertThatThrownBy(() -> service.add(p, new BigDecimal("0.001"), "c", null)).hasMessageContaining("decimal");
        assertThatThrownBy(() -> service.correct(p, BigDecimal.ONE, "d", " ")).hasMessageContaining("reason");
        assertThatThrownBy(() -> service.correct(p, new BigDecimal("-3"), "e", "Count")).hasMessageContaining("Insufficient");
        assertThat(p.getProductQuantityAmount()).isEqualByComparingTo("2");
        assertThat(p.getSoldQuantity()).isZero();
        verifyNoInteractions(movements);
    }
}
