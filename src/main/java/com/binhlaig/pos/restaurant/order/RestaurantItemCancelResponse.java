package com.binhlaig.pos.restaurant.order;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class RestaurantItemCancelResponse {
    @Builder.Default
    private List<Long> activeTicketIds = List.of();
}
