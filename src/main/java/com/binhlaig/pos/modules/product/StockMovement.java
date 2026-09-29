package com.binhlaig.pos.modules.product;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "stock_movements", uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "reference"}))
@Getter @Setter @NoArgsConstructor
public class StockMovement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long productId;
    @Column(nullable = false, length = 32) private String operation;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal quantityDelta;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal balanceBefore;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal balanceAfter;
    @Column(nullable = false, length = 160) private String reference;
    @Column(length = 500) private String reason;
    @Column(nullable = false) private Instant createdAt = Instant.now();
}
