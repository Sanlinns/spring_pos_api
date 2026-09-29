package com.binhlaig.pos.modules.product;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;

@Service @RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class StockService {
    private final StockMovementRepository movements;

    public void initialize(Product product) {
        BigDecimal opening = quantity(product.getProductQuantityAmount());
        if (opening.signum() < 0) throw bad("Opening stock cannot be negative");
        product.setOpeningBalance(opening);
        product.setTotalStock(opening);
        product.setSoldQuantity(BigDecimal.ZERO);
        product.setStockCorrection(BigDecimal.ZERO);
        product.setHistoricalSoldQuantity(BigDecimal.ZERO);
        product.setStockTrackingStartedAt(Instant.now());
        product.setStockTrackingBasis("FROM_CREATION");
    }

    public void recordOpening(Product product) {
        record(product, "OPENING_BALANCE", BigDecimal.ZERO, product.getOpeningBalance(), "OPENING", null);
    }

    // Call only with a product locked by findByIdAndShopIdForUpdate in the caller transaction.
    public void add(Product product, BigDecimal amount, String reference, String reason) {
        amount = quantity(amount);
        if (amount.signum() <= 0) throw bad("ADD_STOCK quantity must be positive");
        BigDecimal before = product.getProductQuantityAmount();
        checkRemaining(before.add(amount));
        product.setTotalStock(product.getTotalStock().add(amount));
        product.setProductQuantityAmount(before.add(amount));
        record(product, "ADD_STOCK", before, amount, reference, reason);
    }

    public void correct(Product product, BigDecimal delta, String reference, String reason) {
        delta = quantity(delta);
        if (delta.signum() == 0 || reason == null || reason.isBlank())
            throw bad("STOCK_CORRECTION requires a nonzero signed quantity and reason");
        BigDecimal before = product.getProductQuantityAmount();
        checkRemaining(before.add(delta));
        product.setStockCorrection(product.getStockCorrection().add(delta));
        product.setProductQuantityAmount(before.add(delta));
        record(product, "STOCK_CORRECTION", before, delta, reference, reason);
    }

    public void sell(Product product, BigDecimal amount, String reference) {
        amount = quantity(amount);
        if (amount.signum() <= 0) throw bad("Sale quantity must be positive");
        BigDecimal before = product.getProductQuantityAmount();
        checkRemaining(before.subtract(amount));
        product.setSoldQuantity(product.getSoldQuantity().add(amount));
        product.setProductQuantityAmount(before.subtract(amount));
        record(product, "SALE", before, amount.negate(), reference, null);
    }

    private void record(Product product, String operation, BigDecimal before,
                        BigDecimal delta, String reference, String reason) {
        StockMovement movement = new StockMovement();
        movement.setProductId(product.getId());
        movement.setOperation(operation);
        movement.setQuantityDelta(delta);
        movement.setBalanceBefore(before);
        movement.setBalanceAfter(before.add(delta));
        movement.setReference(reference);
        movement.setReason(reason);
        movements.save(movement);
    }

    private BigDecimal quantity(BigDecimal value) {
        if (value == null || value.stripTrailingZeros().scale() > 2
                || value.abs().compareTo(new BigDecimal("9999999999.99")) > 0)
            throw bad("Quantity requires at most 2 decimal places and magnitude <= 9999999999.99");
        return value;
    }

    private void checkRemaining(BigDecimal remaining) {
        quantity(remaining);
        if (remaining.signum() < 0) throw bad("Insufficient stock");
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
