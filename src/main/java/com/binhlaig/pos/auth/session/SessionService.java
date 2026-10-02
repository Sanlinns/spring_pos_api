package com.binhlaig.pos.auth.session;

import com.binhlaig.pos.admin.PlanLimitService;
import com.binhlaig.pos.auth.JwtService;
import com.binhlaig.pos.staff.entity.Staff;
import com.binhlaig.pos.staff.repository.StaffRepository;
import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SessionService {
    private final SessionStore store;
    private final PlanLimitService plans;
    private final UserRepository users;
    private final StaffRepository staff;
    private final EntityManager em;
    private final JwtService jwt;
    @Value("${app.session.refresh-ttl-seconds:2592000}")
    private long ttlSeconds;
    private static final SecureRandom RANDOM = new SecureRandom();

    // Internal only: never serialize this result or log bearer credentials.
    public record Issued(SessionStore.Session session, @com.fasterxml.jackson.annotation.JsonIgnore String refreshToken) {
        @Override public String toString() { return "Issued[sessionId=" + session.id() + "]"; }
    }
    public static class InvalidRefresh extends ResponseStatusException {
        public InvalidRefresh() { super(HttpStatus.UNAUTHORIZED, "Invalid refresh token. Please sign in again."); }
    }
    public static class RefreshReplay extends InvalidRefresh {}

    public void lockAccount(Object account) {
        // Refresh discards any entity snapshot read before a competing reset committed.
        em.refresh(account, LockModeType.PESSIMISTIC_WRITE);
    }

    @Transactional
    public Issued create(String type, Long accountId, Long shopId, String deviceId, String deviceName) {
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId must be a stable installation identifier (16-128 letters, digits, _ or -)");
        }
        if (deviceName == null || deviceName.isBlank() || deviceName.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceName is required (maximum 200 characters)");
        }
        store.lockShop(shopId);
        plans.assertShopCanUsePos(shopId);
        var active = store.active(shopId);
        boolean existingDevice = active.stream().anyMatch(s -> s.deviceId().equals(deviceId));
        Integer max = plans.getEffectiveLimits(shopId).maxDevices();
        if (!existingDevice && max != null && store.activeDevices(shopId) >= max) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Device limit reached. Ask the Owner to revoke a device.");
        }
        // Installation metadata never authenticates an account or revokes a different account.
        active.stream().filter(s -> s.deviceId().equals(deviceId) && s.accountType().equals(type) && s.accountId().equals(accountId))
                .forEach(s -> store.revoke(s.id(), "REPLACED_BY_LOGIN"));
        String raw = randomToken();
        if (ttlSeconds <= 0) throw new IllegalStateException("Refresh TTL must be positive");
        var now = OffsetDateTime.now();
        var session = new SessionStore.Session(UUID.randomUUID(), type, accountId, shopId, deviceId,
                deviceName.trim(), hash(raw), now.plusSeconds(ttlSeconds), now, null, null);
        store.insert(session);
        return new Issued(session, raw);
    }

    @Transactional(noRollbackFor = RefreshReplay.class)
    public Issued refresh(String raw) {
        if (raw == null || raw.length() > 256 || raw.isBlank()) throw new InvalidRefresh();
        String hash = hash(raw);
        var snapshot = store.byHash(hash).or(() -> store.consumed(hash)).orElseThrow(InvalidRefresh::new);
        // Lock order: account -> shop -> sessions. Password reset uses the same order.
        Object account = "USER".equals(snapshot.accountType())
                ? users.findById(snapshot.accountId()).orElseThrow(InvalidRefresh::new)
                : staff.findById(snapshot.accountId()).orElseThrow(InvalidRefresh::new);
        lockAccount(account);
        store.lockShop(snapshot.shopId());
        var session = store.find(snapshot.id()).orElseThrow(InvalidRefresh::new);
        if (!session.hash().equals(hash)) {
            // This exception must NOT roll back replay revocation.
            store.revoke(session.id(), "REFRESH_REPLAY");
            throw new RefreshReplay();
        }
        if (!session.active()) throw new InvalidRefresh();
        if (account instanceof User u && (!Objects.equals(u.getShopId(), session.shopId()) || u.getRole() == null)) throw new InvalidRefresh();
        if (account instanceof Staff s && (!Objects.equals(s.getShopId(), session.shopId()) || "inactive".equalsIgnoreCase(s.getStatus() == null ? "" : s.getStatus().trim()))) throw new InvalidRefresh();
        try { plans.assertShopCanUsePos(session.shopId()); }
        catch (IllegalArgumentException | ResponseStatusException ex) { throw new InvalidRefresh(); }
        String next = randomToken();
        store.rotate(session, hash(next));
        return new Issued(session, next);
    }

    public boolean validAccess(String token, String type, Long accountId, Long shopId) {
        String sid = jwt.extractSessionId(token);
        if (sid == null) return false;
        var session = store.find(UUID.fromString(sid)).orElse(null);
        if (session == null || !session.active() || !session.accountType().equals(type)
                || !session.accountId().equals(accountId) || !session.shopId().equals(shopId)) return false;
        store.touch(session.id());
        return true;
    }

    @Transactional
    public void logout(String token) {
        var s = authenticatedSession(token);
        store.lockShop(s.shopId());
        store.revoke(s.id(), "LOGOUT");
    }
    public record DeviceSession(UUID sessionId, String accountType, Long accountId, String deviceId,
                                String deviceName, OffsetDateTime expiresAt, OffsetDateTime lastSeenAt) {}
    public List<DeviceSession> devices(String token, Long shopId) {
        ownerShop(token, shopId);
        return store.active(shopId).stream().map(s -> new DeviceSession(s.id(), s.accountType(), s.accountId(),
                s.deviceId(), s.deviceName(), s.expiresAt(), s.lastSeenAt())).toList();
    }
    @Transactional
    public void revokeDevice(String token, Long shopId, String deviceId) {
        ownerShop(token, shopId);
        store.lockShop(shopId);
        var sessions = store.active(shopId).stream().filter(s -> s.deviceId().equals(deviceId)).toList();
        if (sessions.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found in this shop");
        sessions.forEach(s -> store.revoke(s.id(), "OWNER_REMOTE_LOGOUT"));
    }
    private void ownerShop(String token, Long shopId) {
        var s = authenticatedSession(token);
        var user = "USER".equals(s.accountType()) ? users.findById(s.accountId()).orElse(null) : null;
        if (user == null || user.getRole() != com.binhlaig.pos.auth.Role.ADMIN || !s.shopId().equals(shopId)
                || !Objects.equals(user.getShopId(), shopId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner access to own shop required");
    }
    private SessionStore.Session authenticatedSession(String token) {
        String sid = jwt.extractSessionId(token);
        if (sid == null) throw new InvalidRefresh();
        var s = store.find(UUID.fromString(sid)).orElseThrow(InvalidRefresh::new);
        if (!s.active() || !s.accountType().equals(jwt.extractTokenType(token))
                || !s.accountId().equals(jwt.extractAccountId(token)) || !s.shopId().equals(jwt.extractShopId(token))) throw new InvalidRefresh();
        return s;
    }
    @Transactional
    public void revokeUser(User user) {
        store.lockShop(user.getShopId());
        store.revokeUser(user.getId());
    }
    static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String randomToken() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
