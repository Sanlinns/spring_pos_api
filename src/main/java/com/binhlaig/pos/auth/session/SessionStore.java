package com.binhlaig.pos.auth.session;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class SessionStore {
    private final JdbcTemplate jdbc;

    public record Session(UUID id, String accountType, Long accountId, Long shopId,
                          String deviceId, String deviceName, String hash, OffsetDateTime expiresAt,
                          OffsetDateTime lastSeenAt, OffsetDateTime revokedAt, String revocationReason) {
        public boolean active() { return revokedAt == null && expiresAt.isAfter(OffsetDateTime.now()); }
    }

    private final org.springframework.jdbc.core.RowMapper<Session> mapper = (rs, row) -> new Session(
            rs.getObject("id", UUID.class), rs.getString("account_type"), rs.getLong("account_id"),
            rs.getLong("shop_id"), rs.getString("device_id"), rs.getString("device_name"),
            rs.getString("refresh_token_hash"), rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("last_seen_at", OffsetDateTime.class), rs.getObject("revoked_at", OffsetDateTime.class),
            rs.getString("revocation_reason"));

    // All mutations acquire the shop row lock, across every application instance.
    public void lockShop(Long shopId) {
        jdbc.queryForObject("select id from shops where id = ? for update", Long.class, shopId);
    }
    public Optional<Session> find(UUID id) {
        return jdbc.query("select * from login_sessions where id = ?", mapper, id).stream().findFirst();
    }
    public Optional<Session> byHash(String hash) {
        return jdbc.query("select * from login_sessions where refresh_token_hash = ?", mapper, hash).stream().findFirst();
    }
    public Optional<Session> consumed(String hash) {
        return jdbc.query("select s.* from login_sessions s join consumed_refresh_tokens t on t.session_id=s.id where t.token_hash=?",
                mapper, hash).stream().findFirst();
    }
    public int activeDevices(Long shopId) {
        return jdbc.queryForObject("select count(distinct device_id) from login_sessions where shop_id=? and revoked_at is null and expires_at > ?",
                Integer.class, shopId, OffsetDateTime.now());
    }
    public List<Session> active(Long shopId) {
        return jdbc.query("select * from login_sessions where shop_id=? and revoked_at is null and expires_at > ? order by last_seen_at desc",
                mapper, shopId, OffsetDateTime.now());
    }
    public void insert(Session s) {
        jdbc.update("insert into login_sessions (id,account_type,account_id,shop_id,device_id,device_name,refresh_token_hash,created_at,expires_at,last_seen_at) values (?,?,?,?,?,?,?,?,?,?)",
                s.id(), s.accountType(), s.accountId(), s.shopId(), s.deviceId(), s.deviceName(), s.hash(),
                OffsetDateTime.now(), s.expiresAt(), s.lastSeenAt());
    }
    public void rotate(Session s, String nextHash) {
        jdbc.update("insert into consumed_refresh_tokens(token_hash,session_id,consumed_at) values (?,?,?)", s.hash(), s.id(), OffsetDateTime.now());
        jdbc.update("update login_sessions set refresh_token_hash=?, last_seen_at=? where id=?", nextHash, OffsetDateTime.now(), s.id());
    }
    public void revoke(UUID id, String reason) {
        jdbc.update("update login_sessions set revoked_at=?, revocation_reason=? where id=? and revoked_at is null", OffsetDateTime.now(), reason, id);
    }
    public void revokeUser(Long userId) {
        jdbc.update("update login_sessions set revoked_at=?, revocation_reason='PASSWORD_RESET' where account_type='USER' and account_id=? and revoked_at is null",
                OffsetDateTime.now(), userId);
    }
    public void touch(UUID id) {
        jdbc.update("update login_sessions set last_seen_at=? where id=? and revoked_at is null", OffsetDateTime.now(), id);
    }
}
