package com.binhlaig.pos.restaurant.order;

import com.binhlaig.pos.shopfeature.FeatureKey;
import com.binhlaig.pos.shopfeature.ShopFeatureService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/restaurant/orders")
@RequiredArgsConstructor
public class RestaurantOpenOrderController {

    private final RestaurantOpenOrderService openOrderService;
    private final ShopFeatureService shopFeatureService;

    @GetMapping
    public List<RestaurantOpenOrderResponse> getOrders(
            @RequestHeader("Authorization") String authorizationHeader
    ) {
        shopFeatureService.requireFeatureFromAuthorization(authorizationHeader, FeatureKey.RESTAURANT_ORDERS);
        return openOrderService.getOrders(authorizationHeader);
    }

    @GetMapping("/open/table/{tableId}")
    public ResponseEntity<RestaurantOpenOrderResponse> getOpenOrderByTable(
            @PathVariable Long tableId,
            @RequestHeader("Authorization") String authorizationHeader
    ) {
        shopFeatureService.requireFeatureFromAuthorization(authorizationHeader, FeatureKey.RESTAURANT_ORDERS);
        RestaurantOpenOrderResponse response = openOrderService.getOpenOrderByTable(tableId, authorizationHeader);
        return response == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(response);
    }

    @PostMapping("/open")
    public RestaurantOpenOrderResponse saveOpenOrder(
            @RequestBody RestaurantOpenOrderRequest request,
            @RequestHeader("Authorization") String authorizationHeader
    ) {
        shopFeatureService.requireFeatureFromAuthorization(authorizationHeader, FeatureKey.RESTAURANT_POS);
        return openOrderService.createOrUpdateOpenOrder(request, authorizationHeader);
    }

    @PostMapping("/items/cancel")
    public RestaurantItemCancelResponse cancelSentItem(
            @Valid @RequestBody RestaurantItemCancelRequest request,
            @RequestHeader("Authorization") String authorizationHeader
    ) {
        shopFeatureService.requireFeatureFromAuthorization(authorizationHeader, FeatureKey.RESTAURANT_POS);
        return openOrderService.cancelSentItem(request, authorizationHeader);
    }
}
