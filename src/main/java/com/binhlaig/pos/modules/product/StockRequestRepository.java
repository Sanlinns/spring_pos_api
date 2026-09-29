package com.binhlaig.pos.modules.product;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface StockRequestRepository extends JpaRepository<StockRequest, Long> {
    Optional<StockRequest> findByShopIdAndRequestKey(Long shopId, String requestKey);
}
