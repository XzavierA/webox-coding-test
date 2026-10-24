package com.webox.menu.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.webox.shared.ApiException;
import com.webox.platform.security.CurrentActor;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/app/menu")
public class MenuController {
    private final JdbcTemplate jdbc;
    private final CurrentActor currentActor;
    private final RedisMessageListenerContainer listeners;
    private final TaskScheduler scheduler;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;

    public MenuController(JdbcTemplate jdbc, CurrentActor currentActor, RedisMessageListenerContainer listeners,
                          TaskScheduler scheduler, ObjectMapper mapper, StringRedisTemplate redis) {
        this.jdbc = jdbc; this.currentActor = currentActor; this.listeners = listeners; this.scheduler = scheduler; this.mapper = mapper; this.redis = redis;
    }

    @GetMapping
    public Map<String, Object> menu(@RequestParam(required = false) LocalDate date,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false) List<String> categories,
                                    @RequestParam(defaultValue = "false") boolean recommended,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        LocalDate selectedDate = date == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : date;
        if (page < 0 || size < 1 || size > 100) throw invalid("page", "page must be non-negative and size must be between 1 and 100");
        if (q != null && q.length() > 50) throw invalid("q", "must be at most 50 characters");
        if (categories != null && categories.stream().anyMatch(c -> !List.of("CHINESE", "WESTERN", "JAPANESE", "LIGHT_MEAL", "KOREAN", "SOUTHEAST_ASIAN").contains(c)))
            throw invalid("categories", "contains an unsupported category");
        long userId = currentActor.require().userId();
        List<String> preferred = jdbc.queryForList("SELECT category FROM user_preferred_category WHERE user_id=?", String.class, userId);
        Map<String, Object> preference = preferences(userId);
        List<Map<String, Object>> rows = menuRows(selectedDate);
        if (q != null && !q.isBlank()) {
            String search = q.toLowerCase();
            rows.removeIf(row -> !row.get("name").toString().toLowerCase().contains(search)
                    && !row.get("description").toString().toLowerCase().contains(search));
        }
        if (categories != null && !categories.isEmpty()) rows.removeIf(row -> !categories.contains(row.get("category")));
        for (Map<String, Object> row : rows) {
            List<String> rowAllergens = jdbc.queryForList("SELECT allergen FROM dish_allergen WHERE dish_id=? ORDER BY allergen", String.class, row.get("dishId"));
            row.put("allergens", rowAllergens);
            List<String> matches = matches(row, preferred, preference);
            row.put("preferenceMatches", matches);
        }
        if (recommended) rows.sort(Comparator.<Map<String, Object>>comparingInt(row -> ((List<?>) row.get("preferenceMatches")).size()).reversed()
                .thenComparing(row -> ((Number) row.get("dishId")).longValue()));
        else rows.sort(Comparator.comparing(row -> ((Number) row.get("dishId")).longValue()));
        long total = rows.size();
        int start = (int) Math.min(total, (long) page * size);
        int end = (int) Math.min(total, (long) start + size);
        List<Map<String, Object>> items = new ArrayList<>(rows.subList(start, end));
        Map<String, Object> result = pageMeta(page, size, total);
        result.put("date", selectedDate.toString()); result.put("items", items);
        return result;
    }

    @GetMapping("/dishes/{dishId}")
    public Map<String, Object> detail(@PathVariable long dishId, @RequestParam(required = false) LocalDate date) {
        LocalDate selectedDate = date == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : date;
        long userId = currentActor.require().userId();
        List<Map<String, Object>> rows = menuRows(selectedDate).stream().filter(row -> ((Number) row.get("dishId")).longValue() == dishId).toList();
        if (rows.isEmpty()) throw new ApiException(404, "RESOURCE_NOT_FOUND", "The dish was not found on the published menu.");
        Map<String, Object> card = rows.get(0);
        List<String> allergens = jdbc.queryForList("SELECT allergen FROM dish_allergen WHERE dish_id=? ORDER BY allergen", String.class, dishId);
        card.put("allergens", allergens);
        card.put("preferenceMatches", matches(card,
                jdbc.queryForList("SELECT category FROM user_preferred_category WHERE user_id=?", String.class, userId), preferences(userId)));
        card.put("proteinSource", jdbc.queryForObject("SELECT protein_source FROM dish WHERE id=?", String.class, dishId));
        List<Map<String, Object>> groups = jdbc.query("SELECT id,name,selection_type,required_selection,min_selections,max_selections FROM dish_option_group WHERE dish_id=? AND active=TRUE ORDER BY display_order,id", (rs, n) -> {
            Map<String, Object> group = new LinkedHashMap<>();
            long id = rs.getLong("id"); group.put("id", id); group.put("name", rs.getString("name"));
            group.put("selectionType", rs.getString("selection_type")); group.put("required", rs.getBoolean("required_selection"));
            group.put("minSelections", rs.getInt("min_selections")); group.put("maxSelections", rs.getInt("max_selections"));
            group.put("values", jdbc.query("SELECT id,name,price_delta FROM dish_option_value WHERE option_group_id=? AND active=TRUE ORDER BY display_order,id", (valueRs, i) -> {
                Map<String, Object> value = new LinkedHashMap<>(); value.put("id", valueRs.getLong("id")); value.put("name", valueRs.getString("name"));
                value.put("priceDelta", money(valueRs.getBigDecimal("price_delta"))); return value;
            }, id));
            return group;
        }, dishId);
        card.put("optionGroups", groups);
        return card;
    }

    @GetMapping(path = "/stock-stream", produces = "text/event-stream")
    public SseEmitter stockStream(@RequestParam(required = false) LocalDate date) {
        LocalDate selectedDate = date == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : date;
        SseEmitter emitter = new SseEmitter(0L);
        ChannelTopic topic = new ChannelTopic("webox:stock:" + selectedDate);
        AtomicBoolean closed = new AtomicBoolean();
        MessageListener listener = (Message message, byte[] pattern) -> {
            if (closed.get()) return;
            try { emitter.send(SseEmitter.event().name("stock.changed").data(mapper.readTree(message.getBody()))); }
            catch (IOException ex) { emitter.completeWithError(ex); }
        };
        listeners.addMessageListener(listener, topic);
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
            if (closed.get()) return;
            try { emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("at", OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString()))); }
            catch (IOException ex) { emitter.completeWithError(ex); }
        }, Duration.ofSeconds(20));
        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) { heartbeat.cancel(false); listeners.removeMessageListener(listener, topic); }
        };
        emitter.onCompletion(cleanup); emitter.onTimeout(cleanup); emitter.onError(error -> cleanup.run());
        try { emitter.send(SseEmitter.event().name("resync.required").data(Map.of("date", selectedDate.toString()))); }
        catch (IOException ex) { emitter.completeWithError(ex); }
        return emitter;
    }

    private List<Map<String, Object>> menuRows(LocalDate date) {
        List<Long> versions = jdbc.queryForList("SELECT publication_version FROM daily_menu WHERE menu_date=? AND status='PUBLISHED'", Long.class, date);
        if (versions.isEmpty()) return new ArrayList<>();
        String cacheKey = "webox:menu:v1:" + date + ":" + versions.get(0);
        List<Map<String, Object>> rows = null;
        boolean cacheLoaded = false;
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) { rows = mapper.readValue(cached, new TypeReference<>() { }); cacheLoaded = true; }
        } catch (Exception ignored) { }
        if (rows == null) rows = jdbc.query("SELECT mi.id AS menu_item_id,d.id AS dish_id,d.name,d.description,d.image_url,d.base_price,d.category,d.spice_level,mi.remaining_stock,mi.stock_version " +
                "FROM daily_menu m JOIN daily_menu_item mi ON mi.daily_menu_id=m.id JOIN dish d ON d.id=mi.dish_id " +
                "WHERE m.menu_date=? AND m.status='PUBLISHED' AND mi.visible=TRUE AND d.published=TRUE", (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            int stock = rs.getInt("remaining_stock");
            row.put("menuItemId", rs.getLong("menu_item_id")); row.put("dishId", rs.getLong("dish_id"));
            row.put("name", rs.getString("name")); row.put("description", rs.getString("description")); row.put("imageUrl", rs.getString("image_url"));
            row.put("basePrice", money(rs.getBigDecimal("base_price"))); row.put("category", rs.getString("category"));
            row.put("spiceLevel", rs.getString("spice_level")); row.put("remainingStock", stock); row.put("stockVersion", rs.getLong("stock_version"));
            row.put("lowStock", stock <= 3); row.put("soldOut", stock == 0); return row;
        }, date);
        if (!cacheLoaded) try { redis.opsForValue().set(cacheKey, mapper.writeValueAsString(rows), Duration.ofMinutes(5)); } catch (Exception ignored) { }
        for (Map<String, Object> row : rows) {
            Map<String, Object> stock = jdbc.queryForMap("SELECT remaining_stock,stock_version FROM daily_menu_item WHERE id=?", row.get("menuItemId"));
            int remaining = ((Number) stock.get("remaining_stock")).intValue();
            row.put("remainingStock", remaining); row.put("stockVersion", ((Number) stock.get("stock_version")).longValue());
            row.put("lowStock", remaining <= 3); row.put("soldOut", remaining == 0);
        }
        return rows;
    }

    private Map<String, Object> preferences(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT spice_level,taste_intensity,budget_min,budget_max FROM user_preference WHERE user_id=?", userId);
        if (rows.isEmpty()) return Map.of();
        Map<String, Object> row = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("spiceLevel", row.get("spice_level")); result.put("tasteIntensity", row.get("taste_intensity"));
        result.put("budgetMin", row.get("budget_min")); result.put("budgetMax", row.get("budget_max")); return result;
    }

    private List<String> matches(Map<String, Object> row, List<String> preferred, Map<String, Object> preference) {
        List<String> result = new ArrayList<>();
        if (preferred.contains(row.get("category"))) result.add("PREFERRED_CATEGORY");
        if (preference.get("spiceLevel") != null && preference.get("spiceLevel").equals(row.get("spiceLevel"))) result.add("SPICE_LEVEL");
        BigDecimal price = new BigDecimal(row.get("basePrice").toString());
        Object min = preference.get("budgetMin"), max = preference.get("budgetMax");
        if ((min == null || price.compareTo(new BigDecimal(min.toString())) >= 0) && (max == null || price.compareTo(new BigDecimal(max.toString())) <= 0)
                && (min != null || max != null)) result.add("BUDGET");
        return result;
    }

    private Map<String, Object> pageMeta(int page, int size, long total) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("page", page); result.put("size", size);
        result.put("totalItems", total); result.put("totalPages", total == 0 ? 0 : (int) ((total + size - 1) / size)); return result;
    }
    private String money(BigDecimal value) { return value.setScale(2).toPlainString(); }
    private ApiException invalid(String field, String detail) { return new ApiException(400, "VALIDATION_FAILED", detail, List.of(new ApiException.FieldError(field, detail))); }
}
