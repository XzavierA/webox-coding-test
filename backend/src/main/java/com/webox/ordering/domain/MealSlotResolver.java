package com.webox.ordering.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

public final class MealSlotResolver {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime LUNCH_CUTOFF = LocalTime.of(10, 0);
    private static final LocalTime DINNER_CUTOFF = LocalTime.of(15, 0);
    private final Clock clock;

    public MealSlotResolver(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public ResolvedSlot resolve(LocalDate requestedDate, MealSlot requestedSlot) {
        Objects.requireNonNull(requestedDate);
        Objects.requireNonNull(requestedSlot);
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        if (requestedDate.isBefore(today)) {
            throw new IllegalArgumentException("Delivery date cannot be in the past");
        }
        if (requestedDate.isAfter(today)) {
            return new ResolvedSlot(requestedDate, requestedSlot, false);
        }
        LocalTime now = LocalTime.now(clock.withZone(BUSINESS_ZONE));
        if (!now.isBefore(DINNER_CUTOFF)) {
            return new ResolvedSlot(today.plusDays(1), MealSlot.LUNCH, true);
        }
        if (requestedSlot == MealSlot.LUNCH && !now.isBefore(LUNCH_CUTOFF)) {
            return new ResolvedSlot(today, MealSlot.DINNER, true);
        }
        return new ResolvedSlot(today, requestedSlot, false);
    }
}
