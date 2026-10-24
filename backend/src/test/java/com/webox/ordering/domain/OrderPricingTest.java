package com.webox.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderPricingTest {
    @Test
    void sumsBaseAndOptionPricesThenMultipliesByQuantityWithTwoDecimalPrecision() {
        assertThat(OrderPricing.subtotal(new BigDecimal("22.00"), List.of(new BigDecimal("5.00"), new BigDecimal("3.50")), 2))
                .isEqualByComparingTo("61.00");
    }

    @Test
    void rejectsPricesWithMoreThanTwoFractionalDigitsAndInvalidQuantities() {
        assertThatThrownBy(() -> OrderPricing.subtotal(new BigDecimal("1.001"), List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderPricing.subtotal(new BigDecimal("1.00"), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capsTotalQuantityAcrossOrderLinesAtFive() {
        assertThatThrownBy(() -> OrderPricing.requireTotalQuantity(List.of(3, 3)))
                .isInstanceOf(IllegalArgumentException.class);
        OrderPricing.requireTotalQuantity(List.of(2, 3));
    }
}
