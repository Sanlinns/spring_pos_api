package com.binhlaig.pos.auth.email;

public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(Throwable cause) {
        super("Password reset email delivery failed", cause);
    }
}
