package com.binhlaig.pos.admin;

import com.binhlaig.pos.admin.dto.AdminShopResponse;
import com.binhlaig.pos.admin.dto.AdminShopCheckResponse;
import com.binhlaig.pos.admin.dto.AdminShopRegisterResponse;
import com.binhlaig.pos.admin.dto.ChangeShopPlanRequest;
import com.binhlaig.pos.admin.dto.ExtendShopRequest;
import com.binhlaig.pos.admin.dto.SuspendShopRequest;
import com.binhlaig.pos.auth.Role;
import com.binhlaig.pos.storage.FileStorageService;
import com.binhlaig.pos.user.BusinessType;
import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
public class AdminShopService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9-]+(?:\\.[A-Z0-9-]+)+$",
            Pattern.CASE_INSENSITIVE);

    private final ShopRepository shopRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;

    @Transactional(readOnly = true)
    public List<AdminShopResponse> getShops() {
        return shopRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminShopResponse getShop(Long shopId) {
        return toResponse(findShop(shopId));
    }

    @Transactional(readOnly = true)
    public AdminShopCheckResponse checkShop(Long shopId, String shopCode) {
        String normalizedShopCode = normalizeShopCode(shopCode);
        boolean shopIdExists = shopId != null && shopRepository.existsById(shopId);
        boolean shopCodeExists = normalizedShopCode != null && shopRepository.existsByShopCode(normalizedShopCode);

        return new AdminShopCheckResponse(
                shopIdExists,
                shopCodeExists,
                suggestShopId(),
                suggestShopCode()
        );
    }

    @Transactional
    public AdminShopRegisterResponse registerShop(
            String username,
            String email,
            String phone,
            String password,
            String role,
            Long shopId,
            String shopCode,
            String shopName,
            String address,
            String businessType,
            String status,
            String subscriptionPlan,
            Integer subscriptionDays,
            MultipartFile image
    ) throws Exception {
        String cleanUsername = required(username, "Username is required");
        String cleanEmail = required(email, "Email is required").toLowerCase(Locale.ROOT);
        String cleanPhone = clean(phone);
        String cleanPassword = password == null ? "" : password.trim();
        String cleanShopCode = required(shopCode, "Shop code is required").toUpperCase(Locale.ROOT);
        String cleanShopName = required(shopName, "Shop name is required");
        String cleanAddress = required(address, "Address is required");
        BusinessType parsedBusinessType = parseBusinessType(businessType);
        ShopStatus parsedStatus = parseShopStatus(status);
        String planCode = defaultText(subscriptionPlan, "TRIAL").toUpperCase();
        int days = subscriptionDays == null || subscriptionDays <= 0 ? 14 : subscriptionDays;
        Role parsedRole = parseOwnerRole(role);

        if (cleanEmail.length() > 255 || !EMAIL_PATTERN.matcher(cleanEmail).matches()) {
            throw new ResponseStatusException(BAD_REQUEST, "Email must be valid and at most 255 characters");
        }
        if (cleanPhone != null && cleanPhone.length() > 30) {
            throw new ResponseStatusException(BAD_REQUEST, "Phone must be at most 30 characters");
        }
        if (cleanPassword.length() < 8) {
            throw new ResponseStatusException(BAD_REQUEST, "Password must be at least 8 characters");
        }
        if (shopId == null || shopId <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Valid shop ID is required");
        }
        if (shopRepository.existsById(shopId)) {
            throw new ResponseStatusException(CONFLICT, "Shop ID already exists");
        }
        if (shopRepository.existsByShopCode(cleanShopCode)) {
            throw new ResponseStatusException(CONFLICT, "Shop code already exists");
        }
        if (userRepository.existsByUsername(cleanUsername)) {
            throw new ResponseStatusException(CONFLICT, "Username already exists");
        }
        if (userRepository.existsByEmailIgnoreCase(cleanEmail)) {
            throw new ResponseStatusException(CONFLICT, "Email already exists.");
        }

        LocalDate today = LocalDate.now();
        OffsetDateTime now = OffsetDateTime.now();

        Shop shop = Shop.builder()
                .id(shopId)
                .shopCode(cleanShopCode)
                .shopName(cleanShopName)
                .address(cleanAddress)
                .businessType(parsedBusinessType.name())
                .status(parsedStatus)
                .subscriptionPlan(planCode)
                .subscriptionStartDate(today)
                .subscriptionEndDate(today.plusDays(days))
                .updatedAt(now)
                .build();
        shopRepository.save(shop);

        String imageUrl = null;
        if (image != null && !image.isEmpty()) {
            imageUrl = fileStorageService.saveAvatarImage(image);
        }

        User owner = User.builder()
                .username(cleanUsername)
                .email(cleanEmail)
                .phone(cleanPhone)
                .password(passwordEncoder.encode(cleanPassword))
                .role(parsedRole)
                .shopId(shopId)
                .shopCode(cleanShopCode)
                .shopName(cleanShopName)
                .address(cleanAddress)
                .businessType(parsedBusinessType)
                .imageUrl(imageUrl)
                .build();
        try {
            userRepository.saveAndFlush(owner);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(CONFLICT, "Username or email already exists.");
        }

        return new AdminShopRegisterResponse(
                "Shop and owner account created successfully",
                owner.getUsername(),
                owner.getEmail(),
                owner.getPhone(),
                owner.getRole().name(),
                shop.getId(),
                shop.getShopCode(),
                shop.getShopName(),
                shop.getAddress()
        );
    }

    @Transactional
    public AdminShopResponse activateShop(Long shopId) {
        Shop shop = findShop(shopId);
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setSuspendedReason(null);
        shop.setSuspendedAt(null);
        return toResponse(touchAndSave(shop));
    }

    @Transactional
    public AdminShopResponse suspendShop(Long shopId, SuspendShopRequest request) {
        Shop shop = findShop(shopId);
        shop.setStatus(ShopStatus.SUSPENDED);
        shop.setSuspendedReason(resolveSuspendReason(request));
        shop.setSuspendedAt(OffsetDateTime.now());
        return toResponse(touchAndSave(shop));
    }

    @Transactional
    public AdminShopResponse expireShop(Long shopId) {
        Shop shop = findShop(shopId);
        shop.setStatus(ShopStatus.EXPIRED);
        return toResponse(touchAndSave(shop));
    }

    @Transactional
    public AdminShopResponse extendShop(Long shopId, ExtendShopRequest request) {
        Shop shop = findShop(shopId);
        int days = request == null || request.days() == null ? 30 : request.days();
        if (days <= 0) {
            days = 30;
        }

        LocalDate baseDate = shop.getSubscriptionEndDate();
        if (baseDate == null || baseDate.isBefore(LocalDate.now())) {
            baseDate = LocalDate.now();
        }

        shop.setSubscriptionEndDate(baseDate.plusDays(days));
        shop.setStatus(ShopStatus.ACTIVE);
        return toResponse(touchAndSave(shop));
    }

    @Transactional
    public AdminShopResponse changePlan(Long shopId, ChangeShopPlanRequest request) {
        if (request == null || request.plan() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Subscription plan is required");
        }

        Shop shop = findShop(shopId);
        int days = resolveDays(request.days());
        LocalDate today = LocalDate.now();

        shop.setSubscriptionPlan(request.plan().trim().toUpperCase());
        if (shop.getSubscriptionStartDate() == null) {
            shop.setSubscriptionStartDate(today);
        }
        shop.setSubscriptionEndDate(today.plusDays(days));
        shop.setStatus(ShopStatus.ACTIVE);
        return toResponse(touchAndSave(shop));
    }

    private Shop findShop(Long shopId) {
        return shopRepository.findById(shopId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Shop not found"));
    }

    private Shop touchAndSave(Shop shop) {
        shop.setUpdatedAt(OffsetDateTime.now());
        return shopRepository.save(shop);
    }

    private AdminShopResponse toResponse(Shop shop) {
        return new AdminShopResponse(
                shop.getId(),
                shop.getShopCode(),
                shop.getShopName(),
                shop.getAddress(),
                shop.getBusinessType(),
                shop.getStatus() == null ? null : shop.getStatus().name(),
                shop.getSubscriptionPlan(),
                shop.getSubscriptionStartDate(),
                shop.getSubscriptionEndDate(),
                shop.getSuspendedReason(),
                shop.getSuspendedAt(),
                shop.getCreatedAt(),
                shop.getUpdatedAt()
        );
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int resolveDays(Integer days) {
        if (days == null || days <= 0) {
            return 30;
        }
        return days;
    }

    private String resolveSuspendReason(SuspendShopRequest request) {
        if (request == null) {
            return "Suspended by Super Admin";
        }

        String reason = clean(request.reason());
        if (reason != null) {
            return reason;
        }

        reason = clean(request.suspendedReason());
        return reason == null ? "Suspended by Super Admin" : reason;
    }

    private String required(String value, String message) {
        String cleaned = clean(value);
        if (cleaned == null) {
            throw new ResponseStatusException(BAD_REQUEST, message);
        }
        return cleaned;
    }

    private String defaultText(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned == null ? fallback : cleaned;
    }

    private String normalizeShopCode(String shopCode) {
        String cleaned = clean(shopCode);
        return cleaned == null ? null : cleaned.toUpperCase();
    }

    private Role parseOwnerRole(String role) {
        String value = defaultText(role, "ADMIN").toUpperCase();
        Role parsed;
        try {
            parsed = Role.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid role: " + value);
        }
        if (parsed != Role.ADMIN) {
            throw new ResponseStatusException(BAD_REQUEST, "Owner role must be ADMIN");
        }
        return parsed;
    }

    private BusinessType parseBusinessType(String businessType) {
        String value = required(businessType, "Business type is required").toUpperCase();
        try {
            return BusinessType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid business type: " + value);
        }
    }

    private ShopStatus parseShopStatus(String status) {
        String value = defaultText(status, "TRIAL").toUpperCase();
        try {
            return ShopStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid shop status: " + value);
        }
    }

    private Long suggestShopId() {
        for (int i = 0; i < 30; i++) {
            long candidate = ThreadLocalRandom.current().nextLong(1000, 10000);
            if (!shopRepository.existsById(candidate)) {
                return candidate;
            }
        }
        long candidate = ThreadLocalRandom.current().nextLong(10000, 99999);
        while (shopRepository.existsById(candidate)) {
            candidate++;
        }
        return candidate;
    }

    private String suggestShopCode() {
        for (int i = 0; i < 30; i++) {
            String candidate = "SHP-" + randomLetters(3);
            if (!shopRepository.existsByShopCode(candidate)) {
                return candidate;
            }
        }
        return "SHP-" + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    private String randomLetters(int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return value.toString();
    }
}
