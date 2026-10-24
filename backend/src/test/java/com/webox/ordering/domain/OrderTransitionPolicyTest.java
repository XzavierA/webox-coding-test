package com.webox.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class OrderTransitionPolicyTest {
    @Test
    void allowsOnlyContractTransitions() {
        assertThat(OrderTransitionPolicy.allows(OrderStatus.PENDING, OrderStatus.CONFIRMED, ActorRole.ADMIN)).isTrue();
        assertThat(OrderTransitionPolicy.allows(OrderStatus.PENDING, OrderStatus.CANCELLED, ActorRole.EMPLOYEE)).isTrue();
        assertThat(OrderTransitionPolicy.allows(OrderStatus.CONFIRMED, OrderStatus.COMPLETED, ActorRole.ADMIN)).isTrue();
        assertThat(OrderTransitionPolicy.allows(OrderStatus.PENDING, OrderStatus.COMPLETED, ActorRole.ADMIN)).isFalse();
        assertThat(OrderTransitionPolicy.allows(OrderStatus.COMPLETED, OrderStatus.CANCELLED, ActorRole.ADMIN)).isFalse();
        assertThat(OrderTransitionPolicy.allows(OrderStatus.CONFIRMED, OrderStatus.CANCELLED, ActorRole.EMPLOYEE)).isFalse();
    }
}
