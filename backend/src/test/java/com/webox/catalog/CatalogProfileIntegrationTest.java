package com.webox.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CatalogProfileIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mockMvc;
    @Autowired StringRedisTemplate redis;

    @Test
    void menuDetailIncludesActiveOptionGroupsAndUnknownDishesAreNotFound() {
        String token = register("dish-detail");
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        ResponseEntity<Map> detail = get("/api/v1/app/menu/dishes/4?date=" + today, token, Map.class);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(detail.getBody()).containsKeys("proteinSource", "optionGroups", "menuItemId");
        List<Map> groups = (List<Map>) detail.getBody().get("optionGroups");
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0)).containsKeys("selectionType", "required", "minSelections", "maxSelections", "values");

        assertThat(get("/api/v1/app/menu/dishes/999999?date=" + today, token, Map.class).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void seedMediaPathServesFilesFromTheConfiguredSeedDirectory() throws Exception {
        Path seed = Path.of("./target/test-uploads/seed");
        Files.createDirectories(seed);
        byte[] expected = "seed image bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(seed.resolve("image_01.jpg"), expected);

        ResponseEntity<byte[]> image = http.getForEntity("/media/seed/image_01.jpg", byte[].class);

        assertThat(image.getStatusCode().value()).isEqualTo(200);
        assertThat(image.getBody()).containsExactly(expected);
    }

    @Test
    void newEmployeeHasEmptyPreferencesBeforeFirstUpdate() {
        String token = register("empty-preference");
        ResponseEntity<Map> read = get("/api/v1/app/preferences", token, Map.class);
        assertThat(read.getStatusCode().value()).isEqualTo(200);
        assertThat(read.getBody()).containsEntry("allergens", List.of()).containsEntry("preferredCategories", List.of());
    }

    @Test
    void menuRequiresBearerAndReturnsPagedEnglishRowsSearchableByNameOrDescription() {
        String token = register("menu");
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        ResponseEntity<Map> anonymous = http.getForEntity("/api/v1/app/menu?date=" + today, Map.class);
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);

        ResponseEntity<Map> found = get("/api/v1/app/menu?date=" + today + "&q=chicken&page=0&size=1", token, Map.class);
        assertThat(found.getStatusCode().value()).isEqualTo(200);
        assertThat(found.getBody()).containsKeys("date", "page", "size", "totalItems", "totalPages", "items");
        List<Map> rows = (List<Map>) found.getBody().get("items");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name").toString().toLowerCase()).contains("chicken");
        assertThat(rows.get(0)).containsKeys("menuItemId", "dishId", "basePrice", "remainingStock", "stockVersion", "preferenceMatches");
        assertThat(rows.get(0).get("basePrice")).isEqualTo("22.00");
        long publicationVersion = jdbc.queryForObject("SELECT publication_version FROM daily_menu WHERE menu_date=?", Long.class, today);
        assertThat(redis.hasKey("webox:menu:v1:" + today + ":" + publicationVersion)).isTrue();

        ResponseEntity<Map> description = get("/api/v1/app/menu?date=" + today + "&q=lemongrass", token, Map.class);
        assertThat(description.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) description.getBody().get("items")).hasSize(1);
    }

    @Test
    void preferencesAreOwnedAndRecommendedMenuUsesStoredDietaryAndCategoryPreferences() {
        String token = register("preference");
        Map<String, Object> preference = Map.of("allergens", List.of("SHELLFISH"), "preferredCategories", List.of("JAPANESE"),
                "spiceLevel", "MILD", "tasteIntensity", "BALANCED", "budgetMin", "20.00", "budgetMax", "40.00");
        ResponseEntity<Map> saved = post("/api/v1/app/preferences", preference, token, HttpMethod.PUT);
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        assertThat(saved.getBody().get("allergens")).isEqualTo(List.of("SHELLFISH"));
        ResponseEntity<Map> read = get("/api/v1/app/preferences", token, Map.class);
        assertThat(read.getBody()).containsEntry("tasteIntensity", "BALANCED").containsEntry("budgetMax", "40.00");

        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        ResponseEntity<Map> recommended = get("/api/v1/app/menu?date=" + today + "&recommended=true", token, Map.class);
        List<Map> rows = (List<Map>) recommended.getBody().get("items");
        Map shellfishDish = rows.stream().filter(row -> ((Number) row.get("dishId")).longValue() == 5L).findFirst().orElseThrow();
        assertThat(((List<?>) shellfishDish.get("allergens")).stream().map(Object::toString).toList()).contains("SHELLFISH");
        assertThat(rows.get(0).get("preferenceMatches")).isNotNull();
        Map<String, Object> invalid = new java.util.HashMap<>();
        invalid.put("allergens", List.of());
        invalid.put("preferredCategories", List.of());
        invalid.put("spiceLevel", "INVALID");
        invalid.put("tasteIntensity", "BALANCED");
        invalid.put("budgetMin", null);
        invalid.put("budgetMax", null);
        assertThat(post("/api/v1/app/preferences", invalid, token, HttpMethod.PUT)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void addressCrudEnforcesOwnerAndOneDefault() {
        String owner = register("address-owner");
        String other = register("address-other");
        ResponseEntity<Map> first = post("/api/v1/app/addresses", Map.of("label", "Home", "addressText", "1 Example Road", "isDefault", true), owner, HttpMethod.POST);
        ResponseEntity<Map> second = post("/api/v1/app/addresses", Map.of("label", "Office", "addressText", "2 Example Road", "isDefault", true), owner, HttpMethod.POST);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        long firstId = ((Number) first.getBody().get("id")).longValue();
        long secondId = ((Number) second.getBody().get("id")).longValue();
        List<Map> addresses = http.exchange("/api/v1/app/addresses", HttpMethod.GET, bearer(owner), List.class).getBody();
        assertThat(addresses).filteredOn(a -> Boolean.TRUE.equals(a.get("isDefault"))).hasSize(1);
        assertThat(patchAddress(firstId, other).getStatusCode().value()).isEqualTo(403);
        assertThat(patchAddress(firstId, owner).getStatusCode().value()).isEqualTo(200);
        assertThat(http.exchange("/api/v1/app/addresses/" + secondId, HttpMethod.DELETE, bearer(owner), Void.class).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void adminDishWritesUseOptimisticVersionAndUnpublishedDishesStayOutOfEmployeeMenu() {
        String employee = register("catalog-employee");
        String admin = adminToken();
        assertThat(get("/api/v1/admin/dishes", employee, Map.class).getStatusCode().value()).isEqualTo(403);

        Map<String, Object> dish = new java.util.HashMap<>();
        dish.put("name", "Seasonal Garden Bowl");
        dish.put("description", "Fresh greens with roasted pear");
        dish.put("basePrice", "27.50");
        dish.put("category", "LIGHT_MEAL");
        dish.put("proteinSource", "Tofu");
        dish.put("spiceLevel", "MILD");
        dish.put("allergens", List.of("SOY"));
        dish.put("imageUrl", "/media/seed/seasonal-garden.jpg");
        dish.put("published", false);
        dish.put("version", 0);
        dish.put("optionGroups", List.of());
        ResponseEntity<Map> created = post("/api/v1/admin/dishes", dish, admin, HttpMethod.POST);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long dishId = ((Number) created.getBody().get("id")).longValue();
        assertThat(get("/api/v1/admin/dishes/" + dishId, admin, Map.class).getBody()).containsEntry("name", "Seasonal Garden Bowl");

        Map<String, Object> changed = new java.util.HashMap<>(dish);
        changed.put("description", "Fresh greens, roasted pear and sesame");
        changed.put("version", 0);
        assertThat(put("/api/v1/admin/dishes/" + dishId, changed, admin).getStatusCode().value()).isEqualTo(200);
        assertThat(put("/api/v1/admin/dishes/" + dishId, changed, admin).getStatusCode().value()).isEqualTo(409);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        ResponseEntity<Map> menu = get("/api/v1/app/menu?date=" + today + "&q=Seasonal", employee, Map.class);
        assertThat((List<?>) menu.getBody().get("items")).isEmpty();
    }

    @Test
    void adminDailyMenuCanPublishAndImageUploadRejectsNonImages() throws Exception {
        String admin = adminToken();
        LocalDate future = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                .plusDays(10L + Math.floorMod(UUID.randomUUID().hashCode(), 20_000));
        Map<String, Object> write = Map.of("expectedPublicationVersion", 0,
                "items", List.of(Map.of("dishId", 1, "initialStock", 7, "visible", true)));
        ResponseEntity<Map> saved = put("/api/v1/admin/menus/" + future, write, admin);
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        assertThat(saved.getBody()).containsEntry("status", "DRAFT").containsEntry("publicationVersion", 1);
        assertThat(post("/api/v1/admin/menus/" + future + "/publish", Map.of(), admin, HttpMethod.POST).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/api/v1/admin/menus/" + future, admin, Map.class).getBody()).containsEntry("status", "PUBLISHED");

        MockMultipartHttpServletRequestBuilder upload = multipart("/api/v1/admin/media/images")
                .file(new MockMultipartFile("file", "payload.txt", "text/plain", "no image".getBytes()));
        MvcResult invalid = mockMvc.perform(upload.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void editingPublishedMenuStockSynchronizesAnAlreadyInitializedRedisInventoryHash() {
        String admin = adminToken();
        LocalDate date = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                .plusDays(40L + Math.floorMod(UUID.randomUUID().hashCode(), 20_000));
        ResponseEntity<Map> saved = put("/api/v1/admin/menus/" + date, Map.of("expectedPublicationVersion", 0,
                "items", List.of(Map.of("dishId", 1, "initialStock", 5, "visible", true))), admin);
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        assertThat(post("/api/v1/admin/menus/" + date + "/publish", Map.of(), admin, HttpMethod.POST).getStatusCode().value()).isEqualTo(200);
        long menuItemId = jdbc.queryForObject("SELECT id FROM daily_menu_item WHERE daily_menu_id=(SELECT id FROM daily_menu WHERE menu_date=?) AND dish_id=1", Long.class, date);
        String stockKey = "webox:inventory:{" + date + "}:stock";
        redis.opsForHash().put(stockKey, Long.toString(menuItemId), "2");

        ResponseEntity<Map> edited = put("/api/v1/admin/menus/" + date, Map.of("expectedPublicationVersion", 2,
                "items", List.of(Map.of("dishId", 1, "initialStock", 8, "visible", true))), admin);

        assertThat(edited.getStatusCode().value()).isEqualTo(200);
        assertThat(redis.opsForHash().get(stockKey, Long.toString(menuItemId))).isEqualTo("8");
    }

    @Test
    void uploadedImageIsStoredAndServedThroughItsReturnedMediaUrl() throws Exception {
        String admin = adminToken();
        byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/pyoAAAAASUVORK5CYII=");
        MockMultipartHttpServletRequestBuilder upload = multipart("/api/v1/admin/media/images")
                .file(new MockMultipartFile("file", "pixel.png", "image/png", png));
        MvcResult created = mockMvc.perform(upload.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String url = mapper.readTree(created.getResponse().getContentAsString()).get("url").asText();
        ResponseEntity<byte[]> served = http.getForEntity(url, byte[].class);
        assertThat(served.getStatusCode().value()).isEqualTo(200);
        assertThat(served.getBody()).containsExactly(png);
    }

    @Test
    void unknownApiResourceUsesTheContractNotFoundProblem() {
        String token = register("unknown-route");
        ResponseEntity<Map> missing = get("/api/v1/app/unknown-resource", token, Map.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(missing.getHeaders().getContentType().toString()).isEqualTo("application/problem+json");
        assertThat(missing.getBody()).containsEntry("code", "RESOURCE_NOT_FOUND");
    }

    private String register(String prefix) {
        ResponseEntity<Map> response = http.postForEntity("/api/v1/auth/register", Map.of("email", prefix + "-" + UUID.randomUUID() + "@example.test",
                "password", "Password123", "displayName", prefix), Map.class);
        return response.getBody().get("accessToken").toString();
    }

    private String adminToken() {
        String email = "admin-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO app_user(email,password_hash,display_name,role,enabled) VALUES(?,?,?,'ADMIN',TRUE)",
                email, passwords.encode("AdminPass123"), "Test Admin");
        ResponseEntity<Map> response = http.postForEntity("/api/v1/auth/login", Map.of("email", email, "password", "AdminPass123"), Map.class);
        return response.getBody().get("accessToken").toString();
    }

    private <T> ResponseEntity<T> get(String path, String token, Class<T> type) {
        return http.exchange(path, HttpMethod.GET, bearer(token), type);
    }

    private ResponseEntity<Map> post(String path, Object body, String token, HttpMethod method) {
        return http.exchange(path, method, new HttpEntity<>(body, bearer(token).getHeaders()), Map.class);
    }

    private ResponseEntity<Map> put(String path, Object body, String token) { return post(path, body, token, HttpMethod.PUT); }

    private HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private ResponseEntity<Map> patchAddress(long id, String token) {
        try {
            MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/app/addresses/" + id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(Map.of("label", "Updated", "addressText", "3 Example Road", "isDefault", false)))).andReturn();
            return ResponseEntity.status(result.getResponse().getStatus()).body(mapper.readValue(result.getResponse().getContentAsByteArray(), Map.class));
        } catch (Exception ex) { throw new AssertionError(ex); }
    }
}
