package com.webox.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.HexFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "webox.rate-limit.login.limit=2", "webox.rate-limit.login.window-seconds=600",
        "webox.rate-limit.order.limit=2", "webox.rate-limit.order.window-seconds=600",
        "webox.rate-limit.general.limit=2", "webox.rate-limit.general.window-seconds=600"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RateLimitIntegrationTest {
    private static final String TEST_LOGIN_IP = "198.51.100.42";
    @Autowired StringRedisTemplate redis;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    @Test
    void loginLimitReturnsProblemDetailAndRetryAfter() throws Exception {
        redis.delete("webox:rate-limit:login:" + digest("ip:" + TEST_LOGIN_IP));
        Map<String, String> credentials = Map.of("email", "missing@example.test", "password", "WrongPassword123");

        MvcResult first = mockMvc.perform(post("/api/v1/auth/login").with(request -> remoteIp(request))
                .contentType("application/json").content(mapper.writeValueAsBytes(credentials))).andReturn();
        MvcResult second = mockMvc.perform(post("/api/v1/auth/login").with(request -> remoteIp(request))
                .contentType("application/json").content(mapper.writeValueAsBytes(credentials))).andReturn();
        MvcResult limited = mockMvc.perform(post("/api/v1/auth/login").with(request -> remoteIp(request))
                .contentType("application/json").content(mapper.writeValueAsBytes(credentials))).andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(401);
        assertThat(second.getResponse().getStatus()).isEqualTo(401);
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();
        assertThat(limited.getResponse().getContentType()).isEqualTo("application/problem+json");
        assertThat(mapper.readValue(limited.getResponse().getContentAsByteArray(), Map.class)).containsEntry("code", "RATE_LIMITED");
    }

    @Test
    void orderAndGeneralLimitsAreIndependentAndUserScoped() throws Exception {
        String token = registerToken();

        assertThat(getMe(token).getResponse().getStatus()).isEqualTo(200);
        assertThat(getMe(token).getResponse().getStatus()).isEqualTo(200);
        MvcResult limitedGeneral = getMe(token);
        assertThat(limitedGeneral.getResponse().getStatus()).isEqualTo(429);

        MvcResult firstOrder = quote(token);
        MvcResult secondOrder = quote(token);
        MvcResult limitedOrder = quote(token);
        assertThat(firstOrder.getResponse().getStatus()).isEqualTo(400);
        assertThat(secondOrder.getResponse().getStatus()).isEqualTo(400);
        assertThat(limitedOrder.getResponse().getStatus()).isEqualTo(429);
        assertThat(limitedOrder.getResponse().getHeader("Retry-After")).isNotBlank();
    }

    private String registerToken() throws Exception {
        Map<String, String> registration = Map.of("email", "ratelimit-" + java.util.UUID.randomUUID() + "@example.test",
                "password", "Password123", "displayName", "Rate Limit Tester");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType("application/json")
                .content(mapper.writeValueAsBytes(registration))).andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray()).get("accessToken").asText();
    }

    private MvcResult getMe(String token) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + token)).andReturn();
    }

    private MvcResult quote(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/app/orders/quote").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{}")).andReturn();
    }

    private MockHttpServletRequest remoteIp(MockHttpServletRequest request) {
        request.setRemoteAddr(TEST_LOGIN_IP);
        return request;
    }

    private String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
