package com.binhlaig.pos.restaurant.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RestaurantPaymentRepository extends JpaRepository<RestaurantPayment, Long> {

    @Query("""
            select p
            from RestaurantPayment p
            join fetch p.order o
            where p.shopId = :shopId
              and p.shopCode = :shopCode
              and o.shopId = :shopId
              and o.shopCode = :shopCode
              and p.status = :status
              and o.id in :orderIds
            order by p.createdAt desc
            """)
    List<RestaurantPayment> findShopPaymentsForOrders(
            @Param("shopId") Long shopId,
            @Param("shopCode") String shopCode,
            @Param("status") String status,
            @Param("orderIds") List<Long> orderIds
    );

    Optional<RestaurantPayment> findByIdAndShopId(Long id, Long shopId);

    List<RestaurantPayment> findByShopIdAndShopCodeOrderByCreatedAtDesc(Long shopId, String shopCode);
}
