package com.binhlaig.pos.auth.passwordreset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import jakarta.persistence.LockModeType;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetToken> findByTokenHashAndUsedFalseAndExpiresAtAfter(
            String tokenHash, OffsetDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordResetToken token set token.used = true " +
            "where token.user.id = :userId and token.used = false")
    int invalidateUnusedByUserId(@Param("userId") Long userId);
}
