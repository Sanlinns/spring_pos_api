package com.binhlaig.pos.user;

import com.binhlaig.pos.auth.Role;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(length = 255)
    private String email;

    @Column(length = 30)
    private String phone;

    @Column(name = "password_hash", nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "roles", nullable = false, length = 30)
    private Role role;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "shop_id")
    private Long shopId;

    @Column(name = "shop_code", length = 100)
    private String shopCode;

    @Column(name = "shop_name", length = 200)
    private String shopName;

    @Column(name = "address", length = 500)
    private String address;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false, length = 30)
    private BusinessType businessType = BusinessType.SUPERMARKET;
}
