package com.binhlaig.pos.restaurant.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface RestaurantOrderRepository extends JpaRepository<RestaurantOrder, Long> {

    @Query("""
            select distinct o
            from RestaurantOrder o
            left join fetch o.items
            where o.shopId = :shopId
            order by o.createdAt desc
            """)
    List<RestaurantOrder> findShopOrdersWithItems(@Param("shopId") Long shopId);

    Optional<RestaurantOrder> findByIdAndShopId(Long id, Long shopId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RestaurantOrder> findFirstByShopIdAndTableIdAndStatusOrderByCreatedAtDesc(
            Long shopId,
            Long tableId,
            String status
    );

    @Query("""
            select distinct o
            from RestaurantOrder o
            left join fetch o.items
            where o.shopId = :shopId
              and o.tableId = :tableId
              and o.status = :status
            order by o.createdAt desc
            """)
    Optional<RestaurantOrder> findOpenByTableIdWithItems(
            @Param("shopId") Long shopId,
            @Param("tableId") Long tableId,
            @Param("status") String status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o
            from RestaurantOrder o
            where o.shopId = :shopId
              and o.tableId = :tableId
            order by o.createdAt desc
            """)
    List<RestaurantOrder> findByShopAndTableForUpdate(
            @Param("shopId") Long shopId,
            @Param("tableId") Long tableId
    );
}
