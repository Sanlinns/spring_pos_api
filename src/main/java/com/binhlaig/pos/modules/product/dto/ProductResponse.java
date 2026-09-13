package com.binhlaig.pos.modules.product.dto;

import com.binhlaig.pos.modules.product.Product;
import com.binhlaig.pos.modules.product.ProductType;

import java.math.BigDecimal;

public record ProductResponse(
        Long id,
        String sku,
        String product_name,
        BigDecimal product_price,
        BigDecimal product_quantity_amount,
        Boolean availableForSale,
        String barcode,
        String category,
        ProductType product_type,
        BigDecimal product_discount,
        String note,
        String image_path
) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(
                p.getId(),
                p.getSku(),
                p.getProductName(),
                p.getProductPrice(),
                p.getProductQuantityAmount(),
                p.getAvailableForSale(),
                p.getBarcode(),
                p.getCategory(),
                p.getProductType(),
                p.getProductDiscount(),
                p.getNote(),
                p.getImagePath()
        );
    }
}
