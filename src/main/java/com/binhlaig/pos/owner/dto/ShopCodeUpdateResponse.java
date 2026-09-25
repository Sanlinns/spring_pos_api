package com.binhlaig.pos.owner.dto;

public record ShopCodeUpdateResponse(
        String message,
        boolean reauthenticationRequired) {
}
