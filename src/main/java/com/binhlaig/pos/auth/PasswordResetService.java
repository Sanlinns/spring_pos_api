package com.binhlaig.pos.auth;

import com.binhlaig.pos.auth.dto.ForgotPasswordRequest;
import com.binhlaig.pos.auth.dto.PasswordResetResponse;
import com.binhlaig.pos.auth.dto.ResetPasswordRequest;
import com.binhlaig.pos.auth.dto.ResetTokenValidationResponse;
import com.binhlaig.pos.auth.email.EmailDeliveryException;
import com.binhlaig.pos.auth.email.PasswordResetEmailService;
import com.binhlaig.pos.auth.passwordreset.PasswordResetToken;
import com.binhlaig.pos.auth.passwordreset.PasswordResetTokenRepository;
import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    static final String FORGOT_MESSAGE = "If the account exists, a password reset link has been sent.";
    static final String RESET_MESSAGE = "Password reset successful.";
    static final String INVALID_TOKEN_MESSAGE = "Invalid or expired password reset token.";
    static final String INVALID_LINK_MESSAGE =
            "This password reset link is invalid, expired, or has already been used.";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetEmailService emailService;

    @Transactional
    public PasswordResetResponse forgotPassword(ForgotPasswordRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);

        // TODO: Apply per-IP and per-account rate limiting before email delivery is integrated.
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .or(() -> userRepository.findByUsernameIgnoreCase(normalizedEmail))
                .orElse(null);
        if (user == null) {
            return new PasswordResetResponse(FORGOT_MESSAGE);
        }

        tokenRepository.invalidateUnusedByUserId(user.getId());

        String rawToken = generateToken();
        tokenRepository.save(PasswordResetToken.builder()
                .user(user)
                .tokenHash(hashToken(rawToken))
                .expiresAt(OffsetDateTime.now().plusMinutes(15))
                .used(false)
                .build());

        try {
            String recipient = user.getEmail() == null || user.getEmail().isBlank()
                    ? user.getUsername()
                    : user.getEmail();
            emailService.sendPasswordResetEmail(recipient, rawToken);
        } catch (EmailDeliveryException ex) {
            // The public response must not reveal account existence or provider failures.
            log.error("Password reset email delivery failed; provider details were suppressed.");
        }
        return new PasswordResetResponse(FORGOT_MESSAGE);
    }

    @Transactional
    public PasswordResetResponse resetPassword(ResetPasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("New password and confirmation do not match.");
        }

        PasswordResetToken resetToken = findAvailableToken(request.token())
                .orElseThrow(() -> new IllegalArgumentException(INVALID_TOKEN_MESSAGE));

        User user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        resetToken.setUsed(true);
        tokenRepository.saveAndFlush(resetToken);
        tokenRepository.invalidateUnusedByUserId(user.getId());
        sendPasswordChangedEmailAfterCommit(user.getEmail());
        return new PasswordResetResponse(RESET_MESSAGE);
    }

    @Transactional
    public ResetTokenValidationResponse validateResetToken(String rawToken) {
        boolean valid = rawToken != null
                && !rawToken.isBlank()
                && findAvailableToken(rawToken).isPresent();
        return new ResetTokenValidationResponse(
                valid,
                valid ? null : INVALID_LINK_MESSAGE);
    }

    private Optional<PasswordResetToken> findAvailableToken(String rawToken) {
        return tokenRepository.findByTokenHashAndUsedFalseAndExpiresAtAfter(
                hashToken(rawToken), OffsetDateTime.now());
    }

    private void sendPasswordChangedEmailAfterCommit(String recipientEmail) {
        if (recipientEmail == null || recipientEmail.isBlank()) {
            log.warn("Password changed confirmation email was not sent because the account has no registered email.");
            return;
        }

        Runnable sendConfirmation = () -> {
            try {
                emailService.sendPasswordChangedEmail(recipientEmail);
            } catch (EmailDeliveryException ex) {
                log.error("Password changed successfully, but confirmation email delivery failed.");
            }
        };

        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            sendConfirmation.run();
                        }
                    });
        } else {
            sendConfirmation.run();
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
