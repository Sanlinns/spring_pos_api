//package com.binhlaig.pos.auth.dto;
//
//import lombok.AllArgsConstructor;
//import lombok.Builder;
//import lombok.Data;
//import lombok.NoArgsConstructor;
//
//@Data
//@Builder
//@NoArgsConstructor
//@AllArgsConstructor
//public class RegisterResponse {
//    private String message;
//    private String username;
//    private String role;
//
//    private Long shopId;
//    private String shopCode;
//}


package com.binhlaig.pos.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterResponse {
    private String message;
    private String username;
    private String role;
    private String email;
    private String phone;

    private Long shopId;
    private String shopCode;
    private String shopName;
    private String address;
    private String businessType;

    private String imageUrl;
}
