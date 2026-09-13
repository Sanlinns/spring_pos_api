package com.binhlaig.pos.modules.product;

import com.binhlaig.pos.modules.product.dto.ProductResponse;
import com.binhlaig.pos.modules.product.dto.ProductAvailabilityRequest;
import com.binhlaig.pos.shopfeature.FeatureKey;
import com.binhlaig.pos.shopfeature.ShopFeatureService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;
    private final ShopFeatureService shopFeatureService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ProductResponse> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "q", required = false) String q,

            // user id fields ကို String နဲ့ယူမယ်။ "user3" လို value လာလည်း crash မဖြစ်တော့ပါ။
            @RequestParam(value = "createdByUserId", required = false) String createdByUserId,
            @RequestParam(value = "created_by_user_id", required = false) String createdByUserIdSnake,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "ownerId", required = false) String ownerId,

            // shop id လည်း String နဲ့ယူပြီး safe parse လုပ်မယ်။
            @RequestParam(value = "shopId", required = false) String shopId,
            @RequestParam(value = "shop_id", required = false) String shopIdSnake,
            @RequestParam(value = "shopCode", required = false) String shopCode,
            @RequestParam(value = "shop_code", required = false) String shopCodeSnake
    ) {
        shopFeatureService.requireFeatureFromAuthorization(authorization, FeatureKey.PRODUCTS);
        return service.listMine(q, null, null, null, authorization);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProductResponse> getById(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id
    ) {
        shopFeatureService.requireFeatureFromAuthorization(authorization, FeatureKey.PRODUCTS);
        return ResponseEntity.ok(service.getById(id));
    }

    @PatchMapping(value = "/{productId}/availability", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProductResponse> updateAvailability(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long productId,
            @Valid @RequestBody ProductAvailabilityRequest request
    ) {
        shopFeatureService.requireFeatureFromAuthorization(authorization, FeatureKey.PRODUCTS);
        return ResponseEntity.ok(service.updateAvailability(productId, request.availableForSale()));
    }

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ProductResponse create(
            @RequestHeader(value = "Authorization", required = false) String authorization,

            @RequestParam("sku") @NotBlank String sku,
            @RequestParam("product_name") @NotBlank String productName,
            @RequestParam("product_price") BigDecimal productPrice,

            @RequestParam(value = "product_quantity_amount", required = false) BigDecimal qty,
            @RequestParam(value = "barcode", required = false) String barcode,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "product_type", required = false) String productTypeSnake,
            @RequestParam(value = "productType", required = false) String productTypeCamel,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam(value = "product_discount", required = false) BigDecimal discount,

            @RequestPart(value = "image", required = false) MultipartFile image,

            // camelCase fields from frontend
            @RequestParam(value = "createdByUserId", required = false) String createdByUserId,
            @RequestParam(value = "createdByUsername", required = false) String createdByUsername,
            @RequestParam(value = "createdByName", required = false) String createdByName,
            @RequestParam(value = "createdByRole", required = false) String createdByRole,
            @RequestParam(value = "shopId", required = false) String shopId,
            @RequestParam(value = "shopCode", required = false) String shopCode,
            @RequestParam(value = "createdBy", required = false) String createdBy,
            @RequestParam(value = "businessType", required = false) String businessTypeCamel,
            @RequestParam(value = "moduleType", required = false) String moduleTypeCamel,

            // snake_case fields from frontend
            @RequestParam(value = "created_by_user_id", required = false) String createdByUserIdSnake,
            @RequestParam(value = "created_by_username", required = false) String createdByUsernameSnake,
            @RequestParam(value = "created_by_name", required = false) String createdByNameSnake,
            @RequestParam(value = "created_by_role", required = false) String createdByRoleSnake,
            @RequestParam(value = "shop_id", required = false) String shopIdSnake,
            @RequestParam(value = "shop_code", required = false) String shopCodeSnake,
            @RequestParam(value = "created_by", required = false) String createdBySnake,
            @RequestParam(value = "business_type", required = false) String businessTypeSnake,
            @RequestParam(value = "module_type", required = false) String moduleTypeSnake,

            // fallback fields
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "user_id", required = false) String userIdSnake,
            @RequestParam(value = "ownerId", required = false) String ownerId,
            @RequestParam(value = "owner_id", required = false) String ownerIdSnake,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "ownerUsername", required = false) String ownerUsername,
            @RequestParam(value = "owner_username", required = false) String ownerUsernameSnake
    ) throws Exception {
        shopFeatureService.requireFeatureFromAuthorization(authorization, FeatureKey.PRODUCTS);

        if (qty == null) {
            qty = BigDecimal.ZERO;
        }

        if (discount == null) {
            discount = BigDecimal.ZERO;
        }

        String productTypeValue = firstNonBlank(productTypeSnake, productTypeCamel);
        ProductType productType = parseProductTypeOrOther(productTypeValue);

        String businessTypeValue = firstNonBlank(
                businessTypeSnake,
                businessTypeCamel,
                moduleTypeSnake,
                moduleTypeCamel
        );

        if (businessTypeValue.isBlank()) {
            businessTypeValue = "SUPERMARKET";
        }

        Long finalCreatedByUserId = firstLongFromString(
                createdByUserId,
                createdByUserIdSnake,
                userId,
                userIdSnake,
                ownerId,
                ownerIdSnake
        );

        /*
         * createdByUserId ထဲကို "user3" လို string ဝင်လာရင်
         * finalCreatedByUserId = null ဖြစ်မယ်။
         * အဲဒီ string ကို username အဖြစ် fallback ယူမယ်။
         */
        String finalCreatedByUsername = firstString(
                createdByUsername,
                createdByUsernameSnake,
                username,
                ownerUsername,
                ownerUsernameSnake,
                firstNonNumericString(
                        createdByUserId,
                        createdByUserIdSnake,
                        userId,
                        userIdSnake,
                        ownerId,
                        ownerIdSnake
                )
        );

        String finalCreatedByName = firstString(
                createdByName,
                createdByNameSnake
        );

        String finalCreatedByRole = firstString(
                createdByRole,
                createdByRoleSnake
        );

        Long finalShopId = firstLongFromString(shopId, shopIdSnake);

        String finalShopCode = firstString(shopCode, shopCodeSnake);

        String finalCreatedBy = firstString(createdBy, createdBySnake);

        return service.create(
                sku.trim(),
                productName.trim(),
                productPrice,
                qty,
                barcode,
                category,
                productType,
                discount,
                note,
                image,

                null,
                null,
                finalCreatedByName,
                finalCreatedByRole,
                null,
                null,
                finalCreatedBy,
                authorization
        );
    }

    @PutMapping(
            value = "/{id}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ProductResponse update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,

            @RequestParam(value = "sku", required = false) String sku,
            @RequestParam(value = "product_name", required = false) String productName,
            @RequestParam(value = "product_price", required = false) BigDecimal productPrice,
            @RequestParam(value = "product_quantity_amount", required = false) BigDecimal qty,
            @RequestParam(value = "barcode", required = false) String barcode,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "product_type", required = false) String productTypeSnake,
            @RequestParam(value = "productType", required = false) String productTypeCamel,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam(value = "product_discount", required = false) BigDecimal discount,
            @RequestPart(value = "image", required = false) MultipartFile image,

            @RequestParam(value = "createdByUserId", required = false) String createdByUserId,
            @RequestParam(value = "created_by_user_id", required = false) String createdByUserIdSnake,
            @RequestParam(value = "createdByUsername", required = false) String createdByUsername,
            @RequestParam(value = "created_by_username", required = false) String createdByUsernameSnake,
            @RequestParam(value = "businessType", required = false) String businessTypeCamel,
            @RequestParam(value = "business_type", required = false) String businessTypeSnake,
            @RequestParam(value = "moduleType", required = false) String moduleTypeCamel,
            @RequestParam(value = "module_type", required = false) String moduleTypeSnake,
            @RequestParam(value = "shopId", required = false) String shopId,
            @RequestParam(value = "shop_id", required = false) String shopIdSnake,
            @RequestParam(value = "shopCode", required = false) String shopCode,
            @RequestParam(value = "shop_code", required = false) String shopCodeSnake
    ) throws Exception {
        shopFeatureService.requireFeatureFromAuthorization(authorization, FeatureKey.PRODUCTS);

        Long finalCreatedByUserId = firstLongFromString(
                createdByUserId,
                createdByUserIdSnake
        );

        String finalCreatedByUsername = firstString(
                createdByUsername,
                createdByUsernameSnake,
                firstNonNumericString(createdByUserId, createdByUserIdSnake)
        );

        Long finalShopId = firstLongFromString(shopId, shopIdSnake);
        String finalShopCode = firstString(shopCode, shopCodeSnake);
        String productTypeValue = firstNonBlank(productTypeSnake, productTypeCamel);
        ProductType productType = parseProductTypeOrOther(productTypeValue);
        String businessTypeValue = firstNonBlank(
                businessTypeSnake,
                businessTypeCamel,
                moduleTypeSnake,
                moduleTypeCamel
        );

        if (businessTypeValue.isBlank()) {
            businessTypeValue = "SUPERMARKET";
        }

        return service.update(
                id,
                sku,
                productName,
                productPrice,
                qty,
                barcode,
                category,
                productType,
                discount,
                note,
                image,

                null,
                null,
                null,
                null,
                authorization
        );
    }

    @DeleteMapping("/{id}")
    public void delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id
    ) throws Exception {
        shopFeatureService.requireFeatureFromAuthorization(authorization, FeatureKey.PRODUCTS);
        service.delete(id);
    }

    private static Long firstLongFromString(String... values) {
        if (values == null) return null;

        for (String value : values) {
            if (value == null) continue;

            String trimmed = value.trim();
            if (trimmed.isEmpty()) continue;

            try {
                return Long.valueOf(trimmed);
            } catch (NumberFormatException ignored) {
                // "user3" လို string ဖြစ်ရင် skip လုပ်မယ်
            }
        }

        return null;
    }

    private static String firstNonNumericString(String... values) {
        if (values == null) return null;

        for (String value : values) {
            if (value == null) continue;

            String trimmed = value.trim();
            if (trimmed.isEmpty()) continue;

            try {
                Long.valueOf(trimmed);
            } catch (NumberFormatException ignored) {
                return trimmed;
            }
        }

        return null;
    }

    private static String firstString(String... values) {
        if (values == null) return null;

        for (String value : values) {
            if (value == null) continue;

            String trimmed = value.trim();
            if (!trimmed.isEmpty()) return trimmed;
        }

        return null;
    }

    private ProductType parseProductTypeOrOther(String value) {
        if (value == null || value.isBlank()) {
            return ProductType.OTHER;
        }

        try {
            return ProductType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ProductType.OTHER;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return "";
    }
}
