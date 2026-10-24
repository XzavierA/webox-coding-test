package com.webox.ordering.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webox.ordering.domain.MealSlot;
import com.webox.ordering.domain.MealSlotResolver;
import com.webox.ordering.domain.OrderPricing;
import com.webox.ordering.domain.ResolvedSlot;
import com.webox.platform.security.AuthenticatedActor;
import com.webox.platform.security.CurrentActor;
import com.webox.shared.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/app")
public class OrderController {
    private static final String RESERVE_LUA = "local n=tonumber(ARGV[1]); " +
            "local rid=ARGV[2+n*2]; local day=ARGV[3+n*2]; local spec=''; " +
            "for i=1,n do if i>1 then spec=spec..',' end; spec=spec..ARGV[2+(i-1)*2]..':'..ARGV[3+(i-1)*2] end; " +
            "local prior=redis.call('HGET',KEYS[3],rid); if prior then if prior=='RESERVED|'..spec or prior=='CONFIRMED' then return {2,prior} else return {0,'RESERVATION_ID_CONFLICT',0} end end; " +
            "for i=1,n do local id=ARGV[2+(i-1)*2]; local qty=tonumber(ARGV[3+(i-1)*2]); local stock=tonumber(redis.call('HGET',KEYS[1],id) or '-1'); if stock<qty then return {0,id,stock} end end; " +
            "for i=1,n do local id=ARGV[2+(i-1)*2]; local qty=tonumber(ARGV[3+(i-1)*2]); redis.call('HINCRBY',KEYS[1],id,-qty) end; " +
            "redis.call('HSET',KEYS[3],rid,'RESERVED|'..spec); redis.call('XADD',KEYS[2],'*','type','RESERVED','reservationId',rid,'date',day,'quantities',spec,'userId',ARGV[4+n*2],'idempotencyKey',ARGV[5+n*2],'requestHash',ARGV[6+n*2]); return {1};";
    private static final String RESTORE_LUA = "local n=#ARGV/2; for i=1,n do redis.call('HINCRBY',KEYS[1],ARGV[(i-1)*2+1],tonumber(ARGV[(i-1)*2+2])) end; return 1;";
    private static final String COMPENSATE_LUA = "local state=redis.call('HGET',KEYS[2],ARGV[1]); if state=='CONFIRMED' or state=='COMPENSATED' then return 0 end; " +
            "local n=tonumber(ARGV[2]); for i=1,n do redis.call('HINCRBY',KEYS[1],ARGV[3+(i-1)*2],tonumber(ARGV[4+(i-1)*2])) end; redis.call('HSET',KEYS[2],ARGV[1],'COMPENSATED'); return 1;";
    private final JdbcTemplate jdbc;
    private final CurrentActor actor;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MealSlotResolver slotResolver = new MealSlotResolver(Clock.systemUTC());
    private final InventoryReservationRecovery recovery;
    private final OrderSubmissionClaimStore submissionClaims;
    private final DefaultRedisScript<List> reserveScript = new DefaultRedisScript<>(RESERVE_LUA, List.class);
    private final DefaultRedisScript<Long> restoreScript = new DefaultRedisScript<>(RESTORE_LUA, Long.class);
    private final DefaultRedisScript<Long> compensateScript = new DefaultRedisScript<>(COMPENSATE_LUA, Long.class);

    public OrderController(JdbcTemplate jdbc, CurrentActor actor, StringRedisTemplate redis, ObjectMapper mapper,
                           InventoryReservationRecovery recovery, OrderSubmissionClaimStore submissionClaims) {
        this.jdbc = jdbc; this.actor = actor; this.redis = redis; this.mapper = mapper; this.recovery = recovery; this.submissionClaims = submissionClaims;
    }

    @PostMapping("/orders/quote")
    public Map<String, Object> quote(@RequestBody Map<String, Object> body) {
        Actor context = current(); OrderInput input = parse(body); validateAddressChoice(context.userId(), input); Plan plan = plan(context.userId(), input);
        Map<String, Object> result = quoteResult(context.userId(), input, plan);
        return result;
    }

