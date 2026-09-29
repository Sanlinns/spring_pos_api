package com.binhlaig.pos.modules.product;

import com.binhlaig.pos.modules.product.dto.*;
import com.binhlaig.pos.shopfeature.ShopFeatureService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StockControllerTest {
    private final ProductService service = mock(ProductService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ProductController(service, mock(ShopFeatureService.class))).build();

    private ProductResponse response() {
        return ProductResponse.from(Product.builder().id(1L).sku("TEST")
                .productQuantityAmount(new BigDecimal("118")).totalStock(new BigDecimal("150"))
                .soldQuantity(new BigDecimal("30")).stockCorrection(new BigDecimal("-2"))
                .openingBalance(new BigDecimal("100")).historicalSoldQuantity(BigDecimal.ZERO)
                .stockTrackingStartedAt(java.time.Instant.EPOCH).stockTrackingBasis("FROM_CREATION").build());
    }

    @Test void listExposesTrackingContractAndRemainingAlias() throws Exception {
        when(service.listMine(any(), any(), any(), any(), any())).thenReturn(List.of(response()));
        mvc.perform(get("/api/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].totalStock").value(150))
                .andExpect(jsonPath("$[0].soldQuantity").value(30))
                .andExpect(jsonPath("$[0].remainingStock").value(118))
                .andExpect(jsonPath("$[0].product_quantity_amount").value(118))
                .andExpect(jsonPath("$[0].stockCorrection").value(-2))
                .andExpect(jsonPath("$[0].stockTrackingBasis").value("FROM_CREATION"));
    }

    @Test void stockEndpointAcceptsSignedCorrectionAndRejectsMissingRequestId() throws Exception {
        when(service.operateStock(eq(1L), any())).thenReturn(response());
        mvc.perform(post("/api/products/1/stock").contentType("application/json").content("""
                {"requestId":"count-1","operation":"STOCK_CORRECTION","quantity":-2,"reason":"Count"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.remainingStock").value(118));
        verify(service).operateStock(1L, new StockOperationRequest("count-1",
                StockOperationRequest.Operation.STOCK_CORRECTION, new BigDecimal("-2"), "Count"));
        mvc.perform(post("/api/products/1/stock").contentType("application/json").content("""
                {"operation":"ADD_STOCK","quantity":10}
                """)).andExpect(status().isBadRequest());
        verifyNoMoreInteractions(service);
    }
}
