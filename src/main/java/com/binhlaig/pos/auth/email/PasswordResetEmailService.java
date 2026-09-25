package com.binhlaig.pos.auth.email;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@Service
@Slf4j
public class PasswordResetEmailService {

    private static final String RESEND_EMAILS_URL = "https://api.resend.com/emails";

    private final RestClient restClient;
    private final Environment environment;
    private final String apiKey;
    private final String fromEmail;
    private final String frontendUrl;

    public PasswordResetEmailService(
            RestClient.Builder restClientBuilder,
            Environment environment,
            @Value("${app.mail.resend-api-key:}") String apiKey,
            @Value("${app.mail.from-email:no-reply@binhlaig.com}") String fromEmail,
            @Value("${app.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.restClient = restClientBuilder.build();
        this.environment = environment;
        this.apiKey = apiKey;
        this.fromEmail = fromEmail;
        this.frontendUrl = frontendUrl;
    }

    @PostConstruct
    void validateProductionConfiguration() {
        log.info(
                "Resend config loaded: apiKeyConfigured={}, fromEmail={}, frontendUrl={}",
                !apiKey.isBlank(),
                fromEmail,
                frontendUrl);

        if (apiKey.isBlank()
                && environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            throw new IllegalStateException(
                    "RESEND_API_KEY must be configured when the prod or production profile is active");
        }
    }

    public void sendPasswordResetEmail(String recipientEmail, String rawToken) {
        if (apiKey.isBlank()) {
            log.warn("Password reset email was not sent because RESEND_API_KEY is not configured.");
            return;
        }

        String resetUrl = buildResetUrl(rawToken);
        ResendEmailRequest request = new ResendEmailRequest(
                "Binhlaig POS <" + fromEmail + ">",
                List.of(recipientEmail),
                "Reset your Binhlaig POS password",
                buildHtml(resetUrl));

        send(request, "Password reset email");
    }

    public void sendPasswordChangedEmail(String recipientEmail) {
        if (apiKey.isBlank()) {
            log.warn("Password changed confirmation email was not sent because RESEND_API_KEY is not configured.");
            return;
        }

        String loginUrl = buildFrontendUrl("/login");
        ResendEmailRequest request = new ResendEmailRequest(
                "Binhlaig POS <" + fromEmail + ">",
                List.of(recipientEmail),
                "Your Binhlaig POS password was changed",
                buildPasswordChangedHtml(loginUrl));

        send(request, "Password changed confirmation email");
    }

    private void send(ResendEmailRequest request, String emailType) {
        try {
            var response = restClient.post()
                    .uri(RESEND_EMAILS_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .toEntity(String.class);

            log.info(
                    "{} accepted by Resend. status={}",
                    emailType,
                    response.getStatusCode());
        } catch (RestClientResponseException ex) {
            log.error(
                    "Resend email failed. status={}, response={}, type={}",
                    ex.getStatusCode(),
                    ex.getResponseBodyAsString(),
                    ex.getClass().getSimpleName());
            throw new EmailDeliveryException(ex);
        } catch (RestClientException ex) {
            log.error(
                    "Resend email request failed. type={}, message={}",
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
            throw new EmailDeliveryException(ex);
        }
    }

    String buildResetUrl(String rawToken) {
        return UriComponentsBuilder.fromUriString(frontendBaseUrl())
                .path("/reset-password")
                .queryParam("token", rawToken)
                .build()
                .encode()
                .toUriString();
    }

    String buildLoginUrl() {
        return buildFrontendUrl("/login");
    }

    private String buildFrontendUrl(String path) {
        return UriComponentsBuilder.fromUriString(frontendBaseUrl())
                .path(path)
                .build()
                .encode()
                .toUriString();
    }

    private String frontendBaseUrl() {
        return frontendUrl.endsWith("/")
                ? frontendUrl.substring(0, frontendUrl.length() - 1)
                : frontendUrl;
    }

    private String buildHtml(String resetUrl) {
        String safeUrl = HtmlUtils.htmlEscape(resetUrl);
        return """
                <!doctype html>
                <html lang="en">
                <body style="margin:0;background:#f4f6f8;font-family:Arial,sans-serif;color:#17202a">
                  <div style="max-width:600px;margin:32px auto;background:#ffffff;border-radius:10px;padding:40px">
                    <div style="font-size:20px;font-weight:700;color:#1f6f5f">Binhlaig POS</div>
                    <h1 style="font-size:28px;margin:28px 0 16px">Password Reset</h1>
                    <p style="line-height:1.6">We received a request to reset your Binhlaig POS password.</p>
                    <p style="margin:28px 0">
                      <a href="%s" style="display:inline-block;background:#1f6f5f;color:#ffffff;text-decoration:none;padding:13px 24px;border-radius:6px;font-weight:700">Reset Password</a>
                    </p>
                    <p style="line-height:1.6"><strong>This link expires in 15 minutes.</strong></p>
                    <p style="line-height:1.6">If you did not request this password reset, you can safely ignore this email.</p>
                    <p style="margin-top:28px;font-size:13px;color:#5d6d7e;line-height:1.5">If the button does not work, copy and paste this URL into your browser:<br><a href="%s" style="color:#1f6f5f;word-break:break-all">%s</a></p>
                  </div>
                </body>
                </html>
                """.formatted(safeUrl, safeUrl, safeUrl);
    }

    private String buildPasswordChangedHtml(String loginUrl) {
        String safeLoginUrl = HtmlUtils.htmlEscape(loginUrl);
        return """
                <!doctype html>
                <html lang="en">
                <body style="margin:0;background:#f4f6f8;font-family:Arial,sans-serif;color:#17202a">
                  <div style="max-width:600px;margin:32px auto;background:#ffffff;border-radius:10px;padding:40px">
                    <div style="font-size:20px;font-weight:700;color:#1f6f5f">Binhlaig POS</div>
                    <h1 style="font-size:28px;margin:28px 0 16px">Password Changed Successfully</h1>
                    <p style="line-height:1.6">Your Binhlaig POS account password was changed successfully.</p>
                    <p style="line-height:1.6">If you made this change, no further action is required.</p>
                    <p style="line-height:1.6">If you did not change your password, please reset your password immediately and contact support.</p>
                    <p style="margin:28px 0">
                      <a href="%s" style="display:inline-block;background:#1f6f5f;color:#ffffff;text-decoration:none;padding:13px 24px;border-radius:6px;font-weight:700">Sign in to Binhlaig POS</a>
                    </p>
                    <p style="font-size:13px;color:#5d6d7e;line-height:1.5">For security, we will never send your password by email.</p>
                  </div>
                </body>
                </html>
                """.formatted(safeLoginUrl);
    }

    private record ResendEmailRequest(
            String from,
            List<String> to,
            String subject,
            String html) {
    }
}
