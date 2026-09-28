package com.binhlaig.pos.modules.product;

import com.binhlaig.pos.common.GlobalExceptionHandler;
import com.binhlaig.pos.modules.product.dto.ProductResponse;
import com.binhlaig.pos.shopfeature.ShopFeatureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductBarcodeControllerTest {

    private ProductService service;
    private ShopFeatureService shopFeatureService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ProductService.class);
        shopFeatureService = mock(ShopFeatureService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProductController(service, shopFeatureService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void exactBarcodeEndpointReturnsExistingProductDto() throws Exception {
        ProductResponse response = new ProductResponse(
                1L, "COF-1", "Coffee", BigDecimal.TEN, BigDecimal.ONE, true,
                "490100100001", "Drinks", ProductType.OTHER, BigDecimal.ZERO, null, null);
        when(service.getByBarcode("490100100001")).thenReturn(response);

        mockMvc.perform(get("/api/products/by-barcode/490100100001")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.barcode").value("490100100001"))
                .andExpect(jsonPath("$.product_name").value("Coffee"));

        verify(service).getByBarcode("490100100001");
    }

    @Test
    void missingBarcodeReturnsUsefulJsonNotFound() throws Exception {
        when(service.getByBarcode("missing"))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Product not found for barcode: missing"));

        mockMvc.perform(get("/api/products/by-barcode/missing")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Product not found for barcode: missing"));
    }

    @Test
    void blankBarcodeReturnsBadRequestJson() throws Exception {
        when(service.getByBarcode("   ")).thenThrow(new IllegalArgumentException("Barcode is required"));

        mockMvc.perform(get("/api/products/by-barcode/{barcode}", "   ")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Barcode is required"));
    }
}
