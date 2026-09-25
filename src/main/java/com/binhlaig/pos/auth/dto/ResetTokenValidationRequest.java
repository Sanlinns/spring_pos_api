package com.binhlaig.pos.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ResetTokenValidationRequest(
        @NotBlank(message = "Reset token is required")
        String token) {
}
