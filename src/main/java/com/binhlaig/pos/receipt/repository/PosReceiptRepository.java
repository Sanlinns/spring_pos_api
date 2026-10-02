package com.binhlaig.pos.receipt.repository;

import com.binhlaig.pos.receipt.entity.PosReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PosReceiptRepository extends JpaRepository<PosReceipt, Long> {

    Optional<PosReceipt> findByReceiptNo(String receiptNo);

    Optional<PosReceipt> findByReceiptNoAndShopId(String receiptNo, Long shopId);

    List<PosReceipt> findByCreatedByUserIdOrderByCreatedAtDesc(Long createdByUserId);
    List<PosReceipt> findByShopIdAndCreatedByUserIdOrderByCreatedAtDesc(Long shopId, Long userId);
    List<PosReceipt> findByShopIdAndCreatedByStaffIdOrderByCreatedAtDesc(Long shopId, Long staffId);

    List<PosReceipt> findByShopIdOrderByCreatedAtDesc(Long shopId);

    long countByShopIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(Long shopId, LocalDateTime start, LocalDateTime end);

    long countByShopIdAndCreatedAtBetween(Long shopId, LocalDateTime start, LocalDateTime end);
}
