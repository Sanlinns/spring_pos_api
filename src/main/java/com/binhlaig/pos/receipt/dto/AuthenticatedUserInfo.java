package com.binhlaig.pos.receipt.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthenticatedUserInfo {
    private Long userId;
    private Long staffAccountId;
    private String username;
    private String name;
    private String role;

    private Long shopId;
    private String shopCode;
    private String shopName;
    private String shopAddress;
}