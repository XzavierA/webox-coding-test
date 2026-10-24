package com.webox.ordering.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class OrderPricing {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private OrderPricing() { }

    public static BigDecimal subtotal(BigDecimal basePrice, List<BigDecimal> optionDeltas, int quantity) {
        if (quantity < 1 || quantity > 5) throw new IllegalArgumentException("Quantity must be between 1 and 5");
        BigDecimal unit = normalize(basePrice);
        for (BigDecimal delta : optionDeltas) {
            BigDecimal normalized = normalize(delta);
            if (normalized.signum() < 0) throw new IllegalArgumentException("Option prices cannot be negative");
            unit = unit.add(normalized);
        }
        return unit.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UNNECESSARY);
    }

    public static void requireTotalQuantity(List<Integer> quantities) {
        if (quantities == null || quantities.isEmpty()) throw new IllegalArgumentException("Order requires at least one line");
        int total = 0;
        for (Integer quantity : quantities) {
            if (quantity == null || quantity < 1 || quantity > 5) throw new IllegalArgumentException("Quantity must be between 1 and 5");
            total = Math.addExact(total, quantity);
        }
        if (total > 5) throw new IllegalArgumentException("An order may contain at most five servings");
    }

    private static BigDecimal normalize(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("Price must be non-negative");
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Price must have at most two decimal places", ex);
        }
    }
}
