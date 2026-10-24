package com.webox.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class MealSlotResolverTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final LocalDate today = LocalDate.of(2026, 10, 24);

    @Test
    void lunchRemainsAvailableBeforeTen() {
        assertThat(resolve("2026-10-24T09:59:59+08:00", "LUNCH", today)).isEqualTo(new ResolvedSlot(today, MealSlot.LUNCH, false));
    }

    @Test
    void lunchMovesToDinnerAtTenAndUntilThree() {
        assertThat(resolve("2026-10-24T10:00:00+08:00", "LUNCH", today)).isEqualTo(new ResolvedSlot(today, MealSlot.DINNER, true));
        assertThat(resolve("2026-10-24T14:59:59+08:00", "LUNCH", today)).isEqualTo(new ResolvedSlot(today, MealSlot.DINNER, true));
    }

    @Test
    void anyTodaysSlotMovesToTomorrowLunchAtThree() {
        assertThat(resolve("2026-10-24T15:00:00+08:00", "DINNER", today)).isEqualTo(new ResolvedSlot(today.plusDays(1), MealSlot.LUNCH, true));
        assertThat(resolve("2026-10-24T15:00:00+08:00", "LUNCH", today)).isEqualTo(new ResolvedSlot(today.plusDays(1), MealSlot.LUNCH, true));
    }

    @Test
    void pastDeliveryDateIsRejected() {
        assertThatThrownBy(() -> resolver("2026-10-24T09:00:00+08:00").resolve(today.minusDays(1), MealSlot.LUNCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ResolvedSlot resolve(String now, String slot, LocalDate date) {
        return resolver(now).resolve(date, MealSlot.valueOf(slot));
    }

    private MealSlotResolver resolver(String now) {
        Clock clock = Clock.fixed(java.time.OffsetDateTime.parse(now).toInstant(), SHANGHAI);
        return new MealSlotResolver(clock);
    }
}
