package com.binhlaig.pos.restaurant.repository;

import com.binhlaig.pos.restaurant.entity.KitchenTicket;
import com.binhlaig.pos.restaurant.entity.KitchenTicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KitchenTicketRepository extends JpaRepository<KitchenTicket, Long> {

    @Query("""
            select distinct t
            from KitchenTicket t
            left join fetch t.items
            where t.shopId = :shopId
            order by t.createdAt desc
            """)
    List<KitchenTicket> findByShopIdOrderByCreatedAtDesc(@Param("shopId") Long shopId);

    @Query("""
            select distinct t
            from KitchenTicket t
            left join fetch t.items
            where t.shopId = :shopId
              and t.status = :status
            order by t.createdAt desc
            """)
    List<KitchenTicket> findByShopIdAndStatusOrderByCreatedAtDesc(
            @Param("shopId") Long shopId,
            @Param("status") KitchenTicketStatus status
    );

    @Query("""
            select distinct t
            from KitchenTicket t
            left join fetch t.items
            where t.id = :id
              and t.shopId = :shopId
            """)
    Optional<KitchenTicket> findByIdAndShopId(
            @Param("id") Long id,
            @Param("shopId") Long shopId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t
            from KitchenTicket t
            where t.id in :ids
              and t.shopId = :shopId
            order by t.id
            """)
    List<KitchenTicket> findAllByIdsAndShopIdForUpdate(
            @Param("ids") List<Long> ids,
            @Param("shopId") Long shopId
    );

    @Query("""
            select distinct t
            from KitchenTicket t
            left join fetch t.items
            where t.shopId = :shopId
              and t.tableId = :tableId
            order by t.createdAt desc
            """)
    List<KitchenTicket> findAllByShopIdAndTableId(
            @Param("shopId") Long shopId,
            @Param("tableId") Long tableId
    );
}
