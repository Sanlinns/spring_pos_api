//
//package com.binhlaig.pos.modules.product;
//
//import jakarta.persistence.LockModeType;
//import org.springframework.data.jpa.repository.JpaRepository;
//import org.springframework.data.jpa.repository.Lock;
//import org.springframework.data.jpa.repository.Query;
//import org.springframework.data.repository.query.Param;
//
//import java.util.List;
//import java.util.Optional;
//
//public interface ProductRepository extends JpaRepository<Product, Long> {
//
//    // Existing methods
//    boolean existsBySku(String sku);
//
//    Optional<Product> findBySku(String sku);
//
//    // Search
//    List<Product> findByProductNameContainingIgnoreCase(String productName);
//
//    // Current user created products
//    List<Product> findByCreatedByUserId(Long createdByUserId);
//
//    List<Product> findByCreatedByUserIdAndProductNameContainingIgnoreCase(
//            Long createdByUserId,
//            String productName
//    );
//
//    // Current shop products by shop_id
//    List<Product> findByShopId(Long shopId);
//
//    List<Product> findByShopIdAndProductNameContainingIgnoreCase(
//            Long shopId,
//            String productName
//    );
//
//    // Current shop products by shop_code
//    List<Product> findByShopCode(String shopCode);
//
//    List<Product> findByShopCodeAndProductNameContainingIgnoreCase(
//            String shopCode,
//            String productName
//    );
//
//    // ----------------------------------------------------------------
//    // Receipt / POS sale stock update
//    // ----------------------------------------------------------------
//
//    Optional<Product> findByIdAndShopId(Long id, Long shopId);
//
//    Optional<Product> findByBarcodeAndShopId(String barcode, Long shopId);
//
//    // ----------------------------------------------------------------
//    // Stock update with DB row lock
//    // Sale တစ်ပြိုင်နက်တည်းဖြစ်ရင် stock မှားမလျော့အောင် lock ချမယ်
//    // ----------------------------------------------------------------
//
//    @Lock(LockModeType.PESSIMISTIC_WRITE)
//    @Query("""
//           SELECT p
//           FROM Product p
//           WHERE p.id = :id
//             AND p.shopId = :shopId
//           """)
//    Optional<Product> findByIdAndShopIdForUpdate(
//            @Param("id") String id,
//            @Param("shopId") Long shopId
//    );
//
//    @Lock(LockModeType.PESSIMISTIC_WRITE)
//    @Query("""
//           SELECT p
//           FROM Product p
//           WHERE p.barcode = :barcode
//             AND p.shopId = :shopId
//           """)
//    Optional<Product> findByBarcodeAndShopIdForUpdate(
//            @Param("barcode") String barcode,
//            @Param("shopId") Long shopId
//    );
//}
























package com.binhlaig.pos.modules.product;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // Existing methods
    boolean existsBySku(String sku);

    Optional<Product> findBySku(String sku);

    boolean existsBySkuAndShopId(String sku, Long shopId);

    Optional<Product> findBySkuAndShopId(String sku, Long shopId);

    // Search
    @Query("SELECT p FROM Product p WHERE p.deletedAt IS NULL AND LOWER(p.productName) LIKE LOWER(CONCAT('%', :productName, '%'))")
    List<Product> findByProductNameContainingIgnoreCase(@Param("productName") String productName);

    // Current user created products
    @Query("SELECT p FROM Product p WHERE p.deletedAt IS NULL AND p.createdByUserId = :createdByUserId")
    List<Product> findByCreatedByUserId(@Param("createdByUserId") Long createdByUserId);

    @Query("SELECT p FROM Product p WHERE p.deletedAt IS NULL AND p.createdByUserId = :createdByUserId AND LOWER(p.productName) LIKE LOWER(CONCAT('%', :productName, '%'))")
    List<Product> findByCreatedByUserIdAndProductNameContainingIgnoreCase(
            @Param("createdByUserId") Long createdByUserId,
            @Param("productName") String productName
    );

    // Current shop products by shop_id
    @Query("SELECT p FROM Product p WHERE p.shopId = :shopId AND p.deletedAt IS NULL")
    List<Product> findByShopId(@Param("shopId") Long shopId);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.shopId = :shopId AND p.deletedAt IS NULL")
    long countByShopId(@Param("shopId") Long shopId);

    @Query("SELECT p FROM Product p WHERE p.deletedAt IS NULL AND p.shopId = :shopId AND LOWER(p.productName) LIKE LOWER(CONCAT('%', :productName, '%'))")
    List<Product> findByShopIdAndProductNameContainingIgnoreCase(
            @Param("shopId") Long shopId,
            @Param("productName") String productName
    );

    @Query("""
           SELECT p
           FROM Product p
           WHERE p.shopId = :shopId
             AND p.deletedAt IS NULL
             AND (
                 LOWER(p.productName) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR p.barcode = :query
             )
           """)
    List<Product> searchByShopId(
            @Param("shopId") Long shopId,
            @Param("query") String query
    );

    // ----------------------------------------------------------------
    // Receipt / POS sale stock update
    // ----------------------------------------------------------------

    Optional<Product> findByIdAndShopId(Long id, Long shopId);

    Optional<Product> findByBarcodeAndShopId(String barcode, Long shopId);

    // ----------------------------------------------------------------
    // Stock update with DB row lock
    // Sale တစ်ပြိုင်နက်တည်းဖြစ်ရင် stock မှားမလျော့အောင် lock ချမယ်
    // ----------------------------------------------------------------

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT p
           FROM Product p
           WHERE p.id = :id
             AND p.shopId = :shopId
           """)
    Optional<Product> findByIdAndShopIdForUpdate(
            @Param("id") Long id,
            @Param("shopId") Long shopId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT p
           FROM Product p
           WHERE p.barcode = :barcode
             AND p.shopId = :shopId
           """)
    Optional<Product> findByBarcodeAndShopIdForUpdate(
            @Param("barcode") String barcode,
            @Param("shopId") Long shopId
    );
}
