package com.webox.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void registrationHashesPasswordAndReturnsFifteenMinuteBearerToken() {
        String email = email();
        ResponseEntity<Map> response = restPost("/api/v1/auth/register", Map.of(
                "email", email, "password", "Password123", "displayName", "Test Employee"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("tokenType", "Bearer").containsEntry("expiresIn", 900);
        assertThat(response.getBody().get("accessToken").toString().split("\\.")).hasSize(3);
        Map user = (Map) response.getBody().get("user");
        assertThat(user).containsEntry("email", email).containsEntry("role", "EMPLOYEE");
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email = ?", String.class, email);
        assertThat(hash).startsWith("$2").isNotEqualTo("Password123");
    }

    @Test
    void duplicateRegistrationAndInvalidPasswordReturnContractProblems() {
        String email = email();
        Map request = Map.of("email", email, "password", "Password123", "displayName", "First");
        assertThat(restPost("/api/v1/auth/register", request, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> duplicate = restPost("/api/v1/auth/register", request, null);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getHeaders().getContentType().toString()).isEqualTo("application/problem+json");

        ResponseEntity<Map> invalid = restPost("/api/v1/auth/register", Map.of(
                "email", email(), "password", "short", "displayName", "Bad"), null);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).containsKey("fieldErrors");
    }

    @Test
    void invalidLoginDoesNotRevealWhetherEmailExists() throws Exception {
        MvcResult unknown = mockPost("/api/v1/auth/login", Map.of("email", email(), "password", "Wrong123"));
        String knownEmail = email();
        restPost("/api/v1/auth/register", Map.of("email", knownEmail, "password", "Password123", "displayName", "Known"), null);
        MvcResult wrongPassword = mockPost("/api/v1/auth/login", Map.of("email", knownEmail, "password", "Wrong123"));

        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(objectMapper.readTree(unknown.getResponse().getContentAsString()).get("detail").asText())
                .isEqualTo(objectMapper.readTree(wrongPassword.getResponse().getContentAsString()).get("detail").asText());
        assertThat(unknown.getResponse().getContentType()).isEqualTo("application/problem+json");
    }

    @Test
    void refreshRequiresAllowlistedOriginAndRotatesThenRevokesReusedCookieFamily() throws Exception {
        String email = email();
        ResponseEntity<Map> login = restPost("/api/v1/auth/register", Map.of(
                "email", email, "password", "Password123", "displayName", "Refresh User"), null);
        String originalCookie = cookieValue(login.getHeaders().getFirst(HttpHeaders.SET_COOKIE));

        MvcResult noOrigin = mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("webox_refresh", originalCookie))).andReturn();
        assertThat(noOrigin.getResponse().getStatus()).isEqualTo(403);

        MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("webox_refresh", originalCookie))
                .header("Origin", "http://localhost:5173")).andReturn();
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        String nextCookie = cookieValue(rotated.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(nextCookie).isNotEqualTo(originalCookie);
        assertThat(mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("webox_refresh", nextCookie))
                .header("Origin", "http://localhost:5173")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("webox_refresh", originalCookie))
                .header("Origin", "http://localhost:5173")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("webox_refresh", nextCookie))
                .header("Origin", "http://localhost:5173")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void bearerClaimsResolveCurrentActorAndExpiredOrMalformedTokensAreRejected() {
        ResponseEntity<Map> registration = restPost("/api/v1/auth/register", Map.of(
                "email", email(), "password", "Password123", "displayName", "Actor"), null);
        String token = registration.getBody().get("accessToken").toString();
        ResponseEntity<Map> me = get("/api/v1/auth/me", token);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsEntry("role", "EMPLOYEE");
        assertThat(get("/api/v1/auth/me", "not-a-jwt").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutRequiresAllowlistedOriginAndClearsCookie() throws Exception {
        ResponseEntity<Map> login = restPost("/api/v1/auth/register", Map.of(
                "email", email(), "password", "Password123", "displayName", "Logout User"), null);
        String cookie = cookieValue(login.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        MvcResult noOrigin = mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("webox_refresh", cookie))).andReturn();
        assertThat(noOrigin.getResponse().getStatus()).isEqualTo(403);
        MvcResult response = mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("webox_refresh", cookie))
                .header("Origin", "http://localhost:5173")).andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(204);
        assertThat(response.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }

    private ResponseEntity<Map> restPost(String path, Object body, String origin) {
        HttpHeaders headers = new HttpHeaders();
        if (origin != null) headers.add(HttpHeaders.ORIGIN, origin);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private MvcResult mockPost(String path, Object body) {
        try {
            return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(body))).andReturn();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private String email() { return "test-" + UUID.randomUUID() + "@example.test"; }
    private String cookieValue(String setCookie) { return setCookie.substring("webox_refresh=".length(), setCookie.indexOf(';')); }
}