    @PostMapping("/orders")
    @Transactional
    public ResponseEntity<Map<String, Object>> place(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                     @RequestBody Map<String, Object> body) {
        if (key == null) throw invalid("Idempotency-Key", "must be a UUID");
        try { UUID.fromString(key); } catch (IllegalArgumentException ex) { throw invalid("Idempotency-Key", "must be a UUID"); }
        Actor context = current(); OrderInput input = parse(body);
        String hash = hash(body);
        List<Map<String, Object>> prior = jdbc.queryForList("SELECT request_hash,state,order_id,reservation_id,failure_code FROM idempotency_record WHERE user_id=? AND idempotency_key=?", context.userId(), key);
        Map<String, Object> claim;
        if (prior.isEmpty()) claim = submissionClaims.claim(context.userId(), key, hash, UUID.randomUUID().toString());
        else claim = prior.get(0);
        if (!hash.equals(claim.get("request_hash"))) throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "This idempotency key was already used for a different request.");
        if ("COMPLETED".equals(claim.get("state")) || "FAILED".equals(claim.get("state"))) return replay(claim, hash);
        Map<String, Object> locked = lockSubmission(context.userId(), key);
        if (locked == null) return acceptedSubmission(claim);
        if (!hash.equals(locked.get("request_hash"))) throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "This idempotency key was already used for a different request.");
        if ("COMPLETED".equals(locked.get("state")) || "FAILED".equals(locked.get("state"))) return replay(locked, hash);
        String reservationId = locked.get("reservation_id").toString();
        recordFailureAfterRollback(context.userId(), key, reservationId);
        Plan plan = plan(context.userId(), input);
        if (plan.lines().stream().anyMatch(line -> line.menuItemId() == 0)) throw new ApiException(422, "MENU_ITEM_UNAVAILABLE", "A requested dish is not available on the resolved menu.");
        if (!plan.unavailable().isEmpty()) throw new ApiException(409, "INVENTORY_INSUFFICIENT", "There is not enough stock to place the order.");
        requireAcceptedSlot(input, plan.slot());
        List<Map<String, Object>> active = jdbc.queryForList("SELECT id,order_number,status FROM customer_order WHERE user_id=? AND delivery_date=? AND meal_slot=? AND status IN ('PENDING','CONFIRMED')", context.userId(), plan.slot().deliveryDate(), plan.slot().mealSlot().name());
        if (!active.isEmpty()) throw new ApiException(409, "ACTIVE_ORDER_EXISTS", "An active order already exists for this delivery slot.");
        String address = resolveAddress(context.userId(), input);
        Map<Long, Integer> quantities = new TreeMap<>();
        for (Line line : plan.lines()) quantities.merge(line.menuItemId(), line.quantity(), Integer::sum);
        boolean[] reserved = {false};
        try {
            reserve(plan.slot().deliveryDate(), quantities, reservationId, context.userId(), key, hash);
            reserved[0] = true;
            String number = "WX" + LocalDate.now(MealSlotResolver.BUSINESS_ZONE).toString().replace("-", "") + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            jdbc.update("INSERT INTO customer_order(order_number,user_id,delivery_date,meal_slot,delivery_address,status,total_amount,version) VALUES(?,?,?,?,?,'PENDING',?,0)",
                    number, context.userId(), plan.slot().deliveryDate(), plan.slot().mealSlot().name(), address, plan.total());
            long orderId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("INSERT INTO order_status_history(order_id,from_status,to_status,actor_user_id,actor_role) VALUES(?,NULL,'PENDING',?,?)", orderId, context.userId(), context.role());
            for (Line line : plan.lines()) {
                int changed = jdbc.update("UPDATE daily_menu_item SET remaining_stock=remaining_stock-?,stock_version=stock_version+1 WHERE id=? AND remaining_stock>=?", line.quantity(), line.menuItemId(), line.quantity());
                if (changed == 0) throw new ApiException(409, "INVENTORY_INSUFFICIENT", "There is not enough stock to place the order.");
                int remaining = jdbc.queryForObject("SELECT remaining_stock FROM daily_menu_item WHERE id=?", Integer.class, line.menuItemId());
                jdbc.update("INSERT INTO order_item(order_id,daily_menu_item_id,dish_id,dish_name_snapshot,base_price_snapshot,unit_price,quantity,subtotal) VALUES(?,?,?,?,?,?,?,?)",
                        orderId, line.menuItemId(), line.dishId(), line.name(), line.basePrice(), line.unitPrice(), line.quantity(), line.subtotal());
                long itemId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                for (Option option : line.options()) jdbc.update("INSERT INTO order_item_option(order_item_id,option_group_name_snapshot,option_value_name_snapshot,price_delta_snapshot) VALUES(?,?,?,?)",
                        itemId, option.groupName(), option.name(), option.delta());
                jdbc.update("INSERT INTO inventory_movement(daily_menu_item_id,order_id,movement_type,quantity_delta,stock_after) VALUES(?,?,'ORDER_DEDUCT',?,?)",
                        line.menuItemId(), orderId, -line.quantity(), remaining);
                jdbc.update("INSERT INTO outbox_event(aggregate_type,aggregate_id,event_type,payload) VALUES('Order',?,'stock.changed',JSON_OBJECT('date',?,'menuItemId',?,'remainingStock',?,'stockVersion',?))",
                        Long.toString(orderId), plan.slot().deliveryDate().toString(), line.menuItemId(), remaining, jdbc.queryForObject("SELECT stock_version FROM daily_menu_item WHERE id=?", Long.class, line.menuItemId()));
            }
            jdbc.update("UPDATE idempotency_record SET state='COMPLETED',order_id=?,response_status=201,completed_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND idempotency_key=?",
                    orderId, context.userId(), key);
            jdbc.update("INSERT INTO outbox_event(aggregate_type,aggregate_id,event_type,payload) VALUES('Order',?,'order.placed',JSON_OBJECT('date',?))",
                    Long.toString(orderId), plan.slot().deliveryDate().toString());
            String reservationHash = reservationKey(plan.slot().deliveryDate());
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { redis.opsForHash().put(reservationHash, reservationId, "CONFIRMED"); }
                    });
            return ResponseEntity.status(HttpStatus.CREATED).body(order(orderId, false));
        } catch (RuntimeException ex) {
            if (reserved[0]) compensate(plan.slot().deliveryDate(), reservationId, quantities);
            throw ex;
        }
    }

    @GetMapping("/orders")
    public Map<String, Object> ownOrders(@RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        long userId = current().userId();
        String where = " WHERE user_id=?"; List<Object> args = new ArrayList<>(); args.add(userId);
        if (status != null) { where += " AND status=?"; args.add(status); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM customer_order" + where, Long.class, args.toArray());
        args.add(size); args.add((long) page * size);
        List<Long> ids = jdbc.queryForList("SELECT id FROM customer_order" + where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", Long.class, args.toArray());
        List<Map<String, Object>> items = ids.stream().map(id -> order(id, false)).toList();
        Map<String, Object> result = pageMeta(page, size, total == null ? 0 : total); result.put("items", items); return result;
    }

    @GetMapping("/orders/{orderId}")
    public Map<String, Object> ownOrder(@PathVariable long orderId) {
        Map<String, Object> order = order(orderId, false);
        if (((Number) order.get("userId")).longValue() != current().userId()) throw new ApiException(403, "ACCESS_DENIED", "The order belongs to another user.");
        return order;
    }

    @GetMapping("/order-submissions/{submissionId}")
    public Map<String, Object> submission(@PathVariable UUID submissionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT state,order_id,failure_code,user_id FROM idempotency_record WHERE reservation_id=?", submissionId.toString());
        if (rows.isEmpty()) throw new ApiException(404, "RESOURCE_NOT_FOUND", "The order submission was not found.");
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("user_id")).longValue() != current().userId()) throw new ApiException(403, "ACCESS_DENIED", "The submission belongs to another user.");
        Map<String, Object> result = new LinkedHashMap<>(); result.put("submissionId", submissionId); result.put("state", row.get("state"));
        result.put("statusUrl", "/api/v1/app/order-submissions/" + submissionId);
        result.put("order", row.get("order_id") == null ? null : order(((Number) row.get("order_id")).longValue(), false));
        result.put("failure", row.get("failure_code") == null ? null : Map.of("code", row.get("failure_code")));
        return result;
    }

    @PostMapping("/orders/{orderId}/cancel")
    @Transactional
    public Map<String, Object> cancelOwnOrder(@PathVariable long orderId, @RequestBody Map<String, Object> body) {
        long userId = current().userId(); long expected = number(body.get("expectedVersion"), "expectedVersion").longValue();
        Map<String, Object> row = orderRow(orderId);
        if (((Number) row.get("user_id")).longValue() != userId) throw new ApiException(403, "ACCESS_DENIED", "The order belongs to another user.");
        if (!"PENDING".equals(row.get("status")) || ((Number) row.get("version")).longValue() != expected) throw new ApiException(409, "OPTIMISTIC_LOCK_CONFLICT", "The order has changed and cannot be cancelled.");
        int changed = jdbc.update("UPDATE customer_order SET status='CANCELLED',version=version+1,cancelled_at=CURRENT_TIMESTAMP(3) WHERE id=? AND user_id=? AND status='PENDING' AND version=?", orderId, userId, expected);
        if (changed == 0) throw new ApiException(409, "OPTIMISTIC_LOCK_CONFLICT", "The order has changed and cannot be cancelled.");
        restoreOrder(orderId, userId, "EMPLOYEE", "Cancelled by employee", "PENDING");
        jdbc.update("INSERT INTO outbox_event(aggregate_type,aggregate_id,event_type,payload) VALUES('Order',?,'order.status.changed',JSON_OBJECT('fromStatus','PENDING','toStatus','CANCELLED','version',?))", Long.toString(orderId), expected + 1);
        return order(orderId, false);
    }

    private Map<String, Object> quoteResult(long userId, OrderInput input, Plan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resolvedDeliveryDate", plan.slot().deliveryDate().toString()); result.put("resolvedMealSlot", plan.slot().mealSlot().name()); result.put("slotAdjusted", plan.slot().adjusted());
        result.put("lines", plan.lines().stream().map(line -> {
            Map<String, Object> map = new LinkedHashMap<>(); map.put("dishId", line.dishId()); map.put("dishName", line.name()); map.put("quantity", line.quantity());
            map.put("optionValueIds", line.options().stream().map(Option::id).toList()); map.put("unitPrice", money(line.unitPrice())); map.put("subtotal", money(line.subtotal())); return map;
        }).toList());
        result.put("total", money(plan.total()));
        List<String> warnings = new ArrayList<>();
        if (plan.slot().adjusted()) warnings.add("SLOT_ADJUSTED");
        BigDecimal budgetMax = (BigDecimal) jdbc.query("SELECT budget_max FROM user_preference WHERE user_id=?", rs -> rs.next() ? rs.getBigDecimal(1) : null, userId);
        if (budgetMax != null && plan.total().compareTo(budgetMax) > 0) warnings.add("BUDGET_EXCEEDED");
        result.put("warnings", warnings); result.put("unavailableItems", plan.unavailable());
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT id,order_number,status FROM customer_order WHERE user_id=? AND delivery_date=? AND meal_slot=? AND status IN ('PENDING','CONFIRMED')", userId, plan.slot().deliveryDate(), plan.slot().mealSlot().name());
        if (existing.isEmpty()) result.put("existingOrder", null);
        else { Map<String, Object> value = new LinkedHashMap<>(); value.put("id", existing.get(0).get("id")); value.put("orderNumber", existing.get(0).get("order_number")); value.put("status", existing.get(0).get("status")); result.put("existingOrder", value); }
        return result;
    }

    private ResponseEntity<Map<String, Object>> replay(Map<String, Object> row, String hash) {
        if (!hash.equals(row.get("request_hash"))) throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "This idempotency key was already used for a different request.");
        if ("COMPLETED".equals(row.get("state"))) return ResponseEntity.ok(order(((Number) row.get("order_id")).longValue(), false));
        if ("FAILED".equals(row.get("state"))) throw new ApiException(409, String.valueOf(row.get("failure_code")), "The previous order submission failed.");
        return acceptedSubmission(row);
    }

    private ResponseEntity<Map<String, Object>> acceptedSubmission(Map<String, Object> row) {
        String submissionId = row.get("reservation_id").toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("submissionId", submissionId); body.put("state", "PROCESSING");
        body.put("statusUrl", "/api/v1/app/order-submissions/" + submissionId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).header("Retry-After", "1").body(body);
    }

    private Map<String, Object> lockSubmission(long userId, String key) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT request_hash,state,order_id,reservation_id,failure_code FROM idempotency_record " +
                    "WHERE user_id=? AND idempotency_key=? FOR UPDATE NOWAIT", userId, key);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessException ex) {
            Throwable cause = ex;
            while (cause != null) {
                if (cause instanceof java.sql.SQLException sql && sql.getErrorCode() == 3572) return null;
                cause = cause.getCause();
            }
            throw ex;
        }
    }

    private Plan plan(long userId, OrderInput input) {
        ResolvedSlot resolved;
        try { resolved = slotResolver.resolve(input.date(), input.slot()); }
        catch (IllegalArgumentException ex) { throw invalid("deliveryDate", ex.getMessage()); }
        List<Line> lines = new ArrayList<>(); List<Map<String, Object>> unavailable = new ArrayList<>();
        for (RequestLine requested : input.lines()) {
            List<Map<String, Object>> items = jdbc.queryForList("SELECT mi.id,mi.remaining_stock,d.id AS dish_id,d.name,d.base_price FROM daily_menu m JOIN daily_menu_item mi ON mi.daily_menu_id=m.id JOIN dish d ON d.id=mi.dish_id WHERE m.menu_date=? AND m.status='PUBLISHED' AND mi.visible=TRUE AND d.published=TRUE AND d.id=?", resolved.deliveryDate(), requested.dishId());
            if (items.isEmpty()) {
                List<Map<String, Object>> dish = jdbc.queryForList("SELECT name,base_price FROM dish WHERE id=? AND published=TRUE", requested.dishId());
                if (dish.isEmpty()) throw new ApiException(422, "MENU_ITEM_UNAVAILABLE", "A requested dish is not available on the resolved menu.");
                unavailable.add(conflictItem(requested.dishId(), dish.get(0).get("name").toString(), requested.quantity(), 0));
                continue;
            }
            Map<String, Object> item = items.get(0); List<Option> options = selectedOptions(requested.dishId(), requested.optionIds());
            BigDecimal base = (BigDecimal) item.get("base_price"); BigDecimal unit = base;
            for (Option option : options) unit = unit.add(option.delta());
            BigDecimal subtotal = OrderPricing.subtotal(base, options.stream().map(Option::delta).toList(), requested.quantity());
            int remaining = ((Number) item.get("remaining_stock")).intValue();
            if (remaining < requested.quantity()) unavailable.add(conflictItem(requested.dishId(), item.get("name").toString(), requested.quantity(), remaining));
            lines.add(new Line(requested.dishId(), ((Number) item.get("id")).longValue(), item.get("name").toString(), base, unit.setScale(2), requested.quantity(), subtotal, options));
        }
        if (lines.size() != input.lines().size()) {
            for (RequestLine requested : input.lines()) if (lines.stream().noneMatch(l -> l.dishId() == requested.dishId())) {
                List<Map<String, Object>> dish = jdbc.queryForList("SELECT id,name,base_price FROM dish WHERE id=? AND published=TRUE", requested.dishId());
                if (!dish.isEmpty()) {
                    List<Option> options = selectedOptions(requested.dishId(), requested.optionIds()); BigDecimal base = (BigDecimal) dish.get(0).get("base_price"), unit = base;
                    for (Option option : options) unit = unit.add(option.delta());
                    BigDecimal subtotal = OrderPricing.subtotal(base, options.stream().map(Option::delta).toList(), requested.quantity());
                    lines.add(new Line(requested.dishId(), 0, dish.get(0).get("name").toString(), base, unit.setScale(2), requested.quantity(), subtotal, options));
                }
            }
        }
        BigDecimal total = lines.stream().map(Line::subtotal).reduce(new BigDecimal("0.00"), BigDecimal::add).setScale(2);
        return new Plan(resolved, lines, unavailable, total);
    }

    private List<Option> selectedOptions(long dishId, List<Long> selectedIds) {
        List<Map<String, Object>> groups = jdbc.queryForList("SELECT id,name,required_selection,min_selections,max_selections FROM dish_option_group WHERE dish_id=? AND active=TRUE", dishId);
        List<Option> selected = new ArrayList<>(); SetLong seen = new SetLong();
        for (Map<String, Object> group : groups) {
            long groupId = ((Number) group.get("id")).longValue();
            List<Map<String, Object>> groupValues = jdbc.queryForList("SELECT id,name,price_delta FROM dish_option_value WHERE option_group_id=? AND active=TRUE", groupId);
            List<Map<String, Object>> chosen = groupValues.stream().filter(v -> selectedIds.contains(((Number) v.get("id")).longValue())).toList();
            int min = ((Number) group.get("min_selections")).intValue(), max = ((Number) group.get("max_selections")).intValue();
            if (chosen.size() < min || chosen.size() > max || (Boolean) group.get("required_selection") && chosen.isEmpty()) throw invalid("items.optionValueIds", "does not satisfy option group selections");
            for (Map<String, Object> value : chosen) {
                long id = ((Number) value.get("id")).longValue(); seen.add(id);
                selected.add(new Option(id, group.get("name").toString(), value.get("name").toString(), (BigDecimal) value.get("price_delta")));
            }
        }
        if (seen.size() != selectedIds.size()) throw invalid("items.optionValueIds", "contains an option that is not active for the selected dish");
        return selected;
    }

    private void reserve(LocalDate date, Map<Long, Integer> quantities, String reservationId, long userId,
                         String idempotencyKey, String requestHash) {
        String stockKey = stockKey(date), streamKey = "webox:inventory:{" + date + "}:events";
        quantities.forEach((id, qty) -> {
            String stock = jdbc.queryForObject("SELECT remaining_stock FROM daily_menu_item WHERE id=?", String.class, id);
            redis.opsForHash().putIfAbsent(stockKey, Long.toString(id), stock);
        });
        List<String> args = new ArrayList<>(); args.add(Integer.toString(quantities.size()));
        quantities.forEach((id, qty) -> { args.add(id.toString()); args.add(qty.toString()); });
        args.add(reservationId); args.add(date.toString()); args.add(Long.toString(userId)); args.add(idempotencyKey); args.add(requestHash);
        List<?> result = redis.execute(reserveScript, List.of(stockKey, streamKey, reservationKey(date)), args.toArray());
        recovery.ensureGroup(streamKey);
        if (result == null || result.isEmpty() || Integer.parseInt(result.get(0).toString()) != 1) {
            if (result != null && !result.isEmpty() && Integer.parseInt(result.get(0).toString()) == 2) return;
            String id = result != null && result.size() > 1 ? result.get(1).toString() : "0";
            String remaining = result != null && result.size() > 2 ? result.get(2).toString() : "0";
            throw new ApiException(409, "INVENTORY_INSUFFICIENT", "There is not enough stock to place the order.",
                    List.of(new ApiException.FieldError("items", "menu item " + id + " has " + remaining + " servings remaining")));
        }
    }

    private void restore(LocalDate date, Map<Long, Integer> quantities) {
        List<String> args = new ArrayList<>(); quantities.forEach((id, qty) -> { args.add(id.toString()); args.add(qty.toString()); });
        if (!args.isEmpty()) redis.execute(restoreScript, List.of(stockKey(date)), args.toArray());
    }
    private void compensate(LocalDate date, String reservationId, Map<Long, Integer> quantities) {
        List<String> args = new ArrayList<>(); args.add(reservationId); args.add(Integer.toString(quantities.size()));
        quantities.forEach((id, qty) -> { args.add(id.toString()); args.add(qty.toString()); });
        redis.execute(compensateScript, List.of(stockKey(date), reservationKey(date)), args.toArray());
    }
    private void recordFailureAfterRollback(long userId, String key, String reservationId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) return;
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED) return;
                        jdbc.update("UPDATE idempotency_record SET state='FAILED',failure_code='ORDER_CONFIRMATION_FAILED',completed_at=CURRENT_TIMESTAMP(3) " +
                                        "WHERE user_id=? AND idempotency_key=? AND reservation_id=? AND state='PROCESSING'",
                                userId, key, reservationId);
                    }
                });
    }
    private String stockKey(LocalDate date) { return "webox:inventory:{" + date + "}:stock"; }
    private String reservationKey(LocalDate date) { return "webox:inventory:{" + date + "}:reservations"; }

    private String resolveAddress(long userId, OrderInput input) {
        validateAddressChoice(userId, input);
        if ((input.addressId() == null) == (input.newAddress() == null)) throw invalid("addressId", "exactly one of addressId and newAddress is required");
        if (input.addressId() != null) {
            List<String> values = jdbc.queryForList("SELECT address_text FROM user_address WHERE id=? AND user_id=? AND deleted_at IS NULL", String.class, input.addressId(), userId);
            if (values.isEmpty()) throw new ApiException(404, "RESOURCE_NOT_FOUND", "The saved address was not found.");
            return values.get(0);
        }
        InlineAddress address = input.newAddress();
        if (address.text().isBlank() || address.text().length() > 200 || address.label() != null && address.label().length() > 50) throw invalid("newAddress", "contains an invalid address");
        if (address.save()) {
            if (address.isDefault()) jdbc.update("UPDATE user_address SET is_default=FALSE WHERE user_id=? AND deleted_at IS NULL", userId);
            jdbc.update("INSERT INTO user_address(user_id,label,address_text,is_default) VALUES(?,?,?,?)", userId, address.label(), address.text(), address.isDefault());
        }
        return address.text();
    }

    private void validateAddressChoice(long userId, OrderInput input) {
        if ((input.addressId() == null) == (input.newAddress() == null)) throw invalid("addressId", "exactly one of addressId and newAddress is required");
        if (input.addressId() != null) {
            int found = jdbc.queryForObject("SELECT COUNT(*) FROM user_address WHERE id=? AND user_id=? AND deleted_at IS NULL", Integer.class, input.addressId(), userId);
            if (found == 0) throw new ApiException(404, "RESOURCE_NOT_FOUND", "The saved address was not found.");
        } else {
            InlineAddress address = input.newAddress();
            if (address.label() == null || address.label().length() > 50 || address.text().isBlank() || address.text().length() > 200)
                throw invalid("newAddress", "contains an invalid address");
        }
    }

    private void requireAcceptedSlot(OrderInput input, ResolvedSlot resolved) {
        if (resolved.adjusted() && (input.acceptedDate() == null || input.acceptedSlot() == null)) throw new ApiException(422, "ORDER_SLOT_ADJUSTED", "The requested delivery slot changed; review and accept the resolved slot.");
        if (input.acceptedDate() != null && !input.acceptedDate().equals(resolved.deliveryDate())
                || input.acceptedSlot() != null && input.acceptedSlot() != resolved.mealSlot())
            throw new ApiException(422, "ORDER_SLOT_ADJUSTED", "The accepted delivery slot does not match the current resolved slot.");
    }

    Map<String, Object> order(long orderId, boolean includeHistory) {
        Map<String, Object> row = orderRow(orderId); long userId = ((Number) row.get("user_id")).longValue();
        Map<String, Object> result = new LinkedHashMap<>(); result.put("id", orderId); result.put("orderNumber", row.get("order_number")); result.put("userId", userId);
        result.put("userDisplayName", row.get("display_name")); result.put("deliveryDate", row.get("delivery_date").toString()); result.put("mealSlot", row.get("meal_slot"));
        result.put("deliveryAddress", row.get("delivery_address")); result.put("status", row.get("status")); result.put("total", money((BigDecimal) row.get("total_amount")));
        result.put("version", ((Number) row.get("version")).longValue()); result.put("createdAt", timestamp(row.get("created_at")));
        List<Map<String, Object>> items = jdbc.query("SELECT id,dish_id,dish_name_snapshot,base_price_snapshot,unit_price,quantity,subtotal FROM order_item WHERE order_id=? ORDER BY id", (rs, i) -> {
            long itemId = rs.getLong("id"); Map<String, Object> item = new LinkedHashMap<>(); item.put("id", itemId); item.put("dishId", rs.getLong("dish_id")); item.put("dishName", rs.getString("dish_name_snapshot"));
            item.put("basePrice", money(rs.getBigDecimal("base_price_snapshot"))); item.put("unitPrice", money(rs.getBigDecimal("unit_price"))); item.put("quantity", rs.getInt("quantity")); item.put("subtotal", money(rs.getBigDecimal("subtotal")));
            item.put("options", jdbc.query("SELECT option_group_name_snapshot,option_value_name_snapshot,price_delta_snapshot FROM order_item_option WHERE order_item_id=? ORDER BY id", (o, x) -> {
                Map<String, Object> option = new LinkedHashMap<>(); option.put("groupName", o.getString(1)); option.put("valueName", o.getString(2)); option.put("priceDelta", money(o.getBigDecimal(3))); return option;
            }, itemId)); return item;
        }, orderId);
        result.put("items", items);
        List<Map<String, Object>> history = jdbc.query("SELECT h.from_status,h.to_status,h.actor_role,u.display_name,h.reason,h.created_at FROM order_status_history h JOIN app_user u ON u.id=h.actor_user_id WHERE h.order_id=? ORDER BY h.id", (rs, i) -> {
            Map<String, Object> entry = new LinkedHashMap<>(); entry.put("fromStatus", rs.getString("from_status")); entry.put("toStatus", rs.getString("to_status")); entry.put("actorRole", rs.getString("actor_role"));
            entry.put("actorDisplayName", rs.getString("display_name")); entry.put("reason", rs.getString("reason")); entry.put("createdAt", rs.getTimestamp("created_at").toInstant().toString()); return entry;
        }, orderId);
        result.put("statusHistory", history); return result;
    }

    Map<String, Object> orderRow(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT o.*,u.display_name FROM customer_order o JOIN app_user u ON u.id=o.user_id WHERE o.id=?", id);
        if (rows.isEmpty()) throw new ApiException(404, "RESOURCE_NOT_FOUND", "The order was not found."); return rows.get(0);
    }

    void restoreOrder(long orderId, long actorId, String role, String reason, String fromStatus) {
        LocalDate date = LocalDate.parse(orderRow(orderId).get("delivery_date").toString());
        List<Map<String, Object>> items = jdbc.queryForList("SELECT daily_menu_item_id,quantity FROM order_item WHERE order_id=?", orderId);
        Map<Long, Integer> restore = new TreeMap<>();
        for (Map<String, Object> item : items) {
            long menuItemId = ((Number) item.get("daily_menu_item_id")).longValue(); int quantity = ((Number) item.get("quantity")).intValue();
            int already = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE order_id=? AND daily_menu_item_id=? AND movement_type='CANCEL_RESTORE'", Integer.class, orderId, menuItemId);
            if (already == 0) {
                jdbc.update("UPDATE daily_menu_item SET remaining_stock=remaining_stock+?,stock_version=stock_version+1 WHERE id=?", quantity, menuItemId);
                int after = jdbc.queryForObject("SELECT remaining_stock FROM daily_menu_item WHERE id=?", Integer.class, menuItemId);
                jdbc.update("INSERT INTO inventory_movement(daily_menu_item_id,order_id,movement_type,quantity_delta,stock_after) VALUES(?,?,'CANCEL_RESTORE',?,?)", menuItemId, orderId, quantity, after);
                jdbc.update("INSERT INTO outbox_event(aggregate_type,aggregate_id,event_type,payload) VALUES('Order',?,'stock.changed',JSON_OBJECT('date',?,'menuItemId',?,'remainingStock',?,'stockVersion',?))",
                        Long.toString(orderId), date.toString(), menuItemId, after,
                        jdbc.queryForObject("SELECT stock_version FROM daily_menu_item WHERE id=?", Long.class, menuItemId));
                restore.put(menuItemId, quantity);
            }
        }
        List<String> args = new ArrayList<>(); restore.forEach((id, quantity) -> { args.add(id.toString()); args.add(quantity.toString()); });
        if (!args.isEmpty()) redis.execute(restoreScript, List.of(stockKey(date)), args.toArray());
        jdbc.update("INSERT INTO order_status_history(order_id,from_status,to_status,actor_user_id,actor_role,reason) VALUES(?,?,'CANCELLED',?,?,?)", orderId, fromStatus, actorId, role, reason);
    }

    private Actor current() { AuthenticatedActor authenticated = actor.require(); return new Actor(authenticated.userId(), authenticated.role()); }
    private OrderInput parse(Map<String, Object> body) {
        LocalDate date; try { date = LocalDate.parse(String.valueOf(body.get("deliveryDate"))); } catch (Exception ex) { throw invalid("deliveryDate", "must be a date"); }
        MealSlot slot; try { slot = MealSlot.valueOf(String.valueOf(body.get("mealSlot"))); } catch (Exception ex) { throw invalid("mealSlot", "must be LUNCH or DINNER"); }
        Long address = body.get("addressId") == null ? null : number(body.get("addressId"), "addressId").longValue();
        InlineAddress newAddress = null;
        if (body.get("newAddress") instanceof Map<?, ?> raw) {
            if (!(raw.get("isDefault") instanceof Boolean) || !(raw.get("save") instanceof Boolean) || !(raw.get("addressText") instanceof String))
                throw invalid("newAddress", "label, addressText, isDefault, and save are required");
            newAddress = new InlineAddress(raw.get("label") == null ? null : raw.get("label").toString(), raw.get("addressText").toString(),
                    (Boolean) raw.get("isDefault"), (Boolean) raw.get("save"));
        }
        LocalDate acceptedDate = null; if (body.get("acceptedResolvedDeliveryDate") != null) try { acceptedDate = LocalDate.parse(body.get("acceptedResolvedDeliveryDate").toString()); } catch (Exception ex) { throw invalid("acceptedResolvedDeliveryDate", "must be a date"); }
        MealSlot acceptedSlot = null; if (body.get("acceptedResolvedMealSlot") != null) try { acceptedSlot = MealSlot.valueOf(body.get("acceptedResolvedMealSlot").toString()); } catch (Exception ex) { throw invalid("acceptedResolvedMealSlot", "must be LUNCH or DINNER"); }
        if (!(body.get("items") instanceof List<?> rawItems) || rawItems.isEmpty() || rawItems.size() > 5) throw invalid("items", "must contain 1 to 5 lines");
        List<RequestLine> lines = new ArrayList<>(); List<Integer> quantities = new ArrayList<>();
        for (Object raw : rawItems) {
            if (!(raw instanceof Map<?, ?> line)) throw invalid("items", "contains an invalid line");
            long dishId = number(line.get("dishId"), "dishId").longValue(); int quantity = number(line.get("quantity"), "quantity").intValue();
            if (!(line.get("optionValueIds") instanceof List<?> rawIds)) throw invalid("optionValueIds", "must be an array");
            List<Long> ids = rawIds.stream().map(id -> number(id, "optionValueIds").longValue()).toList();
            if (ids.size() != ids.stream().distinct().count()) throw invalid("optionValueIds", "must not contain duplicates");
            lines.add(new RequestLine(dishId, quantity, ids)); quantities.add(quantity);
        }
        try { OrderPricing.requireTotalQuantity(quantities); } catch (IllegalArgumentException ex) { throw invalid("items", ex.getMessage()); }
        return new OrderInput(date, slot, address, newAddress, acceptedDate, acceptedSlot, lines);
    }
    private String hash(Map<String, Object> body) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(new TreeMap<>(body)).getBytes(StandardCharsets.UTF_8)); return HexFormat.of().formatHex(digest); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private Map<String, Object> conflictItem(long dishId, String name, int requested, int remaining) { Map<String, Object> item = new LinkedHashMap<>(); item.put("dishId", dishId); item.put("name", name); item.put("requested", requested); item.put("remaining", remaining); return item; }
    private Map<String, Object> pageMeta(int page, int size, long total) { Map<String, Object> r = new LinkedHashMap<>(); r.put("page", page); r.put("size", size); r.put("totalItems", total); r.put("totalPages", total == 0 ? 0 : (total + size - 1) / size); return r; }
    private void validatePage(int page, int size) { if (page < 0 || size < 1 || size > 100) throw invalid("page", "page must be non-negative and size must be between 1 and 100"); }
    private Number number(Object value, String field) { if (!(value instanceof Number n)) throw invalid(field, "must be a number"); return n; }
    private String money(BigDecimal amount) { return amount.setScale(2).toPlainString(); }
    private String timestamp(Object value) {
        if (value instanceof java.time.LocalDateTime local) return local.atZone(MealSlotResolver.BUSINESS_ZONE).toOffsetDateTime().toString();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().toString();
        return value.toString();
    }
    private ApiException invalid(String field, String detail) { return new ApiException(400, "VALIDATION_FAILED", "The request contains invalid fields.", List.of(new ApiException.FieldError(field, detail))); }
    private record Actor(long userId, String role) { }
    private record OrderInput(LocalDate date, MealSlot slot, Long addressId, InlineAddress newAddress, LocalDate acceptedDate, MealSlot acceptedSlot, List<RequestLine> lines) { }
    private record InlineAddress(String label, String text, boolean isDefault, boolean save) { }
    private record RequestLine(long dishId, int quantity, List<Long> optionIds) { }
    private record Option(long id, String groupName, String name, BigDecimal delta) { }
    private record Line(long dishId, long menuItemId, String name, BigDecimal basePrice, BigDecimal unitPrice, int quantity, BigDecimal subtotal, List<Option> options) { }
    private record Plan(ResolvedSlot slot, List<Line> lines, List<Map<String, Object>> unavailable, BigDecimal total) { }
    private static final class SetLong extends java.util.HashSet<Long> { private static final long serialVersionUID = 1L; }
}
