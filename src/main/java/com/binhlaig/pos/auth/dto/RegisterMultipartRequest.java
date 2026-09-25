//
////
//package com.binhlaig.pos.auth.dto;
//
//import com.binhlaig.pos.auth.Role;
//
//public record RegisterMultipartRequest(
//        String username,
//        String password,
//        Role role,
//        Long shopId,
//        String shopCode
//) {
//}



package com.binhlaig.pos.auth.dto;

import com.binhlaig.pos.user.BusinessType;

public record RegisterMultipartRequest(
        String username,
        String password,
        String email,
        String phone,
        String shopName,
        String address,
        BusinessType businessType
) {
}
