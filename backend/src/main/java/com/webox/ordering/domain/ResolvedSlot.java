package com.webox.ordering.domain;

import java.time.LocalDate;

public record ResolvedSlot(LocalDate deliveryDate, MealSlot mealSlot, boolean adjusted) { }
