package com.binhlaig.pos.auth;

import com.binhlaig.pos.auth.dto.ForgotPasswordRequest;
import com.binhlaig.pos.auth.dto.PasswordResetResponse;
import com.binhlaig.pos.auth.dto.ResetPasswordRequest;
import com.binhlaig.pos.auth.email.EmailDeliveryException;
import com.binhlaig.pos.auth.email.PasswordResetEmailService;
import com.binhlaig.pos.auth.passwordreset.PasswordResetToken;
import com.binhlaig.pos.auth.passwordreset.PasswordResetTokenRepository;
import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordResetTokenRepository tokenRepository;
    @Mock PasswordResetEmailService emailService;
    @Mock com.binhlaig.pos.auth.session.SessionService sessions;

    private PasswordEncoder passwordEncoder;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        service = new PasswordResetService(
                userRepository, tokenRepository, passwordEncoder, emailService, sessions);
    }

    @Test
    void forgotPasswordForExistingAccountInvalidatesOldTokensAndStoresOnlyHash() {
        User user = User.builder().id(7L).username("owner").email("owner@example.com").build();
        when(userRepository.findByEmailIgnoreCase("owner@example.com"))
                .thenReturn(Optional.of(user));
        PasswordResetResponse response = service.forgotPassword(
                new ForgotPasswordRequest("  Owner@Example.COM "));

        ArgumentCaptor<PasswordResetToken> tokenCaptor =
                ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).invalidateUnusedByUserId(7L);
        verify(tokenRepository).save(tokenCaptor.capture());

        PasswordResetToken stored = tokenCaptor.getValue();
        ArgumentCaptor<String> rawTokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetEmail(
                eq("owner@example.com"), rawTokenCaptor.capture());
        String rawToken = rawTokenCaptor.getValue();

        assertThat(response.message()).isEqualTo(PasswordResetService.FORGOT_MESSAGE);
        assertThat(stored.getTokenHash())
                .isEqualTo(PasswordResetService.hashToken(rawToken))
                .doesNotContain(rawToken);
        assertThat(stored.getExpiresAt()).isAfter(OffsetDateTime.now().plusMinutes(14));
        assertThat(stored.isUsed()).isFalse();
    }

    @Test
    void forgotPasswordForMissingAccountReturnsSameGenericMessageWithoutToken() {
        when(userRepository.findByEmailIgnoreCase("missing@example.com"))
                .thenReturn(Optional.empty());
        when(userRepository.findByUsernameIgnoreCase("missing@example.com"))
                .thenReturn(Optional.empty());

        PasswordResetResponse response = service.forgotPassword(
                new ForgotPasswordRequest("missing@example.com"));

        assertThat(response.message()).isEqualTo(PasswordResetService.FORGOT_MESSAGE);
        verifyNoInteractions(tokenRepository);
        verifyNoInteractions(emailService);
    }

    @Test
    void resendFailureKeepsGenericResponseAndDoesNotExposeSensitiveDetails() {
        User user = User.builder().id(7L).username("owner@example.com").build();
        when(userRepository.findByEmailIgnoreCase("owner@example.com"))
                .thenReturn(Optional.of(user));
        doThrow(new EmailDeliveryException(
                new RuntimeException("provider rejected api-key-and-token")))
                .when(emailService).sendPasswordResetEmail(eq("owner@example.com"), anyString());

        PasswordResetResponse response = service.forgotPassword(
                new ForgotPasswordRequest("owner@example.com"));

        assertThat(response.message())
                .isEqualTo(PasswordResetService.FORGOT_MESSAGE)
                .doesNotContain("provider", "api-key", "token");
        verify(tokenRepository).save(any(PasswordResetToken.class));
    }

    @Test
    void validTokenResetsPasswordMarksTokenUsedAndInvalidatesOthers() {
        String rawToken = "secure-raw-reset-token";
        User user = User.builder().id(7L).email("owner@example.com").password("old-hash").build();
        PasswordResetToken token = PasswordResetToken.builder()
                .id(11L)
                .user(user)
                .tokenHash(PasswordResetService.hashToken(rawToken))
                .expiresAt(OffsetDateTime.now().plusMinutes(10))
                .build();
        when(tokenRepository.findByTokenHashAndUsedFalseAndExpiresAtAfter(
                eq(PasswordResetService.hashToken(rawToken)), any(OffsetDateTime.class)))
                .thenReturn(Optional.of(token));
        lenient().when(tokenRepository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));

        PasswordResetResponse response = service.resetPassword(
                new ResetPasswordRequest(rawToken, "NewPassword123!", "NewPassword123!"));

        assertThat(response.message()).isEqualTo(PasswordResetService.RESET_MESSAGE);
        assertThat(user.getPassword()).isNotEqualTo("NewPassword123!");
        assertThat(passwordEncoder.matches("NewPassword123!", user.getPassword())).isTrue();
        assertThat(token.isUsed()).isTrue();
        verify(userRepository).save(user);
        verify(sessions).lockAccount(user);
        verify(sessions).revokeUser(user);
        verify(tokenRepository).saveAndFlush(token);
        verify(tokenRepository).invalidateUnusedByUserId(7L);
        verify(emailService).sendPasswordChangedEmail("owner@example.com");
    }

    @Test
    void secondAttemptWithSameTokenIsRejected() {
        String rawToken = "single-use-token";
        User user = User.builder().id(7L).email("owner@example.com").password("old-hash").build();
        PasswordResetToken token = PasswordResetToken.builder()
                .user(user)
                .tokenHash(PasswordResetService.hashToken(rawToken))
                .expiresAt(OffsetDateTime.now().plusMinutes(10))
                .build();
        when(tokenRepository.findByTokenHashAndUsedFalseAndExpiresAtAfter(
                eq(PasswordResetService.hashToken(rawToken)), any(OffsetDateTime.class)))
                .thenReturn(Optional.of(token), Optional.empty());
        when(tokenRepository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));

        service.resetPassword(new ResetPasswordRequest(
                rawToken, "NewPassword123!", "NewPassword123!"));

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(
                rawToken, "AnotherPassword123!", "AnotherPassword123!")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PasswordResetService.INVALID_TOKEN_MESSAGE);
        assertThat(passwordEncoder.matches("NewPassword123!", user.getPassword())).isTrue();
        verify(emailService, times(1)).sendPasswordChangedEmail("owner@example.com");
    }

    @Test
    void invalidTokenIsRejectedSafely() {
        rejectUnavailableToken("unknown-token");
    }

    @Test
    void validUnusedTokenReturnsValid() {
        String rawToken = "valid-unused-token";
        PasswordResetToken token = PasswordResetToken.builder()
                .tokenHash(PasswordResetService.hashToken(rawToken))
                .expiresAt(OffsetDateTime.now().plusMinutes(5))
                .used(false)
                .build();
        when(tokenRepository.findByTokenHashAndUsedFalseAndExpiresAtAfter(
                eq(PasswordResetService.hashToken(rawToken)), any(OffsetDateTime.class)))
                .thenReturn(Optional.of(token));
        lenient().when(tokenRepository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));

        var response = service.validateResetToken(rawToken);

        assertThat(response.valid()).isTrue();
        assertThat(response.message()).isNull();
    }

    @Test
    void randomTokenValidationReturnsInvalid() {
        when(tokenRepository.findByTokenHashAndUsedFalseAndExpiresAtAfter(
                eq(PasswordResetService.hashToken("random-token")), any(OffsetDateTime.class)))
                .thenReturn(Optional.empty());

        var response = service.validateResetToken("random-token");

        assertThat(response.valid()).isFalse();
        assertThat(response.message()).isEqualTo(PasswordResetService.INVALID_LINK_MESSAGE);
    }

    @Test
    void expiredTokenIsRejectedSafely() {
        String rawToken = "expired-token";
        PasswordResetToken expired = PasswordResetToken.builder()
                .expiresAt(OffsetDateTime.now().minusSeconds(1))
                .build();
        when(tokenRepository.findByTokenHashAndUsedFalseAndExpiresAtAfter(
                eq(PasswordResetService.hashToken(rawToken)), any(OffsetDateTime.class)))
                .thenAnswer(invocation -> expired.getExpiresAt()
                        .isAfter(invocation.getArgument(1)) ? Optional.of(expired) : Optional.empty());

        assertInvalidToken(rawToken);
        assertThat(service.validateResetToken(rawToken).valid()).isFalse();
    }

    @Test
    void alreadyUsedTokenIsRejectedSafely() {
        String rawToken = "used-token";
        PasswordResetToken used = PasswordResetToken.builder()
                .used(true)
                .expiresAt(OffsetDateTime.now().plusMinutes(5))
                .build();
        when(tokenRepository.findByTokenHashAndUsedFalseAndExpiresAtAfter(
                eq(PasswordResetService.hashToken(rawToken)), any(OffsetDateTime.class)))
                .thenReturn(used.isUsed() ? Optional.empty() : Optional.of(used));

        assertInvalidToken(rawToken);
        assertThat(service.validateResetToken(rawToken).valid()).isFalse();
    }

    @Test
    void successfulResetMakesValidationAndSecondResetFail() {
        String rawToken = "single-use-validation-token";
        User user = User.builder()
                .id(7L)
                .email("owner@example.com")
                .password("old-hash")
                .build();
        PasswordResetToken token = PasswordResetToken.builder()
                .user(user)
                .tokenHash(PasswordResetService.hashToken(rawToken))
                .expiresAt(OffsetDateTime.now().plusMinutes(10))
                .build();
        when(tokenRepository.findByTokenHashAndUsedFalseAndExpiresAtAfter(
                eq(PasswordResetService.hashToken(rawToken)), any(OffsetDateTime.class)))
                .thenReturn(Optional.of(token), Optional.empty(), Optional.empty());
        when(tokenRepository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));

        service.resetPassword(new ResetPasswordRequest(
                rawToken, "NewPassword123!", "NewPassword123!"));

        assertThat(token.isUsed()).isTrue();
        assertThat(service.validateResetToken(rawToken).valid()).isFalse();
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(
                rawToken, "AnotherPassword123!", "AnotherPassword123!")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PasswordResetService.INVALID_TOKEN_MESSAGE);
    }

    @Test
    void mismatchedPasswordsAreRejectedBeforeTokenLookup() {
        assertThatThrownBy(() -> service.resetPassword(
                new ResetPasswordRequest("token", "NewPassword123!", "Different123!")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("New password and confirmation do not match.");

        verifyNoInteractions(tokenRepository, userRepository);
        verify(emailService, never()).sendPasswordChangedEmail(anyString());
    }

    private void rejectUnavailableToken(String rawToken) {
        assertInvalidToken(rawToken);
    }

    private void assertInvalidToken(String rawToken) {
        assertThatThrownBy(() -> service.resetPassword(
                new ResetPasswordRequest(rawToken, "NewPassword123!", "NewPassword123!")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PasswordResetService.INVALID_TOKEN_MESSAGE);

        verify(userRepository, never()).save(any());
        verify(tokenRepository, never()).saveAndFlush(any());
        verify(emailService, never()).sendPasswordChangedEmail(anyString());
    }

    @Test
    void confirmationEmailFailureDoesNotUndoSuccessfulPasswordReset() {
        String rawToken = "confirmation-failure-token";
        User user = User.builder()
                .id(7L)
                .email("owner@example.com")
                .password("old-hash")
                .build();
        PasswordResetToken token = PasswordResetToken.builder()
                .user(user)
                .tokenHash(PasswordResetService.hashToken(rawToken))
                .expiresAt(OffsetDateTime.now().plusMinutes(10))
                .build();
        when(tokenRepository.findByTokenHashAndUsedFalseAndExpiresAtAfter(
                eq(PasswordResetService.hashToken(rawToken)), any(OffsetDateTime.class)))
                .thenReturn(Optional.of(token));
        lenient().when(tokenRepository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));
        doThrow(new EmailDeliveryException(new RuntimeException("provider failure")))
                .when(emailService).sendPasswordChangedEmail("owner@example.com");

        PasswordResetResponse response = service.resetPassword(new ResetPasswordRequest(
                rawToken, "NewPassword123!", "NewPassword123!"));

        assertThat(response.message()).isEqualTo(PasswordResetService.RESET_MESSAGE);
        assertThat(passwordEncoder.matches("NewPassword123!", user.getPassword())).isTrue();
        assertThat(token.isUsed()).isTrue();
        verify(userRepository).save(user);
        verify(tokenRepository).saveAndFlush(token);
        verify(tokenRepository).invalidateUnusedByUserId(7L);
    }
}
