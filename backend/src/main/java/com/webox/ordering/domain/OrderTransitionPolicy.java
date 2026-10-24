package com.webox.ordering.domain;

public final class OrderTransitionPolicy {
    private OrderTransitionPolicy() { }

    public static boolean allows(OrderStatus from, OrderStatus to, ActorRole actor) {
        if (from == null || to == null || actor == null || from == to) return false;
        if (actor == ActorRole.EMPLOYEE) {
            return from == OrderStatus.PENDING && to == OrderStatus.CANCELLED;
        }
        return (from == OrderStatus.PENDING && (to == OrderStatus.CONFIRMED || to == OrderStatus.CANCELLED))
                || (from == OrderStatus.CONFIRMED && (to == OrderStatus.COMPLETED || to == OrderStatus.CANCELLED));
    }
}
