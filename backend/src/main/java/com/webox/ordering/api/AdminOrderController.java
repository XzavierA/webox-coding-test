package com.webox.ordering.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webox.ordering.domain.ActorRole;
import com.webox.ordering.domain.OrderStatus;
import com.webox.ordering.domain.OrderTransitionPolicy;
import com.webox.platform.security.AuthenticatedActor;
import com.webox.platform.security.CurrentActor;
import com.webox.shared.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminOrderController {
    private final JdbcTemplate jdbc;
    private final CurrentActor actor;
    private final OrderController orders;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public AdminOrderController(JdbcTemplate jdbc, CurrentActor actor, OrderController orders,
                                StringRedisTemplate redis, ObjectMapper mapper) {
        this.jdbc = jdbc; this.actor = actor; this.orders = orders; this.redis = redis; this.mapper = mapper;
    }

    @GetMapping("/orders")
    public Map<String, Object> listOrders(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) LocalDate date,
                                          @RequestParam(required = false) String mealSlot,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        StringBuilder where = new StringBuilder(" FROM customer_order o JOIN app_user u ON u.id=o.user_id WHERE 1=1"); List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) { where.append(" AND (LOWER(o.order_number) LIKE ? OR LOWER(u.display_name) LIKE ?)"); String pattern = "%" + q.toLowerCase() + "%"; args.add(pattern); args.add(pattern); }
        if (date != null) { where.append(" AND o.delivery_date=?"); args.add(date); }
        if (mealSlot != null) { where.append(" AND o.meal_slot=?"); args.add(mealSlot); }
        if (status != null) { where.append(" AND o.status=?"); args.add(status); }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        args.add(size); args.add((long) page * size);
        List<Long> ids = jdbc.queryForList("SELECT o.id" + where + " ORDER BY o.created_at DESC,o.id DESC LIMIT ? OFFSET ?", Long.class, args.toArray());
        List<Map<String, Object>> items = ids.stream().map(id -> orders.order(id, true)).toList();
        Map<String, Object> result = pageMeta(page, size, total == null ? 0 : total); result.put("items", items); return result;
    }

    @GetMapping("/orders/{orderId}")
    public Map<String, Object> getOrder(@PathVariable long orderId) { return orders.order(orderId, true); }

    @PostMapping("/orders/{orderId}/transitions")
    @Transactional
    public Map<String, Object> transition(@PathVariable long orderId, @RequestBody Map<String, Object> body) {
        AuthenticatedActor authenticated = actor.require();
        OrderStatus target;
        try { target = OrderStatus.valueOf(String.valueOf(body.get("targetStatus"))); }
        catch (IllegalArgumentException ex) { throw invalid("targetStatus", "is invalid"); }
        Object versionValue = body.get("expectedVersion");
        if (!(versionValue instanceof Number expectedValue) || expectedValue.longValue() < 0) throw invalid("expectedVersion", "must be a non-negative integer");
        String reason = body.get("reason") == null ? null : body.get("reason").toString();
        if (reason != null && reason.length() > 500) throw invalid("reason", "must be at most 500 characters");
        Map<String, Object> row = orders.orderRow(orderId); String from = row.get("status").toString();
        long version = ((Number) row.get("version")).longValue();
        if (version != expectedValue.longValue()) throw new ApiException(409, "OPTIMISTIC_LOCK_CONFLICT", "The order has changed. Reload it before applying this transition.");
        OrderStatus source = OrderStatus.valueOf(from);
        ActorRole actorRole = ActorRole.valueOf(authenticated.role());
        if (!OrderTransitionPolicy.allows(source, target, actorRole)) throw new ApiException(409, "INVALID_ORDER_TRANSITION", "The requested order status transition is not allowed.");
        int changed = jdbc.update("UPDATE customer_order SET status=?,version=version+1,cancelled_at=CASE WHEN ?='CANCELLED' THEN CURRENT_TIMESTAMP(3) ELSE cancelled_at END WHERE id=? AND status=? AND version=?",
                target.name(), target.name(), orderId, from, version);
        if (changed == 0) throw new ApiException(409, "OPTIMISTIC_LOCK_CONFLICT", "The order changed while applying this transition.");
        if (target == OrderStatus.CANCELLED) orders.restoreOrder(orderId, authenticated.userId(), authenticated.role(), reason, from);
        else jdbc.update("INSERT INTO order_status_history(order_id,from_status,to_status,actor_user_id,actor_role,reason) VALUES(?,?,?,?,?,?)", orderId, from, target.name(), authenticated.userId(), authenticated.role(), reason);
        jdbc.update("INSERT INTO outbox_event(aggregate_type,aggregate_id,event_type,payload) VALUES('Order',?,'order.status.changed',JSON_OBJECT('fromStatus',?,'toStatus',?,'version',?))",
                Long.toString(orderId), from, target.name(), version + 1);
        return orders.order(orderId, true);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam(required = false) LocalDate date,
                                         @RequestParam(defaultValue = "false") boolean refresh) {
        LocalDate selectedDate = date == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : date;
        String cacheKey = "webox:dashboard:" + selectedDate;
        if (!refresh) {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) try { return mapper.readValue(cached, new TypeReference<>() { }); } catch (Exception ignored) { redis.delete(cacheKey); }
        }
        Map<String, Object> result = makeDashboard(selectedDate);
        try { redis.opsForValue().set(cacheKey, mapper.writeValueAsString(result), 15, TimeUnit.SECONDS); } catch (Exception ignored) { }
        return result;
    }

    private Map<String, Object> makeDashboard(LocalDate date) {
        Map<String, Object> raw = jdbc.queryForMap("SELECT COUNT(*) AS total_orders, " +
                "COALESCE(SUM(CASE WHEN status<>'CANCELLED' THEN total_amount ELSE 0 END),0) AS revenue, " +
                "SUM(status='COMPLETED') AS completed,SUM(status='PENDING') AS pending,SUM(status='CONFIRMED') AS confirmed,SUM(status='CANCELLED') AS cancelled " +
                "FROM customer_order WHERE delivery_date=?", date);
        Map<String, Object> overview = new LinkedHashMap<>(); overview.put("totalOrders", asInt(raw.get("total_orders")));
        overview.put("revenue", money((BigDecimal) raw.get("revenue"))); overview.put("completed", asInt(raw.get("completed")));
        overview.put("pending", asInt(raw.get("pending"))); overview.put("confirmed", asInt(raw.get("confirmed"))); overview.put("cancelled", asInt(raw.get("cancelled")));
        List<Map<String, Object>> top = jdbc.query("SELECT oi.dish_id,oi.dish_name_snapshot,SUM(oi.quantity) AS quantity FROM customer_order o JOIN order_item oi ON oi.order_id=o.id " +
                        "WHERE o.delivery_date=? AND o.status<>'CANCELLED' GROUP BY oi.dish_id,oi.dish_name_snapshot ORDER BY quantity DESC,oi.dish_id LIMIT 10", (rs, n) -> {
                    Map<String, Object> item = new LinkedHashMap<>(); item.put("dishId", rs.getLong("dish_id")); item.put("name", rs.getString("dish_name_snapshot")); item.put("quantity", rs.getInt("quantity")); return item;
                }, date);
        List<Map<String, Object>> distribution = new ArrayList<>();
        for (String slot : List.of("LUNCH", "DINNER")) {
            int count = jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE delivery_date=? AND meal_slot=? AND status<>'CANCELLED'", Integer.class, date, slot);
            Map<String, Object> row = new LinkedHashMap<>(); row.put("mealSlot", slot); row.put("orders", count); distribution.add(row);
        }
        LocalDate start = date.minusDays(6); Map<LocalDate, Map<String, Object>> trendRows = new TreeMap<>();
        jdbc.query("SELECT delivery_date,COUNT(*) AS orders,COALESCE(SUM(CASE WHEN status<>'CANCELLED' THEN total_amount ELSE 0 END),0) AS revenue " +
                        "FROM customer_order WHERE delivery_date BETWEEN ? AND ? GROUP BY delivery_date", rs -> {
                    LocalDate day = rs.getDate("delivery_date").toLocalDate(); Map<String, Object> point = new LinkedHashMap<>(); point.put("date", day.toString());
                    point.put("orders", rs.getInt("orders")); point.put("revenue", money(rs.getBigDecimal("revenue"))); trendRows.put(day, point);
                }, start, date);
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 0; i < 7; i++) trend.add(trendRows.getOrDefault(start.plusDays(i), trendPoint(start.plusDays(i))));
        List<Map<String, Object>> lowStock = jdbc.query("SELECT d.id,d.name,mi.remaining_stock FROM daily_menu m JOIN daily_menu_item mi ON mi.daily_menu_id=m.id JOIN dish d ON d.id=mi.dish_id " +
                "WHERE m.menu_date=? AND m.status='PUBLISHED' AND mi.visible=TRUE AND mi.remaining_stock<=3 ORDER BY mi.remaining_stock,d.id", (rs, n) -> {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("dishId", rs.getLong("id")); item.put("name", rs.getString("name")); item.put("remainingStock", rs.getInt("remaining_stock")); return item;
        }, date);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("asOf", OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString()); result.put("date", date.toString());
        result.put("overview", overview); result.put("topDishes", top); result.put("mealDistribution", distribution); result.put("trend", trend); result.put("lowStock", lowStock); return result;
    }

    private Map<String, Object> trendPoint(LocalDate date) { Map<String, Object> row = new LinkedHashMap<>(); row.put("date", date.toString()); row.put("orders", 0); row.put("revenue", "0.00"); return row; }
    private Map<String, Object> pageMeta(int page, int size, long total) { Map<String, Object> r = new LinkedHashMap<>(); r.put("page", page); r.put("size", size); r.put("totalItems", total); r.put("totalPages", total == 0 ? 0 : (total + size - 1) / size); return r; }
    private void validatePage(int page, int size) { if (page < 0 || size < 1 || size > 100) throw invalid("page", "page must be non-negative and size must be between 1 and 100"); }
    private int asInt(Object value) { return value == null ? 0 : ((Number) value).intValue(); }
    private String money(BigDecimal value) { return value.setScale(2).toPlainString(); }
    private ApiException invalid(String field, String detail) { return new ApiException(400, "VALIDATION_FAILED", "The request contains invalid fields.", List.of(new ApiException.FieldError(field, detail))); }
}
