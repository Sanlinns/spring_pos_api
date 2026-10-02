
package com.binhlaig.pos.auth;

import com.binhlaig.pos.admin.PlanLimitService;
import com.binhlaig.pos.admin.Shop;
import com.binhlaig.pos.admin.ShopRepository;
import com.binhlaig.pos.admin.ShopStatus;
import com.binhlaig.pos.admin.SubscriptionPlan;
import com.binhlaig.pos.admin.dto.EffectiveLimitsResponse;
import com.binhlaig.pos.auth.dto.AuthResponse;
import com.binhlaig.pos.auth.dto.LoginRequest;
import com.binhlaig.pos.auth.dto.PlanFeaturesDto;
import com.binhlaig.pos.auth.dto.PlanLimitsDto;
import com.binhlaig.pos.auth.dto.RegisterMultipartRequest;
import com.binhlaig.pos.auth.dto.RegisterResponse;
import com.binhlaig.pos.auth.dto.StaffLoginRequest;
import com.binhlaig.pos.shopfeature.ShopFeature;
import com.binhlaig.pos.shopfeature.ShopFeatureRepository;
import com.binhlaig.pos.staff.entity.Staff;
import com.binhlaig.pos.staff.repository.StaffRepository;
import com.binhlaig.pos.storage.FileStorageService;
import com.binhlaig.pos.user.BusinessType;
import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9-]+(?:\\.[A-Z0-9-]+)+$",
            Pattern.CASE_INSENSITIVE);

    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final FileStorageService fileStorageService;
    private final PlanLimitService planLimitService;
    private final ShopRepository shopRepository;
    private final ShopFeatureRepository shopFeatureRepository;
    private final com.binhlaig.pos.auth.session.SessionService sessions;

    public record LoginResult(AuthResponse response, @com.fasterxml.jackson.annotation.JsonIgnore String refreshToken,
                              @com.fasterxml.jackson.annotation.JsonIgnore OffsetDateTime sessionExpiresAt) {
        @Override public String toString() { return "LoginResult[credentials redacted]"; }
    }

    @Transactional
    public RegisterResponse registerMultipart(RegisterMultipartRequest req, MultipartFile image) throws Exception {
        String username = req.username() == null ? "" : req.username().trim();
        String password = req.password() == null ? "" : req.password();
        String email = normalizeEmail(req.email());
        String phone = normalizePhone(req.phone());
        String shopName = req.shopName() == null ? "" : req.shopName().trim();
        String address = req.address() == null ? "" : req.address().trim();
        BusinessType businessType = req.businessType() == null ? BusinessType.SUPERMARKET : req.businessType();

        if (email == null) {
            throw new IllegalArgumentException("Email is required");
        }
        if (username.isBlank()) {
            throw new RuntimeException("Username is required");
        }

        if (password.isBlank() || password.length() < 8) {
            throw new RuntimeException("Password must be at least 8 characters");
        }

        if (shopName.isBlank()) {
            throw new RuntimeException("Shop name is required");
        }

        if (address.isBlank()) {
            throw new RuntimeException("Address is required");
        }

        if (userRepository.findByUsername(username).isPresent()) {
            throw new RuntimeException("Username already exists");
        }

        if (email != null && !EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Email must be valid");
        }

        if (email != null && userRepository.existsByEmailIgnoreCase(email)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Email already exists");
        }

        String imageUrl = null;

        if (image != null && !image.isEmpty()) {
            imageUrl = fileStorageService.saveAvatarImage(image);
        }

        Shop shop = createShop(shopName, address, businessType);

        User user = User.builder()
                .username(username)
                .email(email)
                .phone(phone)
                .password(passwordEncoder.encode(password))
                .role(Role.ADMIN)
                .shopId(shop.getId())
                .shopCode(shop.getShopCode())
                .shopName(shopName)
                .address(address)
                .businessType(businessType)
                .imageUrl(imageUrl)
                .build();

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Username or email already exists");
        }

        return RegisterResponse.builder()
                .message("User registered successfully")
                .username(user.getUsername())
                .role(user.getRole().name())
                .email(user.getEmail())
                .phone(user.getPhone())
                .shopId(user.getShopId())
                .shopCode(user.getShopCode())
                .shopName(user.getShopName())
                .address(user.getAddress())
                .businessType(businessTypeName(user))
                .imageUrl(user.getImageUrl())
                .build();
    }

    private String normalizeEmail(String value) {
        String normalized = value == null ? null : value.trim().toLowerCase(Locale.ROOT);
        return normalized == null || normalized.isBlank() ? null : normalized;
    }

    private String normalizePhone(String value) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        if (normalized.length() > 30) {
            throw new IllegalArgumentException("Phone must be at most 30 characters");
        }
        return normalized;
    }

    private Shop createShop(String shopName, String address, BusinessType businessType) {
        Long nextId = shopRepository.findMaxId() + 1;
        String shopCode = generateShopCode(shopName);

        while (shopRepository.existsByShopCode(shopCode)) {
            shopCode = generateShopCode(shopName);
        }

        Shop shop = Shop.builder()
                .id(nextId)
                .shopCode(shopCode)
                .shopName(shopName)
                .address(address)
                .businessType(businessType.name())
                .status(ShopStatus.TRIAL)
                .subscriptionPlan("TRIAL")
                .subscriptionStartDate(LocalDate.now())
                .subscriptionEndDate(LocalDate.now().plusDays(14))
                .updatedAt(OffsetDateTime.now())
                .build();

        return shopRepository.save(shop);
    }

    private String generateShopCode(String shopName) {
        String prefix = shopName == null ? "SHOP" : shopName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (prefix.length() > 6) {
            prefix = prefix.substring(0, 6);
        }
        if (prefix.isBlank()) {
            prefix = "SHOP";
        }
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    @Transactional
    public LoginResult login(LoginRequest req, String deviceId, String deviceName) {
        String username = req.username() == null ? "" : req.username().trim();
        String password = req.password() == null ? "" : req.password();
        String requestShopCode = req.shopCode() == null ? "" : req.shopCode().trim().toUpperCase();

        if (username.isBlank()) {
            throw new RuntimeException("Username is required");
        }

        if (password.isBlank()) {
            throw new RuntimeException("Password is required");
        }

        if (requestShopCode.isBlank()) {
            throw new RuntimeException("Shop code is required");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        sessions.lockAccount(user);
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new org.springframework.security.authentication.BadCredentialsException("Invalid credentials");
        }

        String userShopCode = user.getShopCode() == null ? "" : user.getShopCode().trim().toUpperCase();

        if (!userShopCode.equals(requestShopCode)) {
            throw new RuntimeException("Invalid shop code");
        }

        var issued = sessions.create("USER", user.getId(), user.getShopId(), deviceId, deviceName);
        return new LoginResult(userResponse(user, issued.session().id().toString()), issued.refreshToken(), issued.session().expiresAt());
    }

    private AuthResponse userResponse(User user, String sid) {
        PlanInfo planInfo = loadPlanInfo(user.getShopId());
        String token = jwtService.generateToken(user, sid);

        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .username(user.getUsername())
                .role(user.getRole().name())
                .shopId(user.getShopId())
                .shopCode(user.getShopCode())
                .businessType(businessTypeName(user))
                .imageUrl(user.getImageUrl())
                .shopStatus(planInfo.shopStatus())
                .subscriptionPlan(planInfo.subscriptionPlan())
                .subscriptionEndDate(planInfo.subscriptionEndDate())
                .features(planInfo.features())
                .limits(planInfo.limits())
                .build();
    }

    @Transactional
    public LoginResult staffLogin(StaffLoginRequest req, String deviceId, String deviceName) {
        String shopCode = req.getShopCode() == null ? "" : req.getShopCode().trim().toUpperCase();
        Long staffId = req.getStaffId();
        String password = req.getPassword() == null ? "" : req.getPassword();

        if (shopCode.isBlank()) {
            throw new RuntimeException("Shop code is required");
        }

        if (staffId == null) {
            throw new RuntimeException("Staff ID is required");
        }

        if (password.isBlank()) {
            throw new RuntimeException("Password is required");
        }

        Staff staff = staffRepository.findByShopCodeAndStaffId(shopCode, staffId)
                .orElseThrow(() -> new RuntimeException("Invalid shop code or staff ID"));
        sessions.lockAccount(staff);
        if (!shopCode.equalsIgnoreCase(staff.getShopCode()) || !staffId.equals(staff.getStaffId())) {
            throw new org.springframework.security.authentication.BadCredentialsException("Invalid staff identity");
        }

        if (staff.getPassword() == null || staff.getPassword().isBlank()) {
            throw new RuntimeException("Staff password is not set");
        }

        if (!passwordEncoder.matches(password, staff.getPassword())) {
            throw new RuntimeException("Invalid password");
        }

        if (staff.getStatus() != null && staff.getStatus().equalsIgnoreCase("inactive")) {
            throw new RuntimeException("Your account is inactive");
        }

        User user = userRepository.findFirstByShopId(staff.getShopId())
                .orElseThrow(() -> new RuntimeException("Shop user not found"));

        if (user.getShopId() != null && staff.getShopId() != null && !user.getShopId().equals(staff.getShopId())) {
            throw new RuntimeException("Staff does not belong to this shop");
        }

        var issued = sessions.create("STAFF", staff.getId(), staff.getShopId(), deviceId, deviceName);
        return new LoginResult(staffResponse(staff, user, issued.session().id().toString()), issued.refreshToken(), issued.session().expiresAt());
    }

    // Keep rotation and response construction atomic. Both boundaries commit replay revocation.
    @Transactional(noRollbackFor = com.binhlaig.pos.auth.session.SessionService.RefreshReplay.class)
    public LoginResult refresh(String rawToken) {
        var issued = sessions.refresh(rawToken);
        var session = issued.session();
        AuthResponse response;
        if ("USER".equals(session.accountType())) {
            response = userResponse(userRepository.findById(session.accountId()).orElseThrow(), session.id().toString());
        } else {
            Staff staff = staffRepository.findById(session.accountId()).orElseThrow();
            User user = userRepository.findFirstByShopId(session.shopId()).orElseThrow();
            response = staffResponse(staff, user, session.id().toString());
        }
        return new LoginResult(response, issued.refreshToken(), session.expiresAt());
    }

    private AuthResponse staffResponse(Staff staff, User user, String sid) {
        PlanInfo planInfo = loadPlanInfo(staff.getShopId());
        String token = jwtService.generateStaffToken(staff, user, sid);

        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .username(staff.getFullName())
                .role(staff.getRole())
                .shopId(staff.getShopId())
                .shopCode(staff.getShopCode())
                .businessType(businessTypeName(user))
                .staffId(staff.getStaffId())
                .imageUrl(staff.getImageUrl())
                .shopStatus(planInfo.shopStatus())
                .subscriptionPlan(planInfo.subscriptionPlan())
                .subscriptionEndDate(planInfo.subscriptionEndDate())
                .features(planInfo.features())
                .limits(planInfo.limits())
                .build();
    }

    private String businessTypeName(User user) {
        return user.getBusinessType() == null ? BusinessType.SUPERMARKET.name() : user.getBusinessType().name();
    }

    private PlanInfo loadPlanInfo(Long shopId) {
        if (shopId == null) {
            throw new RuntimeException("Shop ID not found for user");
        }
        Shop shop = planLimitService.findShop(shopId);
        planLimitService.assertShopCanUsePos(shopId);
        SubscriptionPlan plan = planLimitService.getCurrentPlan(shopId);
        EffectiveLimitsResponse limits = planLimitService.getEffectiveLimits(shopId);
        return new PlanInfo(
                shop.getStatus() == null ? null : shop.getStatus().name(),
                shop.getSubscriptionPlan(),
                shop.getSubscriptionEndDate(),
                featuresFrom(shopId, shop.getShopCode(), plan),
                PlanLimitsDto.from(limits)
        );
    }

    private PlanFeaturesDto featuresFrom(Long shopId, String shopCode, SubscriptionPlan plan) {
        PlanFeaturesDto features = PlanFeaturesDto.from(plan);
        findShopFeature(shopId, shopCode).ifPresent(feature -> applyShopFeatureGates(features, feature));
        return features;
    }

    private Optional<ShopFeature> findShopFeature(Long shopId, String shopCode) {
        Optional<ShopFeature> byShopId = shopId == null
                ? Optional.empty()
                : shopFeatureRepository.findByShopId(shopId);
        if (byShopId.isPresent()) {
            return byShopId;
        }
        if (shopCode == null || shopCode.isBlank()) {
            return Optional.empty();
        }
        return shopFeatureRepository.findByShopCode(shopCode.trim());
    }

    private void applyShopFeatureGates(PlanFeaturesDto features, ShopFeature feature) {
        if (feature.getAllowRestaurant() != null) {
            features.setAllowRestaurant(feature.getAllowRestaurant());
        }
        if (feature.getAllowKitchen() != null) {
            features.setAllowKitchen(feature.getAllowKitchen());
        }
        if (feature.getAllowTableOrder() != null) {
            features.setAllowTableOrder(feature.getAllowTableOrder());
        }
    }

    private record PlanInfo(
            String shopStatus,
            String subscriptionPlan,
            java.time.LocalDate subscriptionEndDate,
            PlanFeaturesDto features,
            PlanLimitsDto limits
    ) {
    }
}
