package com.binhlaig.pos.modules.product;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "products", indexes = {
        @Index(name = "idx_products_sku", columnList = "sku", unique = true),
        @Index(name = "idx_products_barcode", columnList = "barcode"),
        @Index(name = "idx_products_created_by_user_id", columnList = "created_by_user_id"),
        @Index(name = "idx_products_shop_id", columnList = "shop_id"),
        @Index(name = "idx_products_shop_code", columnList = "shop_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, unique = true)
    private String sku;

    /**
     * DB has NOT NULL column "name".
     * Keep it in-sync with productName.
     */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "product_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal productPrice;

    @Column(name = "product_quantity_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal productQuantityAmount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal openingBalance;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalStock;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal soldQuantity;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal stockCorrection;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal historicalSoldQuantity;
    @Column(nullable = false)
    private Instant stockTrackingStartedAt;
    @Column(nullable = false, length = 32)
    private String stockTrackingBasis;

    @Builder.Default
    @Column(name = "available_for_sale", nullable = false)
    private Boolean availableForSale = true;

    @Column(length = 64)
    private String barcode;

    @Column(length = 64)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", length = 32)
    private ProductType productType;

    @Column(name = "product_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal productDiscount;

    @Column(columnDefinition = "text")
    private String note;

    // uploaded image path, e.g. /uploads/products/xxx.jpg
    @Column(name = "image_path", length = 512)
    private String imagePath;

    /**
     * Product owner info
     */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_by_username", length = 150)
    private String createdByUsername;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "created_by_role", length = 100)
    private String createdByRole;

    @Column(name = "shop_id")
    private Long shopId;

    @Column(name = "shop_code", length = 100)
    private String shopCode;

    /**
     * DB column is jsonb.
     * This fixes:
     * found [jsonb], but expecting [text]
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "created_by", columnDefinition = "jsonb")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }

        if (productPrice == null) {
            productPrice = BigDecimal.ZERO;
        }

        if (productQuantityAmount == null) {
            productQuantityAmount = BigDecimal.ZERO;
        }

        if (productDiscount == null) {
            productDiscount = BigDecimal.ZERO;
        }

        if (productType == null) {
            productType = ProductType.OTHER;
        }

        if (availableForSale == null) {
            availableForSale = true;
        }

        syncAndClean();
    }

    @PreUpdate
    void onUpdate() {
        if (productPrice == null) {
            productPrice = BigDecimal.ZERO;
        }

        if (productQuantityAmount == null) {
            productQuantityAmount = BigDecimal.ZERO;
        }

        if (productDiscount == null) {
            productDiscount = BigDecimal.ZERO;
        }

        if (productType == null) {
            productType = ProductType.OTHER;
        }

        if (availableForSale == null) {
            availableForSale = true;
        }

        syncAndClean();
    }

    private void syncAndClean() {
        if (sku != null) {
            sku = sku.trim();
        }

        if (barcode != null) {
            barcode = emptyToNull(barcode);
        }

        if (category != null) {
            category = emptyToNull(category);
        }

        if (shopCode != null) {
            shopCode = emptyToNull(shopCode);
        }

        if (createdByUsername != null) {
            createdByUsername = emptyToNull(createdByUsername);
        }

        if (createdByName != null) {
            createdByName = emptyToNull(createdByName);
        }

        if (createdByRole != null) {
            createdByRole = emptyToNull(createdByRole);
        }

        if (note != null) {
            note = emptyToNull(note);
        }

        // ensure name/productName never null
        if (name == null || name.isBlank()) {
            name = productName;
        }

        if (productName == null || productName.isBlank()) {
            productName = name;
        }

        if (name != null) {
            name = name.trim();
        }

        if (productName != null) {
            productName = productName.trim();
        }
    }

    private String emptyToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
