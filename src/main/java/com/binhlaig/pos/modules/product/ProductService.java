package com.binhlaig.pos.modules.product;

import com.binhlaig.pos.admin.PlanLimitService;
import com.binhlaig.pos.modules.product.dto.ProductResponse;
import com.binhlaig.pos.modules.product.dto.StockOperationRequest;
import com.binhlaig.pos.storage.FileStorageService;
import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository repo;
    private final FileStorageService storage;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final PlanLimitService planLimitService;
    private final StockService stockService;
    private final StockRequestService stockRequests;

    // ─────────────────────────────────────────────────────────────
    // OLD list - keep for compatibility
    // ─────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<ProductResponse> list(String q) {
        return listMine(q, "enabled", null, null, null, null);
    }

    // ─────────────────────────────────────────────────────────────
    // NEW listMine - current user / shop products only
    // ─────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<ProductResponse> listMine(
            String q,
            String availability,
            Long createdByUserId,
            Long shopId,
            String shopCode,
            String authorization
    ) {
        User currentUser = getCurrentUserOrNull();
        Long finalShopId = requireCurrentShopId(currentUser);

        List<Product> products;

        boolean hasSearch = q != null && !q.trim().isEmpty();
        String keyword = hasSearch ? q.trim() : null;

        products = hasSearch
                ? repo.searchByShopId(finalShopId, keyword)
                : repo.findByShopId(finalShopId);

        String availabilityFilter = availability == null ? "enabled" : availability.trim().toLowerCase();
        if (!List.of("enabled", "disabled", "all").contains(availabilityFilter)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "availability must be enabled, disabled, or all");
        }

        return products.stream()
                .filter(product -> product.getDeletedAt() == null)
                .filter(product -> "all".equals(availabilityFilter)
                        || Boolean.TRUE.equals(product.getAvailableForSale()) == "enabled".equals(availabilityFilter))
                .map(ProductResponse::from)
                .toList();
    }

    // ─────────────────────────────────────────────────────────────
    // CREATE with owner info
    // ─────────────────────────────────────────────────────────────
    @Transactional
    public ProductResponse create(
            String sku,
            String productName,
            BigDecimal productPrice,
            BigDecimal productQuantityAmount,
            String barcode,
            String category,
            ProductType productType,
            BigDecimal productDiscount,
            String note,
            MultipartFile image,

            Long createdByUserId,
            String createdByUsername,
            String createdByName,
            String createdByRole,
            Long shopId,
            String shopCode,
            String createdBy,
            String authorization
    ) throws Exception {

        String finalSku = cleanRequired(sku, "SKU");
        String finalProductName = cleanRequired(productName, "Product name");

        User currentUser = getCurrentUserOrNull();
        Long finalShopId = requireCurrentShopId(currentUser);
        String finalShopCode = cleanNullable(currentUser.getShopCode());

        if (repo.existsBySkuAndShopId(finalSku, finalShopId)) {
            throw new IllegalArgumentException("SKU already exists");
        }

        String imagePath = null;
        if (image != null && !image.isEmpty()) {
            imagePath = storage.saveProductImage(image);
        }

        Long finalCreatedByUserId = currentUser.getId();
        String finalCreatedByUsername = currentUser.getUsername();
        String finalCreatedByName = currentUser.getUsername();
        String finalCreatedByRole = getCurrentRoleOrDefault();

        /*
         * User entity ထဲမှာ getRoles() မရှိလို့ currentUser.getRoles() မသုံးပါ။
         * frontend က role ပို့လာရင်သုံးမယ်။
         * မပို့လာရင် SecurityContext authorities ထဲကယူမယ်။
         * မရရင် USER default ထားမယ်။
         */
        planLimitService.assertCanCreateProduct(finalShopId);

        String finalCreatedByJson = buildCreatedByJson(
                createdBy,
                finalCreatedByUserId,
                finalCreatedByUsername,
                finalCreatedByName,
                finalCreatedByRole,
                finalShopId,
                finalShopCode
        );

        Product p = Product.builder()
                .sku(finalSku)
                .name(finalProductName)
                .productName(finalProductName)
                .productPrice(productPrice == null ? BigDecimal.ZERO : productPrice)
                .productQuantityAmount(productQuantityAmount == null ? BigDecimal.ZERO : productQuantityAmount)
                .barcode(cleanNullable(barcode))
                .category(cleanNullable(category))
                .productType(productType == null ? ProductType.OTHER : productType)
                .productDiscount(productDiscount == null ? BigDecimal.ZERO : productDiscount)
                .note(cleanNullable(note))
                .imagePath(imagePath)

                // owner fields
                .createdByUserId(finalCreatedByUserId)
                .createdByUsername(finalCreatedByUsername)
                .createdByName(finalCreatedByName)
                .createdByRole(finalCreatedByRole)
                .shopId(finalShopId)
                .shopCode(finalShopCode)
                .createdBy(finalCreatedByJson)
                .build();

        stockService.initialize(p);
        Product saved = repo.save(p);
        stockService.recordOpening(saved);
        return ProductResponse.from(saved);
    }

    // ─────────────────────────────────────────────────────────────
    // OLD create - keep for old Controller compatibility
    // ─────────────────────────────────────────────────────────────
    @Transactional
    public ProductResponse create(
            String sku,
            String productName,
            BigDecimal productPrice,
            BigDecimal productQuantityAmount,
            String barcode,
            String category,
            ProductType productType,
            BigDecimal productDiscount,
            String note,
            MultipartFile image
    ) throws Exception {
        return create(
                sku,
                productName,
                productPrice,
                productQuantityAmount,
                barcode,
                category,
                productType,
                productDiscount,
                note,
                image,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    // ─────────────────────────────────────────────────────────────
    // UPDATE with optional owner info
    // ─────────────────────────────────────────────────────────────
    @Transactional
    public ProductResponse update(
            Long id,
            String sku,
            String productName,
            BigDecimal productPrice,
            BigDecimal productQuantityAmount,
            String barcode,
            String category,
            ProductType productType,
            BigDecimal productDiscount,
            String note,
            MultipartFile image,

            Long createdByUserId,
            String createdByUsername,
            Long shopId,
            String shopCode,
            String authorization
    ) throws Exception {

        User currentUser = getCurrentUserOrNull();
        Long currentShopId = currentUser == null ? null : currentUser.getShopId();
        if (productQuantityAmount != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Product edit cannot change stock; use ADD_STOCK or STOCK_CORRECTION");
        }
        Product p = lockProductForCurrentShop(id, currentShopId);

        if (sku != null && !sku.trim().isEmpty()) {
            String newSku = sku.trim();

            if (!newSku.equals(p.getSku()) && repo.existsBySkuAndShopId(newSku, currentShopId)) {
                throw new IllegalArgumentException("SKU already exists");
            }

            p.setSku(newSku);
        }

        if (productName != null && !productName.trim().isEmpty()) {
            String cleanName = productName.trim();
            p.setProductName(cleanName);
            p.setName(cleanName);
        }

        if (productPrice != null) {
            p.setProductPrice(productPrice);
        }

        if (barcode != null) {
            p.setBarcode(cleanNullable(barcode));
        }

        if (category != null) {
            p.setCategory(cleanNullable(category));
        }

        if (productType != null) {
            p.setProductType(productType);
        }

        if (productDiscount != null) {
            p.setProductDiscount(productDiscount);
        }

        if (note != null) {
            p.setNote(cleanNullable(note));
        }

        /*
         * update မှာ owner info ပို့လာမှ update လုပ်မယ်။
         * မပို့ရင် မူလ owner မပျက်စေဘူး။
         */
        if (image != null && !image.isEmpty()) {
            String imagePath = storage.saveProductImage(image);
            p.setImagePath(imagePath);
        }

        Product saved = repo.save(p);
        return ProductResponse.from(saved);
    }

    // ─────────────────────────────────────────────────────────────
    // OLD update - keep for old Controller compatibility
    // ─────────────────────────────────────────────────────────────
    @Transactional
    public ProductResponse update(
            Long id,
            String sku,
            String productName,
            BigDecimal productPrice,
            BigDecimal productQuantityAmount,
            String barcode,
            String category,
            ProductType productType,
            BigDecimal productDiscount,
            String note,
            MultipartFile image
    ) throws Exception {
        return update(
                id,
                sku,
                productName,
                productPrice,
                productQuantityAmount,
                barcode,
                category,
                productType,
                productDiscount,
                note,
                image,
                null,
                null,
                null,
                null,
                null
        );
    }

    // ─────────────────────────────────────────────────────────────
    // DELETE
    // ─────────────────────────────────────────────────────────────
    @Transactional
    public void delete(Long id) throws Exception {
        User currentUser = getCurrentUserOrNull();
        Product p = repo.findByIdAndShopIdForUpdate(id, requireCurrentShopId(currentUser))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id));

        if (p.getDeletedAt() == null) {
            p.setDeletedAt(java.time.Instant.now());
            repo.save(p);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // GET BY ID
    // ─────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public ProductResponse getById(Long id) {
        User currentUser = getCurrentUserOrNull();
        Product product = findProductForCurrentShop(id, currentUser == null ? null : currentUser.getShopId());

        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public ProductResponse getByBarcode(String barcode) {
        String normalizedBarcode = cleanRequired(barcode, "Barcode");
        User currentUser = getCurrentUserOrNull();
        Long shopId = requireCurrentShopId(currentUser);

        Product product = repo.findByBarcodeAndShopId(normalizedBarcode, shopId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Product not found for barcode: " + normalizedBarcode));

        if (product.getDeletedAt() != null || !Boolean.TRUE.equals(product.getAvailableForSale())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    product.getProductName() + " is currently unavailable for sale");
        }

        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse updateAvailability(Long productId, boolean availableForSale) {
        User currentUser = getCurrentUserOrNull();
        Product product = lockProductForCurrentShop(
                productId, currentUser == null ? null : currentUser.getShopId());
        product.setAvailableForSale(availableForSale);
        return ProductResponse.from(repo.save(product));
    }

    @Transactional
    public ProductResponse operateStock(Long id, StockOperationRequest request) {
        Long shopId = requireCurrentShopId(getCurrentUserOrNull());
        return stockRequests.execute(shopId, request.requestId(), "PRODUCT_STOCK:" + id,
                request, ProductResponse.class, () -> {
                    Product product = lockProductForCurrentShop(id, shopId);
                    String reference = "STOCK:" + request.requestId();
                    if (request.operation() == StockOperationRequest.Operation.ADD_STOCK) {
                        stockService.add(product, request.quantity(), reference, request.reason());
                    } else if (request.operation() == StockOperationRequest.Operation.STOCK_CORRECTION) {
                        stockService.correct(product, request.quantity(), reference, request.reason());
                    } else {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock operation required");
                    }
                    return ProductResponse.from(repo.save(product));
                });
    }

    private Product lockProductForCurrentShop(Long id, Long shopId) {
        return repo.findByIdAndShopIdForUpdate(id, requireCurrentShopId(shopId))
                .filter(product -> product.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id));
    }

    private Product findProductForCurrentShop(Long id, Long shopId) {
        Long requiredShopId = requireCurrentShopId(shopId);
        return repo.findByIdAndShopId(id, requiredShopId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Product not found: " + id));
    }

    private Long requireCurrentShopId(User user) {
        if (user == null || user.getShopId() == null) {
            throw new IllegalArgumentException("Current shop is required");
        }
        return user.getShopId();
    }

    private Long requireCurrentShopId(Long shopId) {
        if (shopId == null) {
            throw new IllegalArgumentException("Current shop is required");
        }
        return shopId;
    }

    // ─────────────────────────────────────────────────────────────
    // CURRENT USER
    // ─────────────────────────────────────────────────────────────
    private User getCurrentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return null;
        }

        String username = auth.getName();

        return userRepository.findByUsername(username).orElse(null);
    }

    private String getCurrentRoleOrDefault() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || auth.getAuthorities() == null || auth.getAuthorities().isEmpty()) {
            return "USER";
        }

        return auth.getAuthorities()
                .stream()
                .findFirst()
                .map(authority -> authority.getAuthority())
                .orElse("USER");
    }

    // ─────────────────────────────────────────────────────────────
    // JSON creator info
    // ─────────────────────────────────────────────────────────────
    private String buildCreatedByJson(
            String fallbackCreatedBy,
            Long userId,
            String username,
            String name,
            String role,
            Long shopId,
            String shopCode
    ) {
        /*
         * frontend ကပို့တဲ့ JSON ရှိပြီး backend current user မတွေ့ရင် fallback သုံးမယ်။
         */
        if (userId == null && fallbackCreatedBy != null && !fallbackCreatedBy.trim().isEmpty()) {
            return fallbackCreatedBy.trim();
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", userId);
            payload.put("userId", userId);
            payload.put("username", username);
            payload.put("name", name);
            payload.put("role", role);
            payload.put("shopId", shopId);
            payload.put("shopCode", shopCode);

            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────
    private String cleanRequired(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }

        return value.trim();
    }

    private String cleanNullable(String value) {
        if (value == null) return null;

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
