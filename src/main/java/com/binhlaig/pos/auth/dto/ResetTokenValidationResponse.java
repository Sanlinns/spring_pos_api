package com.binhlaig.pos.auth.dto;

public record ResetTokenValidationResponse(
        boolean valid,
        String message) {
}
