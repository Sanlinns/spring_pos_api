package com.binhlaig.pos.auth;

import com.binhlaig.pos.auth.dto.ResetPasswordRequest;
import com.binhlaig.pos.auth.dto.ResetTokenValidationRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResetPasswordRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void weakPasswordsAreRejectedByTheEndpointRequestPolicy() {
        assertThat(validator.validate(new ResetPasswordRequest(
                "raw-token", "password", "password")))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                        .isEqualTo("newPassword"));
    }

    @Test
    void strongPasswordsPassTheEndpointRequestPolicy() {
        assertThat(validator.validate(new ResetPasswordRequest(
                "raw-token", "NewPassword123!", "NewPassword123!")))
                .isEmpty();
    }

    @Test
    void blankResetTokenValidationRequestIsRejected() {
        assertThat(validator.validate(new ResetTokenValidationRequest("  ")))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                        .isEqualTo("token"));
    }
}
