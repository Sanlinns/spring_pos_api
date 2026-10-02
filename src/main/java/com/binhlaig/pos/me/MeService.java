package com.binhlaig.pos.me;

import com.binhlaig.pos.admin.Shop;
import com.binhlaig.pos.admin.ShopRepository;
import com.binhlaig.pos.admin.SubscriptionPlan;
import com.binhlaig.pos.admin.SubscriptionPlanRepository;
import com.binhlaig.pos.auth.Role;
import com.binhlaig.pos.me.dto.MeProfileResponse;
import com.binhlaig.pos.me.dto.MyPlanResponse;
import com.binhlaig.pos.me.dto.MyShopResponse;
import com.binhlaig.pos.me.dto.PlanFeaturesDto;
import com.binhlaig.pos.me.dto.PlanLimitsDto;
import com.binhlaig.pos.me.dto.PlanUsageDto;
import com.binhlaig.pos.me.dto.UpdateMyShopRequest;
import com.binhlaig.pos.me.dto.UpdateProfileRequest;
import com.binhlaig.pos.modules.product.ProductRepository;
import com.binhlaig.pos.receipt.repository.PosReceiptRepository;
import com.binhlaig.pos.shopfeature.ShopFeature;
import com.binhlaig.pos.shopfeature.ShopFeatureRepository;
import com.binhlaig.pos.staff.repository.StaffRepository;
import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import com.binhlaig.pos.auth.AccountPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MeService {

    private static final String FALLBACK_STATUS = "ACTIVE";
    private static final String FALLBACK_PLAN = "TRIAL";

    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final StaffRepository staffRepository;
    private final ProductRepository productRepository;
    private final PosReceiptRepository receiptRepository;
    private final ShopFeatureRepository shopFeatureRepository;
    private final com.binhlaig.pos.auth.session.SessionStore sessions;

    @Transactional(readOnly = true)
    public MeProfileResponse getProfile(Authentication authentication) {
        return toProfileResponse(resolveCurrentUser(authentication));
    }

    @Transactional
    public MeProfileResponse updateProfile(Authentication authentication, UpdateProfileRequest request) {
        User user = resolveCurrentUser(authentication);
        if (request != null && request.getUsername() != null) {
            String username = request.getUsername().trim();
            if (username.length() < 3) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username must be at least 3 characters");
            }
            if (!username.equals(user.getUsername()) && userRepository.existsByUsername(username)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username already exists");
            }
            user.setUsername(username);
        }
        return toProfileResponse(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public MyShopResponse getMyShop(Authentication authentication) {
        User user = resolveCurrentUser(authentication);
        return toShopResponse(user, findShop(user).orElse(null));
    }

    @Transactional
    public MyShopResponse updateMyShop(Authentication authentication, UpdateMyShopRequest request) {
        User user = resolveCurrentUser(authentication);
        if (user.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only ADMIN users can update shop settings");
        }

        String shopName = request == null ? null : trimToNull(request.getShopName());
        String address = request == null ? null : trimToNull(request.getAddress());

        Optional<Shop> shopOptional = findShop(user);
        if (shopOptional.isPresent()) {
            Shop shop = shopOptional.get();
            if (shopName != null) {
                shop.setShopName(shopName);
            }
            if (address != null) {
                shop.setAddress(address);
            }
            shop.setUpdatedAt(OffsetDateTime.now());
            shopRepository.save(shop);
        }

        // Keep legacy user-shop columns in sync for older POS screens and tokens.
        if (shopName != null) {
            user.setShopName(shopName);
        }
        if (address != null) {
            user.setAddress(address);
        }
        User savedUser = userRepository.save(user);
        return toShopResponse(savedUser, shopOptional.orElse(null));
    }

    @Transactional(readOnly = true)
    public MyPlanResponse getMyPlan(Authentication authentication) {
        User user = resolveCurrentUser(authentication);
        Shop shop = findShop(user).orElse(null);
        String planCode = getPlanCode(shop);
        Optional<SubscriptionPlan> plan = subscriptionPlanRepository.findByCode(planCode)
                .or(() -> subscriptionPlanRepository.findByCode(FALLBACK_PLAN));

        return MyPlanResponse.builder()
                .shopId(shop != null ? shop.getId() : user.getShopId())
                .shopCode(shop != null ? shop.getShopCode() : user.getShopCode())
                .businessType(shop != null ? shop.getBusinessType() : businessTypeName(user))
                .shopStatus(shop != null && shop.getStatus() != null ? shop.getStatus().name() : FALLBACK_STATUS)
                .subscriptionPlan(shop != null && trimToNull(shop.getSubscriptionPlan()) != null ? shop.getSubscriptionPlan() : FALLBACK_PLAN)
                .subscriptionEndDate(shop == null ? null : shop.getSubscriptionEndDate())
                .features(featuresFrom(user.getShopId(), shop != null ? shop.getShopCode() : user.getShopCode(), plan.orElse(null)))
                .limits(plan.map(this::limitsFrom).orElseGet(this::fallbackLimits))
                .usage(currentUsage(user.getShopId()))
                .build();
    }

    private User resolveCurrentUser(Authentication authentication) {
        return AccountPrincipal.require(authentication).requireUser(userRepository);
    }

    private Optional<Shop> findShop(User user) {
        return user.getShopId() == null ? Optional.empty() : shopRepository.findById(user.getShopId());
    }

    private MeProfileResponse toProfileResponse(User user) {
        return MeProfileResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .role(user.getRole() == null ? null : user.getRole().name())
                .active(true)
                .imageUrl(user.getImageUrl())
                .avatarPath(null)
                .shopId(user.getShopId())
                .shopCode(user.getShopCode())
                .shopName(user.getShopName())
                .address(user.getAddress())
                .businessType(businessTypeName(user))
                .build();
    }

    private MyShopResponse toShopResponse(User user, Shop shop) {
        if (shop != null) {
            return MyShopResponse.builder()
                    .shopId(shop.getId())
                    .shopCode(shop.getShopCode())
                    .shopName(shop.getShopName())
                    .address(shop.getAddress())
                    .businessType(shop.getBusinessType())
                    .status(shop.getStatus() == null ? null : shop.getStatus().name())
                    .subscriptionPlan(shop.getSubscriptionPlan())
                    .subscriptionEndDate(shop.getSubscriptionEndDate())
                    .build();
        }
        return MyShopResponse.builder()
                .shopId(user.getShopId())
                .shopCode(user.getShopCode())
                .shopName(user.getShopName())
                .address(user.getAddress())
                .businessType(businessTypeName(user))
                .status(FALLBACK_STATUS)
                .subscriptionPlan(FALLBACK_PLAN)
                .subscriptionEndDate(null)
                .build();
    }

    private String getPlanCode(Shop shop) {
        String code = shop == null ? null : trimToNull(shop.getSubscriptionPlan());
        return code == null ? FALLBACK_PLAN : code.toUpperCase();
    }

    private PlanFeaturesDto featuresFrom(Long shopId, String shopCode, SubscriptionPlan plan) {
        PlanFeaturesDto features = plan == null
                ? fallbackFeatures()
                : PlanFeaturesDto.builder()
                        .allowRestaurant(Boolean.TRUE.equals(plan.getAllowRestaurant()))
                        .allowFashion(Boolean.TRUE.equals(plan.getAllowFashion()))
                        .allowAnalytics(Boolean.TRUE.equals(plan.getAllowAnalytics()))
                        .allowKitchen(Boolean.TRUE.equals(plan.getAllowKitchen()))
                        .allowTableOrder(Boolean.TRUE.equals(plan.getAllowTableOrder()))
                        .build();
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

    private PlanLimitsDto limitsFrom(SubscriptionPlan plan) {
        return PlanLimitsDto.builder()
                .maxStaff(plan.getMaxStaff())
                .maxProducts(plan.getMaxProducts())
                .maxReceiptsPerMonth(plan.getMaxReceiptsPerMonth())
                .maxStorageMb(plan.getMaxStorageMb())
                .maxDevices(plan.getMaxDevices())
                .maxBranches(plan.getMaxBranches())
                .build();
    }

    private PlanFeaturesDto fallbackFeatures() {
        return PlanFeaturesDto.builder()
                .allowRestaurant(false)
                .allowFashion(false)
                .allowAnalytics(false)
                .allowKitchen(false)
                .allowTableOrder(false)
                .build();
    }

    private PlanLimitsDto fallbackLimits() {
        return PlanLimitsDto.builder()
                .maxStaff(2)
                .maxProducts(100)
                .maxReceiptsPerMonth(300)
                .maxStorageMb(500)
                .maxDevices(1)
                .maxBranches(1)
                .build();
    }

    private PlanUsageDto currentUsage(Long shopId) {
        if (shopId == null) {
            return PlanUsageDto.builder().staffCount(0).productCount(0).receiptCount(0).deviceCount(0).build();
        }
        YearMonth month = YearMonth.now();
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
        return PlanUsageDto.builder()
                .deviceCount(sessions.activeDevices(shopId))
                .staffCount(toIntCount(staffRepository.countByShopId(shopId)))
                .productCount(toIntCount(productRepository.countByShopId(shopId)))
                .receiptCount(toIntCount(receiptRepository.countByShopIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(shopId, start, end)))
                .build();
    }

    private int toIntCount(long count) {
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    private String businessTypeName(User user) {
        return user.getBusinessType() == null ? null : user.getBusinessType().name();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
