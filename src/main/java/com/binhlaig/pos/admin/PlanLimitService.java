package com.binhlaig.pos.admin;

import com.binhlaig.pos.admin.dto.EffectiveLimitsResponse;
import com.binhlaig.pos.modules.product.ProductRepository;
import com.binhlaig.pos.receipt.repository.PosReceiptRepository;
import com.binhlaig.pos.shopfeature.FeatureDisabledException;
import com.binhlaig.pos.shopfeature.ShopFeatureRepository;
import com.binhlaig.pos.staff.repository.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlanLimitService {

    private static final String STAFF_LIMIT_MESSAGE = "သင့် plan မှာ staff limit ပြည့်သွားပါပြီ။ Plan upgrade လုပ်ပါ။";
    private static final String PRODUCT_LIMIT_MESSAGE = "သင့် plan မှာ products limit ပြည့်သွားပါပြီ။ Plan upgrade လုပ်ပါ။";
    private static final String RECEIPT_LIMIT_MESSAGE = "သင့် plan မှာ monthly receipts limit ပြည့်သွားပါပြီ။ Plan upgrade လုပ်ပါ။";

    private final ShopRepository shopRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final ShopUsageMonthlyRepository usageRepository;
    private final ShopPlanOverrideRepository overrideRepository;
    private final StaffRepository staffRepository;
    private final ProductRepository productRepository;
    private final PosReceiptRepository receiptRepository;
    private final ShopFeatureRepository shopFeatureRepository;
    private final com.binhlaig.pos.auth.session.SessionStore sessions;

    @Transactional(readOnly = true)
    public SubscriptionPlan getCurrentPlan(Long shopId) {
        return getCurrentPlanForShop(shopId);
    }

    @Transactional(readOnly = true)
    public SubscriptionPlan getCurrentPlanForShop(Long shopId) {
        Shop shop = findShop(shopId);
        assertShopCanUsePos(shop);
        String planCode = getPlanCode(shop);
        return subscriptionPlanRepository.findByCode(planCode)
                .or(() -> subscriptionPlanRepository.findByCode("TRIAL"))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Subscription plan not found: " + planCode));
    }

    @Transactional(readOnly = true)
    public EffectiveLimitsResponse getEffectiveLimits(Long shopId) {
        SubscriptionPlan plan = getCurrentPlanForShop(shopId);
        ShopPlanOverride override = overrideRepository.findByShopIdAndActiveTrue(shopId).orElse(null);
        return toEffectiveLimits(plan, override);
    }

    @Transactional(readOnly = true)
    public void assertShopCanUsePos(Long shopId) {
        assertShopCanUsePos(findShop(shopId));
    }

    @Transactional
    public ShopUsageMonthly getCurrentUsage(Long shopId) {
        ShopUsageMonthly usage = getOrCreateCurrentMonthUsage(shopId);
        usage.setDeviceCount(sessions.activeDevices(shopId));
        return usage;
    }

    @Transactional
    public ShopUsageMonthly refreshUsage(Long shopId) {
        ShopUsageMonthly usage = getOrCreateCurrentMonthUsage(shopId);
        YearMonth currentMonth = YearMonth.of(usage.getYear(), usage.getMonth());
        LocalDateTime start = currentMonth.atDay(1).atStartOfDay();
        LocalDateTime end = currentMonth.plusMonths(1).atDay(1).atStartOfDay();

        usage.setStaffCount(toIntCount(staffRepository.countByShopId(shopId)));
        usage.setProductCount(toIntCount(productRepository.countByShopId(shopId)));
        usage.setReceiptCount(toIntCount(receiptRepository.countByShopIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(shopId, start, end)));
        if (usage.getStorageUsedMb() == null) {
            usage.setStorageUsedMb(0);
        }
        usage.setDeviceCount(sessions.activeDevices(shopId));
        return usageRepository.save(usage);
    }

    @Transactional
    public ShopUsageMonthly resetCurrentUsage(Long shopId) {
        ShopUsageMonthly usage = getOrCreateCurrentMonthUsage(shopId);
        usage.setStaffCount(0);
        usage.setProductCount(0);
        usage.setReceiptCount(0);
        usage.setStorageUsedMb(0);
        usage.setDeviceCount(sessions.activeDevices(shopId));
        return usageRepository.save(usage);
    }

    @Transactional
    public void assertCanCreateStaff(Long shopId) {
        assertShopCanUsePos(shopId);
        EffectiveLimitsResponse limits = getEffectiveLimits(shopId);
        ShopUsageMonthly usage = refreshUsage(shopId);
        assertBelowLimit(usage.getStaffCount(), limits.maxStaff(), STAFF_LIMIT_MESSAGE);
    }

    @Transactional
    public void assertCanCreateProduct(Long shopId) {
        assertShopCanUsePos(shopId);
        EffectiveLimitsResponse limits = getEffectiveLimits(shopId);
        ShopUsageMonthly usage = refreshUsage(shopId);
        assertBelowLimit(usage.getProductCount(), limits.maxProducts(), PRODUCT_LIMIT_MESSAGE);
    }

    @Transactional
    public void assertCanCreateReceipt(Long shopId) {
        assertShopCanUsePos(shopId);
        EffectiveLimitsResponse limits = getEffectiveLimits(shopId);
        ShopUsageMonthly usage = refreshUsage(shopId);
        assertBelowLimit(usage.getReceiptCount(), limits.maxReceiptsPerMonth(), RECEIPT_LIMIT_MESSAGE);
    }

    @Transactional(readOnly = true)
    public void assertCanUseRestaurant(Long shopId) {
        Shop shop = findShop(shopId);
        SubscriptionPlan plan = getCurrentPlanForShop(shopId);
        boolean enabled = Boolean.TRUE.equals(plan.getAllowRestaurant())
                || shopFeatureRepository.findByShopId(shopId)
                .map(feature -> Boolean.TRUE.equals(feature.getRestaurantPosEnabled())
                        || Boolean.TRUE.equals(feature.getAllowRestaurant()))
                .orElse(false);
        log.info("Plan feature guard shopId={} shopCode={} featureName={} value={}",
                shopId, shop.getShopCode(), "RESTAURANT", enabled);
        if (!enabled) {
            throw new FeatureDisabledException();
        }
    }

    @Transactional(readOnly = true)
    public void assertCanUseKitchen(Long shopId) {
        Shop shop = findShop(shopId);
        SubscriptionPlan plan = getCurrentPlanForShop(shopId);
        boolean enabled = Boolean.TRUE.equals(plan.getAllowKitchen())
                || shopFeatureRepository.findByShopId(shopId)
                .map(feature -> Boolean.TRUE.equals(feature.getRestaurantKitchenEnabled())
                        || Boolean.TRUE.equals(feature.getAllowKitchen()))
                .orElse(false);
        log.info("Plan feature guard shopId={} shopCode={} featureName={} value={}",
                shopId, shop.getShopCode(), "KITCHEN", enabled);
        if (!enabled) {
            throw new FeatureDisabledException();
        }
    }

    @Transactional(readOnly = true)
    public void assertCanUseAnalytics(Long shopId) {
        SubscriptionPlan plan = getCurrentPlanForShop(shopId);
        if (!Boolean.TRUE.equals(plan.getAllowAnalytics())) {
            throw new FeatureDisabledException();
        }
    }

    @Transactional(readOnly = true)
    public void assertCanUseTableOrder(Long shopId) {
        Shop shop = findShop(shopId);
        SubscriptionPlan plan = getCurrentPlanForShop(shopId);
        boolean enabled = Boolean.TRUE.equals(plan.getAllowTableOrder())
                || shopFeatureRepository.findByShopId(shopId)
                .map(feature -> Boolean.TRUE.equals(feature.getRestaurantTablesEnabled())
                        || Boolean.TRUE.equals(feature.getAllowTableOrder()))
                .orElse(false);
        log.info("Plan feature guard shopId={} shopCode={} featureName={} value={}",
                shopId, shop.getShopCode(), "TABLE_ORDER", enabled);
        if (!enabled) {
            throw new FeatureDisabledException();
        }
    }

    @Transactional
    public ShopUsageMonthly getOrCreateCurrentMonthUsage(Long shopId) {
        findShop(shopId);
        YearMonth currentMonth = YearMonth.now();
        return usageRepository.findByShopIdAndYearAndMonth(shopId, currentMonth.getYear(), currentMonth.getMonthValue())
                .orElseGet(() -> usageRepository.save(ShopUsageMonthly.builder()
                        .shopId(shopId)
                        .year(currentMonth.getYear())
                        .month(currentMonth.getMonthValue())
                        .staffCount(0)
                        .productCount(0)
                        .receiptCount(0)
                        .storageUsedMb(0)
                        .deviceCount(sessions.activeDevices(shopId))
                        .build()));
    }

    @Transactional(readOnly = true)
    public Shop findShop(Long shopId) {
        return shopRepository.findById(shopId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Shop not found"));
    }

    @Transactional(readOnly = true)
    public ShopPlanOverride getActiveOverride(Long shopId) {
        return overrideRepository.findByShopIdAndActiveTrue(shopId).orElse(null);
    }

    private EffectiveLimitsResponse toEffectiveLimits(SubscriptionPlan plan, ShopPlanOverride override) {
        return new EffectiveLimitsResponse(
                override != null && override.getMaxStaff() != null ? override.getMaxStaff() : plan.getMaxStaff(),
                override != null && override.getMaxProducts() != null ? override.getMaxProducts() : plan.getMaxProducts(),
                override != null && override.getMaxReceiptsPerMonth() != null ? override.getMaxReceiptsPerMonth() : plan.getMaxReceiptsPerMonth(),
                override != null && override.getMaxStorageMb() != null ? override.getMaxStorageMb() : plan.getMaxStorageMb(),
                override != null && override.getMaxDevices() != null ? override.getMaxDevices() : plan.getMaxDevices(),
                plan.getMaxBranches()
        );
    }

    private String getPlanCode(Shop shop) {
        return shop.getSubscriptionPlan() == null || shop.getSubscriptionPlan().isBlank()
                ? "TRIAL"
                : shop.getSubscriptionPlan().trim().toUpperCase();
    }

    private void assertShopCanUsePos(Shop shop) {
        ShopStatus status = shop.getStatus() == null ? ShopStatus.TRIAL : shop.getStatus();
        if (status == ShopStatus.SUSPENDED) {
            throw new IllegalArgumentException("သင့်ဆိုင် account ကို ယာယီပိတ်ထားပါသည်။");
        }
        if (status == ShopStatus.CANCELLED) {
            throw new IllegalArgumentException("သင့် POS account ကို ပိတ်ထားပါသည်။");
        }
        if (status == ShopStatus.EXPIRED || isExpired(shop)) {
            throw new IllegalArgumentException("သင့် POS plan သက်တမ်းကုန်ဆုံးသွားပါပြီ။");
        }
    }

    private boolean isExpired(Shop shop) {
        return shop.getSubscriptionEndDate() != null
                && shop.getSubscriptionEndDate().isBefore(LocalDate.now());
    }

    private void assertBelowLimit(Integer usage, Integer limit, String message) {
        if (limit != null && usage != null && usage >= limit) {
            throw new IllegalArgumentException(message);
        }
    }

    private int toIntCount(long count) {
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }
}
