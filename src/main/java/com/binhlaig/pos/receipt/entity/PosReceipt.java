//package com.binhlaig.pos.receipt.entity;
//
//import jakarta.persistence.*;
//import lombok.*;
//
//import java.math.BigDecimal;
//import java.time.LocalDateTime;
//import java.util.ArrayList;
//import java.util.List;
//
//@Entity
//@Table(name = "pos_receipts")
//@Getter
//@Setter
//@NoArgsConstructor
//@AllArgsConstructor
//@Builder
//public class PosReceipt {
//
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    @Column(name = "receipt_no", nullable = false, unique = true)
//    private String receiptNo;
//
//    @Column(name = "staff_id", nullable = false)
//    private String staffId;
//
//    @Column(name = "staff_name")
//    private String staffName;
//
//    @Column(name = "staff_role")
//    private String staffRole;
//
//    @Column(name = "payment_method", nullable = false)
//    private String paymentMethod;
//
//    @Column(nullable = false)
//    private BigDecimal subtotal;
//
//    @Column(name = "tax_amount", nullable = false)
//    private BigDecimal taxAmount;
//
//    @Column(name = "discount_percent", nullable = false)
//    private BigDecimal discountPercent;
//
//    @Column(name = "grand_total", nullable = false)
//    private BigDecimal grandTotal;
//
//    @Column(name = "cash_given", nullable = false)
//    private BigDecimal cashGiven;
//
//    @Column(name = "change_amount", nullable = false)
//    private BigDecimal changeAmount;
//
//    @Column(name = "shop_id")
//    private Long shopId;
//
//    @Column(name = "shop_code")
//    private String shopCode;
//
//    @Column(nullable = false)
//    private String status;
//
//    @Column(name = "created_at")
//    private LocalDateTime createdAt;
//
//    @Builder.Default
//    @OneToMany(
//            mappedBy = "receipt",
//            cascade = CascadeType.ALL,
//            orphanRemoval = true
//    )
//    private List<PosReceiptItem> items = new ArrayList<>();
//
//    @PrePersist
//    void prePersist() {
//        if (createdAt == null) {
//            createdAt = LocalDateTime.now();
//        }
//
//        if (status == null || status.isBlank()) {
//            status = "COMPLETED";
//        }
//
//        if (subtotal == null) subtotal = BigDecimal.ZERO;
//        if (taxAmount == null) taxAmount = BigDecimal.ZERO;
//        if (discountPercent == null) discountPercent = BigDecimal.ZERO;
//        if (grandTotal == null) grandTotal = BigDecimal.ZERO;
//        if (cashGiven == null) cashGiven = BigDecimal.ZERO;
//        if (changeAmount == null) changeAmount = BigDecimal.ZERO;
//    }
//
//    public void addItem(PosReceiptItem item) {
//        if (items == null) {
//            items = new ArrayList<>();
//        }
//
//        items.add(item);
//        item.setReceipt(this);
//    }
//}








package com.binhlaig.pos.receipt.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pos_receipts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PosReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_no", nullable = false, unique = true)
    private String receiptNo;

    @Column(name = "staff_id", nullable = false)
    private String staffId;

    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "staff_role")
    private String staffRole;

    @Column(name = "payment_method", nullable = false)
    private String paymentMethod;

    @Column(nullable = false)
    private BigDecimal subtotal;

    @Column(name = "tax_amount", nullable = false)
    private BigDecimal taxAmount;

    @Column(name = "discount_percent", nullable = false)
    private BigDecimal discountPercent;

    @Column(name = "grand_total", nullable = false)
    private BigDecimal grandTotal;

    @Column(name = "cash_given", nullable = false)
    private BigDecimal cashGiven;

    @Column(name = "change_amount", nullable = false)
    private BigDecimal changeAmount;

    @Column(name = "shop_id")
    private Long shopId;

    @Column(name = "shop_code")
    private String shopCode;

    @Column(name = "shop_name")
    private String shopName;

    @Column(name = "shop_address", columnDefinition = "TEXT")
    private String shopAddress;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_by_staff_id")
    private Long createdByStaffId;



    @Column(name = "created_by_username")
    private String createdByUsername;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "created_by_role")
    private String createdByRole;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Builder.Default
    @OneToMany(
            mappedBy = "receipt",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<PosReceiptItem> items = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null || status.isBlank()) status = "COMPLETED";

        if (subtotal == null) subtotal = BigDecimal.ZERO;
        if (taxAmount == null) taxAmount = BigDecimal.ZERO;
        if (discountPercent == null) discountPercent = BigDecimal.ZERO;
        if (grandTotal == null) grandTotal = BigDecimal.ZERO;
        if (cashGiven == null) cashGiven = BigDecimal.ZERO;
        if (changeAmount == null) changeAmount = BigDecimal.ZERO;
    }

    public void addItem(PosReceiptItem item) {
        if (items == null) {
            items = new ArrayList<>();
        }

        items.add(item);
        item.setReceipt(this);
    }
}