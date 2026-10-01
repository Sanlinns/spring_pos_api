
package com.binhlaig.pos.receipt.service;

import com.binhlaig.pos.admin.PlanLimitService;
import com.binhlaig.pos.modules.product.Product;
import com.binhlaig.pos.modules.product.ProductRepository;
import com.binhlaig.pos.receipt.dto.AuthenticatedUserInfo;
import com.binhlaig.pos.receipt.dto.ReceiptCreateRequest;
import com.binhlaig.pos.receipt.dto.ReceiptItemRequest;
import com.binhlaig.pos.receipt.dto.ReceiptItemResponse;
import com.binhlaig.pos.receipt.dto.ReceiptListResponse;
import com.binhlaig.pos.receipt.dto.ReceiptResponse;
import com.binhlaig.pos.receipt.entity.PosReceipt;
import com.binhlaig.pos.receipt.entity.PosReceiptItem;
import com.binhlaig.pos.receipt.repository.PosReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PosReceiptService {

    private final PosReceiptRepository receiptRepository;
    private final ProductRepository productRepository;
    private final PlanLimitService planLimitService;
    private final com.binhlaig.pos.modules.product.StockService stockService;
    private final com.binhlaig.pos.modules.product.StockRequestService stockRequests;

    @Transactional
    public ReceiptResponse createReceipt(
            ReceiptCreateRequest request,
            AuthenticatedUserInfo userInfo
    ) {
        if (request == null || userInfo == null || userInfo.getShopId() == null)
            throw new IllegalArgumentException("Receipt and shop are required");
        return stockRequests.execute(userInfo.getShopId(), request.getRequestId(), "POS",
                request, ReceiptResponse.class, () -> createReceiptOnce(request, userInfo));
    }

    private ReceiptResponse createReceiptOnce(ReceiptCreateRequest request, AuthenticatedUserInfo userInfo) {
        if (request == null) {
            throw new IllegalArgumentException("Receipt request is empty.");
        }

        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Receipt items are empty.");
        }

        if (userInfo == null || userInfo.getShopId() == null) {
            throw new IllegalArgumentException("Shop info not found.");
        }

        planLimitService.assertCanCreateReceipt(userInfo.getShopId());

        PosReceipt receipt = PosReceipt.builder()
                .receiptNo(generateReceiptNo())

                .staffId(requiredText(request.getStaffId(), "staffId"))
                .staffName(request.getStaffName())
                .staffRole(request.getStaffRole())

                .paymentMethod(requiredText(request.getPaymentMethod(), "paymentMethod"))
                .subtotal(nullToZero(request.getSubtotal()))
                .taxAmount(nullToZero(request.getTaxAmount()))
                .discountPercent(nullToZero(request.getDiscountPercent()))
                .grandTotal(nullToZero(request.getGrandTotal()))
                .cashGiven(nullToZero(request.getCashGiven()))
                .changeAmount(nullToZero(request.getChangeAmount()))

                .shopId(userInfo.getShopId())
                .shopCode(userInfo.getShopCode())
                .shopName(userInfo.getShopName())
                .shopAddress(userInfo.getShopAddress())

                .createdByUserId(userInfo.getUserId())
                .createdByUsername(userInfo.getUsername())
                .createdByName(userInfo.getName())
                .createdByRole(userInfo.getRole())

                .status("COMPLETED")
                .createdAt(LocalDateTime.now())
                .build();

        int line = 0;
        for (ReceiptItemRequest item : request.getItems()) {
            PosReceiptItem receiptItem = createReceiptItemAndReduceStock(
                    item,
                    userInfo.getShopId(),
                    "POS:" + request.getRequestId() + ":" + line++
            );

            receipt.addItem(receiptItem);
        }

        PosReceipt saved = receiptRepository.save(receipt);

        return ReceiptResponse.builder()
                .id(saved.getId())
                .receiptNo(saved.getReceiptNo())
                .status(saved.getStatus())
                .grandTotal(saved.getGrandTotal())
                .createdAt(saved.getCreatedAt())
                .build();
    }

    private PosReceiptItem createReceiptItemAndReduceStock(
            ReceiptItemRequest item,
            Long shopId,
            String reference
    ) {
        if (item == null) {
            throw new IllegalArgumentException("Receipt item is empty.");
        }

        String rawProductId = item.getProductId();

        if (rawProductId == null || rawProductId.trim().isEmpty()) {
            throw new IllegalArgumentException("productId is required.");
        }

        Long productId;

        try {
            productId = Long.valueOf(rawProductId.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid productId: " + rawProductId);
        }

        Product product = productRepository
                .findByIdAndShopIdForUpdate(productId, shopId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found in this shop."));

        if (product.getDeletedAt() != null || !Boolean.TRUE.equals(product.getAvailableForSale())) {
            throw new IllegalStateException(
                    product.getProductName() + " is currently unavailable for sale.");
        }

        int soldQty = item.getQty() == null ? 1 : item.getQty();

        if (soldQty <= 0) {
            throw new IllegalArgumentException("Qty must be greater than 0.");
        }

        BigDecimal currentStock = nullToZero(product.getProductQuantityAmount());
        BigDecimal soldQtyValue = BigDecimal.valueOf(soldQty);

        if (currentStock.compareTo(soldQtyValue) < 0) {
            throw new IllegalArgumentException(
                    product.getProductName()
                            + " stock မလုံလောက်ပါ။ Available: "
                            + formatQuantity(currentStock)
                            + ", Cart: "
                            + soldQty
            );
        }

        stockService.sell(product, soldQtyValue, reference);
        productRepository.save(product);

        return PosReceiptItem.builder()
                .productId(String.valueOf(product.getId()))
                .barcode(product.getBarcode())
                .sku(product.getSku())
                .productName(requiredText(product.getProductName(), "productName"))
                .qty(soldQty)
                .price(nullToZero(item.getPrice()))
                .discountPercent(nullToZero(item.getDiscountPercent()))
                .taxable(item.getTaxable() == null || item.getTaxable())
                .lineTotal(nullToZero(item.getLineTotal()))
                .build();
    }

    @Transactional(readOnly = true)
    public List<ReceiptListResponse> getMyReceipts(AuthenticatedUserInfo userInfo) {
        if (userInfo == null || userInfo.getUserId() == null) {
            throw new RuntimeException("Login user not found.");
        }

        List<PosReceipt> receipts =
                receiptRepository.findByCreatedByUserIdOrderByCreatedAtDesc(
                        userInfo.getUserId()
                );

        return receipts.stream()
                .map(this::toListResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReceiptListResponse> getShopReceipts(AuthenticatedUserInfo userInfo) {
        if (userInfo == null || userInfo.getShopId() == null) {
            throw new RuntimeException("Shop info not found.");
        }

        List<PosReceipt> receipts =
                receiptRepository.findByShopIdOrderByCreatedAtDesc(
                        userInfo.getShopId()
                );

        return receipts.stream()
                .map(this::toListResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReceiptListResponse getReceiptByNo(
            String receiptNo,
            AuthenticatedUserInfo userInfo
    ) {
        if (receiptNo == null || receiptNo.trim().isEmpty()) {
            throw new IllegalArgumentException("Receipt No is required.");
        }

        if (userInfo == null || userInfo.getShopId() == null) {
            throw new IllegalArgumentException("Shop info not found.");
        }

        PosReceipt receipt = receiptRepository
                .findByReceiptNoAndShopId(receiptNo.trim(), userInfo.getShopId())
                .orElseThrow(() -> new IllegalArgumentException("Receipt not found."));

        return toListResponse(receipt);
    }

    private ReceiptListResponse toListResponse(PosReceipt receipt) {
        return ReceiptListResponse.builder()
                .id(receipt.getId())
                .receiptNo(receipt.getReceiptNo())

                .staffId(receipt.getStaffId())
                .staffName(receipt.getStaffName())
                .staffRole(receipt.getStaffRole())

                .paymentMethod(receipt.getPaymentMethod())
                .subtotal(nullToZero(receipt.getSubtotal()))
                .taxAmount(nullToZero(receipt.getTaxAmount()))
                .discountPercent(nullToZero(receipt.getDiscountPercent()))
                .grandTotal(nullToZero(receipt.getGrandTotal()))
                .cashGiven(nullToZero(receipt.getCashGiven()))
                .changeAmount(nullToZero(receipt.getChangeAmount()))

                .shopId(receipt.getShopId())
                .shopCode(receipt.getShopCode())
                .shopName(receipt.getShopName())
                .shopAddress(receipt.getShopAddress())

                .createdByUserId(receipt.getCreatedByUserId())
                .createdByUsername(receipt.getCreatedByUsername())
                .createdByName(receipt.getCreatedByName())
                .createdByRole(receipt.getCreatedByRole())

                .status(receipt.getStatus())
                .createdAt(receipt.getCreatedAt())

                .items(
                        receipt.getItems() == null
                                ? List.of()
                                : receipt.getItems()
                                  .stream()
                                  .map(this::toItemResponse)
                                  .toList()
                )
                .build();
    }

    private ReceiptItemResponse toItemResponse(PosReceiptItem item) {
        return ReceiptItemResponse.builder()
                .id(item.getId())
                .productId(item.getProductId())
                .barcode(item.getBarcode())
                .sku(item.getSku())
                .productName(item.getProductName())
                .qty(item.getQty())
                .price(nullToZero(item.getPrice()))
                .discountPercent(nullToZero(item.getDiscountPercent()))
                .taxable(item.getTaxable())
                .lineTotal(nullToZero(item.getLineTotal()))
                .build();
    }

    private String generateReceiptNo() {
        String time = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));

        return "R-" + time + "-" + java.util.UUID.randomUUID();
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String requiredText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }

        return value.trim();
    }

    private String formatQuantity(BigDecimal value) {
        return nullToZero(value).stripTrailingZeros().toPlainString();
    }
}
