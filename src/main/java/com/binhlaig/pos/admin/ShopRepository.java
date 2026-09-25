package com.binhlaig.pos.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ShopRepository extends JpaRepository<Shop, Long> {

    Optional<Shop> findByShopCode(String shopCode);

    boolean existsByShopCode(String shopCode);

    boolean existsByShopCodeIgnoreCase(String shopCode);

    Optional<Shop> findById(Long id);

    List<Shop> findAllByOrderByCreatedAtDesc();

    @Query("select coalesce(max(s.id), 0) from Shop s")
    Long findMaxId();
}
