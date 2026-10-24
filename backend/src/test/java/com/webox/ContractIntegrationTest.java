package com.webox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ContractIntegrationTest {
    @Autowired TestRestTemplate http;

    @Test
    void publishesEveryBindingOpenApiPath() {
        Map api = http.getForObject("/v3/api-docs", Map.class);
        Map paths = (Map) api.get("paths");
        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(Set.of(
                "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/me",
                "/api/v1/app/menu", "/api/v1/app/menu/dishes/{dishId}", "/api/v1/app/menu/stock-stream",
                "/api/v1/app/preferences", "/api/v1/app/addresses", "/api/v1/app/addresses/{addressId}",
                "/api/v1/app/orders/quote", "/api/v1/app/orders", "/api/v1/app/order-submissions/{submissionId}",
                "/api/v1/app/orders/{orderId}", "/api/v1/app/orders/{orderId}/cancel",
                "/api/v1/admin/dishes", "/api/v1/admin/dishes/{dishId}", "/api/v1/admin/dishes/{dishId}/publication",
                "/api/v1/admin/media/images", "/api/v1/admin/menus/{date}", "/api/v1/admin/menus/{date}/publish",
                "/api/v1/admin/orders", "/api/v1/admin/orders/{orderId}", "/api/v1/admin/orders/{orderId}/transitions",
                "/api/v1/admin/dashboard"));
    }

    @Test
    void bindingContractKeepsCriticalOrderAndMoneyConstraints() throws Exception {
        String yaml = Files.readString(Path.of("../docs/openapi.yaml"));
        assertThat(yaml).contains("'200':\n          description: Replay of an already completed request",
                "'201':\n          description: MySQL-confirmed order",
                "'202':\n          description: Reservation accepted but MySQL confirmation is pending",
                "Retry-After: {schema: {type: integer, minimum: 1}}",
                "IdempotencyKey:\n      name: Idempotency-Key\n      in: header\n      required: true\n      schema: {type: string, format: uuid}");

        String orderLine = section(yaml, "    OrderLineRequest:\n", "    InlineAddress:\n");
        assertThat(orderLine).contains("uniqueItems: true", "minimum: 1, maximum: 5");
        String orderRequest = section(yaml, "    OrderRequest:\n", "    QuoteLine:\n");
        assertThat(orderRequest).contains("minItems: 1", "maxItems: 5", "Exactly one of addressId/newAddress");
        String money = section(yaml, "    Money:\n", "    User:\n");
        assertThat(money).contains("pattern: '^\\d{1,8}\\.\\d{2}$'");
    }

    private String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Contract section was not found: " + start);
        return source.substring(from, to);
    }
}
