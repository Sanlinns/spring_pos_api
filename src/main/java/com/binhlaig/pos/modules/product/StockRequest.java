package com.binhlaig.pos.modules.product;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "stock_requests", uniqueConstraints = @UniqueConstraint(columnNames = {"shop_id", "request_key"}))
@Getter @Setter @NoArgsConstructor
public class StockRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long shopId;
    @Column(nullable = false, length = 100) private String requestKey;
    @Column(nullable = false, length = 64) private String channel;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(nullable = false, columnDefinition = "text") private String responseJson;
}
