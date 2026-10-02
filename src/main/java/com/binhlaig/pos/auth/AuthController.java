//
//
//package com.binhlaig.pos.auth;
//
//import com.binhlaig.pos.auth.dto.AuthResponse;
//import com.binhlaig.pos.auth.dto.LoginRequest;
//import com.binhlaig.pos.auth.dto.RegisterMultipartRequest;
//import com.binhlaig.pos.auth.dto.RegisterResponse;
//import jakarta.validation.Valid;
//import lombok.RequiredArgsConstructor;
//import org.springframework.http.MediaType;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.multipart.MultipartFile;
//
//@RestController
//@RequiredArgsConstructor
//@RequestMapping("/api/auth")
//public class AuthController {
//
//    private final AuthService service;
//
//    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
//    public RegisterResponse register(
//            @RequestParam("username") String username,
//            @RequestParam("password") String password,
//            @RequestParam("role") String role,
//            @RequestParam("shopId") Long shopId,
//            @RequestParam("shopCode") String shopCode,
//            @RequestParam("shopName") String shopName,
//            @RequestParam("address") String address,
//            @RequestParam(value = "image", required = false) MultipartFile image
//    ) throws Exception {
//        var req = new RegisterMultipartRequest(
//                username,
//                password,
//                Role.valueOf(role),
//                shopId,
//                shopCode,
//                shopName,
//                address
//        );
//
//        return service.registerMultipart(req, image);
//    }
//
//    @PostMapping("/login")
//    public AuthResponse login(@RequestBody @Valid LoginRequest req) {
//        return service.login(req);
//    }
//}




















package com.binhlaig.pos.auth;

import com.binhlaig.pos.auth.dto.AuthResponse;
import com.binhlaig.pos.auth.dto.ForgotPasswordRequest;
import com.binhlaig.pos.auth.dto.LoginRequest;
import com.binhlaig.pos.auth.dto.PasswordResetResponse;
import com.binhlaig.pos.auth.dto.ResetPasswordRequest;
import com.binhlaig.pos.auth.dto.ResetTokenValidationRequest;
import com.binhlaig.pos.auth.dto.ResetTokenValidationResponse;
import com.binhlaig.pos.auth.dto.RegisterMultipartRequest;
import com.binhlaig.pos.auth.dto.RegisterResponse;
import com.binhlaig.pos.auth.dto.StaffLoginRequest;
import com.binhlaig.pos.user.BusinessType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;
    private final PasswordResetService passwordResetService;
    private final com.binhlaig.pos.auth.session.SessionCookies cookies;
    private final com.binhlaig.pos.auth.session.SessionService sessions;

    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RegisterResponse register(
            @RequestParam("username") String username,
            @RequestParam("password") String password,
            @RequestParam("email") String email,
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam("shopName") String shopName,
            @RequestParam("address") String address,
            @RequestParam(value = "businessType", required = false) String businessType,
            @RequestParam(value = "image", required = false) MultipartFile image
    ) throws Exception {
        var req = new RegisterMultipartRequest(
                username,
                password,
                email,
                phone,
                shopName,
                address,
                parseBusinessType(businessType)
        );

        return service.registerMultipart(req, image);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody @Valid LoginRequest req,
            @RequestHeader("X-Device-ID") String deviceId, @RequestHeader("X-Device-Name") String deviceName,
            jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        cookies.requireOrigin(request);
        var result = service.login(req, deviceId, deviceName);
        cookies.write(response, result.refreshToken(), result.sessionExpiresAt());
        return result.response();
    }

    @PostMapping("/staff/login")
    public AuthResponse staffLogin(@RequestBody @Valid StaffLoginRequest req,
            @RequestHeader("X-Device-ID") String deviceId, @RequestHeader("X-Device-Name") String deviceName,
            jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        cookies.requireOrigin(request);
        var result = service.staffLogin(req, deviceId, deviceName);
        cookies.write(response, result.refreshToken(), result.sessionExpiresAt());
        return result.response();
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        cookies.requireOrigin(request);
        try {
            var result = service.refresh(cookies.read(request));
            cookies.write(response, result.refreshToken(), result.sessionExpiresAt());
            return result.response();
        } catch (com.binhlaig.pos.auth.session.SessionService.InvalidRefresh ex) {
            cookies.write(response, null);
            throw ex;
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader("Authorization") String authorization,
            jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        cookies.requireOrigin(request);
        sessions.logout(authorization.substring(7));
        cookies.write(response, null);
    }

    @PostMapping("/forgot-password")
    public PasswordResetResponse forgotPassword(@RequestBody @Valid ForgotPasswordRequest req) {
        return passwordResetService.forgotPassword(req);
    }

    @PostMapping("/reset-password")
    public PasswordResetResponse resetPassword(@RequestBody @Valid ResetPasswordRequest req) {
        return passwordResetService.resetPassword(req);
    }

    @PostMapping("/reset-password/validate")
    public ResetTokenValidationResponse validateResetToken(
            @RequestBody @Valid ResetTokenValidationRequest req) {
        return passwordResetService.validateResetToken(req.token());
    }

    private BusinessType parseBusinessType(String businessType) {
        if (businessType == null || businessType.isBlank()) {
            return BusinessType.SUPERMARKET;
        }

        return BusinessType.valueOf(businessType.trim().toUpperCase());
    }
}
