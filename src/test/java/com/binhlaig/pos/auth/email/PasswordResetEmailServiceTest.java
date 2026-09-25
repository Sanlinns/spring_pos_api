package com.binhlaig.pos.auth.email;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PasswordResetEmailServiceTest {

    @Test
    void sendsProfessionalResetEmailThroughResendWithRawTokenOnlyInUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PasswordResetEmailService service = service(builder, "test-api-key", "https://pos.binhlaig.com/");

        server.expect(once(), requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-api-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.from").value("Binhlaig POS <no-reply@binhlaig.com>"))
                .andExpect(jsonPath("$.to[0]").value("customer@example.com"))
                .andExpect(jsonPath("$.subject").value("Reset your Binhlaig POS password"))
                .andExpect(jsonPath("$.html").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("Password Reset"),
                        org.hamcrest.Matchers.containsString("expires in 15 minutes"),
                        org.hamcrest.Matchers.containsString(
                                "https://pos.binhlaig.com/reset-password?token=raw-reset-token"))))
                .andRespond(withSuccess("{\"id\":\"email-id\"}", MediaType.APPLICATION_JSON));

        service.sendPasswordResetEmail("customer@example.com", "raw-reset-token");

        server.verify();
    }

    @Test
    void resetUrlContainsEncodedRawTemporaryToken() {
        PasswordResetEmailService service = service(
                RestClient.builder(), "test-api-key", "https://pos.binhlaig.com");

        assertThat(service.buildResetUrl("raw token/+"))
                .isEqualTo("https://pos.binhlaig.com/reset-password?token=raw%20token/+");
    }

    @Test
    void sendsPasswordChangedConfirmationWithoutSensitiveValues() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PasswordResetEmailService service = service(
                builder, "test-api-key", "http://localhost:3000/");

        server.expect(once(), requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-api-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.from").value("Binhlaig POS <no-reply@binhlaig.com>"))
                .andExpect(jsonPath("$.to[0]").value("customer@example.com"))
                .andExpect(jsonPath("$.subject")
                        .value("Your Binhlaig POS password was changed"))
                .andExpect(jsonPath("$.html").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("Password Changed Successfully"),
                        org.hamcrest.Matchers.containsString("http://localhost:3000/login"),
                        org.hamcrest.Matchers.containsString("Sign in to Binhlaig POS"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("NewPassword123!")),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("raw-reset-token")))))
                .andRespond(withSuccess("{\"id\":\"email-id\"}", MediaType.APPLICATION_JSON));

        service.sendPasswordChangedEmail("customer@example.com");

        server.verify();
        assertThat(service.buildLoginUrl()).isEqualTo("http://localhost:3000/login");
    }

    @Test
    void providerFailureUsesInternalExceptionWithoutResponseDetails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PasswordResetEmailService service = service(builder, "secret-api-key", "http://localhost:3000");
        server.expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withServerError().body("provider detail containing a secret"));

        assertThatThrownBy(() -> service.sendPasswordResetEmail(
                "customer@example.com", "sensitive-raw-token"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessage("Password reset email delivery failed")
                .message().doesNotContain("secret-api-key", "sensitive-raw-token", "provider detail");
    }

    @Test
    void productionProfileRequiresResendApiKey() {
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(org.mockito.ArgumentMatchers.any(Profiles.class)))
                .thenReturn(true);
        PasswordResetEmailService service = new PasswordResetEmailService(
                RestClient.builder(),
                environment,
                "",
                "no-reply@binhlaig.com",
                "https://pos.binhlaig.com");

        assertThatThrownBy(service::validateProductionConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_API_KEY");
    }

    private PasswordResetEmailService service(
            RestClient.Builder builder, String apiKey, String frontendUrl) {
        return new PasswordResetEmailService(
                builder,
                mock(Environment.class),
                apiKey,
                "no-reply@binhlaig.com",
                frontendUrl);
    }
}
