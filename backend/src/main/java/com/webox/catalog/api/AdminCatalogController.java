package com.webox.catalog.api;

import com.webox.platform.security.CurrentActor;
import com.webox.shared.ApiException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminCatalogController {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final Path mediaRoot;
    private final long maxMediaBytes;

    public AdminCatalogController(JdbcTemplate jdbc, StringRedisTemplate redis, @Value("${webox.media.root:./uploads}") String mediaRoot,
                                  @Value("${webox.media.max-bytes:5242880}") long maxMediaBytes) {
        this.jdbc = jdbc; this.redis = redis; this.mediaRoot = Path.of(mediaRoot); this.maxMediaBytes = maxMediaBytes;
    }

    @GetMapping("/dishes")
    public Map<String, Object> listDishes(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) Boolean published,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        checkPage(page, size);
        if (q != null && q.length() > 50) throw invalid("q", "must be at most 50 characters");
        StringBuilder where = new StringBuilder(" WHERE 1=1"); List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) { where.append(" AND (LOWER(name) LIKE ? OR LOWER(description) LIKE ?)"); String pattern = "%" + q.toLowerCase() + "%"; args.add(pattern); args.add(pattern); }
        if (category != null) { where.append(" AND category=?"); args.add(category); }
        if (published != null) { where.append(" AND published=?"); args.add(published); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM dish" + where, Long.class, args.toArray());
        args.add(size); args.add((long) page * size);
        List<Map<String, Object>> items = jdbc.query("SELECT id FROM dish" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", (rs, n) -> getDish(rs.getLong(1)), args.toArray());
        Map<String, Object> result = pageMeta(page, size, total == null ? 0 : total); result.put("items", items); return result;
    }

    @PostMapping("/dishes")
    @Transactional
    public ResponseEntity<Map<String, Object>> createDish(@RequestBody Map<String, Object> body) {
        DishInput input = dishInput(body);
        if (input.version() != 0) throw invalid("version", "must be 0 when creating");
        jdbc.update("INSERT INTO dish(name,description,base_price,category,protein_source,spice_level,image_url,published,version) VALUES(?,?,?,?,?,?,?,?,0)",
                input.name(), input.description(), input.price(), input.category(), input.protein(), input.spice(), input.imageUrl(), input.published());
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        saveChildren(id, input);
        return ResponseEntity.status(201).body(getDish(id));
    }

    @GetMapping("/dishes/{dishId}")
    public Map<String, Object> getDishPath(@PathVariable long dishId) { return getDish(dishId); }

    @PutMapping("/dishes/{dishId}")
    @Transactional
    public Map<String, Object> updateDish(@PathVariable long dishId, @RequestBody Map<String, Object> body) {
        DishInput input = dishInput(body);
        int changed = jdbc.update("UPDATE dish SET name=?,description=?,base_price=?,category=?,protein_source=?,spice_level=?,image_url=?,published=?,version=version+1 WHERE id=? AND version=?",
                input.name(), input.description(), input.price(), input.category(), input.protein(), input.spice(), input.imageUrl(), input.published(), dishId, input.version());
        if (changed == 0) ensureDishVersion(dishId, input.version());
        saveChildren(dishId, input);
        bumpMenuVersions(dishId);
        return getDish(dishId);
    }

    @PatchMapping("/dishes/{dishId}/publication")
    @Transactional
    public Map<String, Object> publication(@PathVariable long dishId, @RequestBody Map<String, Object> body) {
        Object published = body.get("published"), version = body.get("expectedVersion");
        if (!(published instanceof Boolean) || !(version instanceof Number)) throw invalid("published", "published and expectedVersion are required");
        int changed = jdbc.update("UPDATE dish SET published=?,version=version+1 WHERE id=? AND version=?", published, dishId, ((Number) version).longValue());
        if (changed == 0) ensureDishVersion(dishId, ((Number) version).longValue());
        bumpMenuVersions(dishId);
        return getDish(dishId);
    }

    @PostMapping("/media/images")
    public ResponseEntity<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty() || file.getSize() > maxMediaBytes) throw invalid("file", "image must not be empty or exceed the upload limit");
        String contentType = file.getContentType();
        byte[] data;
        try { data = file.getBytes(); } catch (IOException ex) { throw new ApiException(400, "VALIDATION_FAILED", "The image could not be read."); }
        String extension = imageExtension(contentType, data);
        if (extension == null) throw invalid("file", "must be a supported image file");
        String name = UUID.randomUUID() + "." + extension;
        try { Files.createDirectories(mediaRoot); Files.write(mediaRoot.resolve(name), data); }
        catch (IOException ex) { throw new ApiException(500, "MEDIA_STORAGE_FAILED", "The image could not be stored."); }
        return ResponseEntity.status(201).body(Map.of("url", "/media/images/" + name));
    }

    @GetMapping("/menus/{date}")
    public Map<String, Object> getMenu(@PathVariable LocalDate date) { return getDailyMenu(date); }

    @PutMapping("/menus/{date}")
    @Transactional
    public Map<String, Object> saveMenu(@PathVariable LocalDate date, @RequestBody Map<String, Object> body) {
        Number expectedValue = number(body.get("expectedPublicationVersion"), "expectedPublicationVersion");
        long expected = expectedValue.longValue();
        Object rawItems = body.get("items");
        if (!(rawItems instanceof List<?> itemList)) throw invalid("items", "must be an array");
        List<MenuItemInput> inputs = new ArrayList<>();
        for (Object raw : itemList) {
            if (!(raw instanceof Map<?, ?> item)) throw invalid("items", "contains an invalid item");
            long dishId = number(item.get("dishId"), "dishId").longValue();
            int initialStock = number(item.get("initialStock"), "initialStock").intValue();
            if (initialStock < 0 || !(item.get("visible") instanceof Boolean visible)) throw invalid("items", "initialStock and visible are invalid");
            if (inputs.stream().anyMatch(existing -> existing.dishId() == dishId)) throw invalid("items", "dishIds must be unique");
            inputs.add(new MenuItemInput(dishId, initialStock, visible));
        }
        List<Map<String, Object>> existingMenu = jdbc.queryForList("SELECT id,status,publication_version FROM daily_menu WHERE menu_date=? FOR UPDATE", date);
        long menuId;
        long currentVersion;
        String status;
        if (existingMenu.isEmpty()) {
            if (expected != 0) throw conflict("The menu publication version has changed.");
            jdbc.update("INSERT INTO daily_menu(menu_date,status,publication_version) VALUES(?,'DRAFT',1)", date);
            menuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class); currentVersion = 1; status = "DRAFT";
        } else {
            Map<String, Object> current = existingMenu.get(0);
            menuId = ((Number) current.get("id")).longValue(); currentVersion = ((Number) current.get("publication_version")).longValue(); status = current.get("status").toString();
            if (currentVersion != expected) throw conflict("The menu publication version has changed.");
            jdbc.update("UPDATE daily_menu SET publication_version=publication_version+1 WHERE id=? AND publication_version=?", menuId, expected);
            currentVersion++;
        }
        Map<Long, Map<String, Object>> previous = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT id,dish_id,initial_stock,remaining_stock,visible FROM daily_menu_item WHERE daily_menu_id=? FOR UPDATE", menuId))
            previous.put(((Number) row.get("dish_id")).longValue(), row);
        for (Map<String, Object> row : previous.values()) {
            long dishId = ((Number) row.get("dish_id")).longValue();
            if (inputs.stream().noneMatch(item -> item.dishId() == dishId)) {
                if (((Number) row.get("initial_stock")).intValue() != ((Number) row.get("remaining_stock")).intValue()) throw conflict("A menu item with sold stock cannot be removed.");
                jdbc.update("DELETE FROM daily_menu_item WHERE id=?", row.get("id"));
            }
        }
        for (MenuItemInput item : inputs) {
            ensurePublishedDish(item.dishId());
            Map<String, Object> old = previous.get(item.dishId());
            if (old == null) jdbc.update("INSERT INTO daily_menu_item(daily_menu_id,dish_id,initial_stock,remaining_stock,visible) VALUES(?,?,?,?,?)", menuId, item.dishId(), item.initialStock(), item.initialStock(), item.visible());
            else {
                int sold = ((Number) old.get("initial_stock")).intValue() - ((Number) old.get("remaining_stock")).intValue();
                if (item.initialStock() < sold) throw conflict("Initial stock cannot be lower than units already sold.");
                jdbc.update("UPDATE daily_menu_item SET initial_stock=?,remaining_stock=?,visible=?,stock_version=stock_version+1 WHERE id=?",
                        item.initialStock(), item.initialStock() - sold, item.visible(), old.get("id"));
            }
        }
        synchronizeInventoryAfterCommit(date, menuId, previous.values());
        return getDailyMenu(date);
    }

    private void synchronizeInventoryAfterCommit(LocalDate date, long menuId, java.util.Collection<Map<String, Object>> previous) {
        Map<Object, Object> stock = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT id,remaining_stock FROM daily_menu_item WHERE daily_menu_id=?", menuId))
            stock.put(Long.toString(((Number) row.get("id")).longValue()), row.get("remaining_stock").toString());
        List<Object> removed = previous.stream()
                .map(row -> Long.toString(((Number) row.get("id")).longValue()))
                .filter(id -> !stock.containsKey(id)).map(id -> (Object) id).toList();
        String key = "webox:inventory:{" + date + "}:stock";
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                if (!stock.isEmpty()) redis.opsForHash().putAll(key, stock);
                if (!removed.isEmpty()) redis.opsForHash().delete(key, removed.toArray());
            }
        });
    }

    @PostMapping("/menus/{date}/publish")
    @Transactional
    public Map<String, Object> publishMenu(@PathVariable LocalDate date) {
        List<Map<String, Object>> found = jdbc.queryForList("SELECT id FROM daily_menu WHERE menu_date=? FOR UPDATE", date);
        if (found.isEmpty()) throw new ApiException(404, "RESOURCE_NOT_FOUND", "The daily menu was not found.");
        long menuId = ((Number) found.get(0).get("id")).longValue();
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM daily_menu_item mi JOIN dish d ON d.id=mi.dish_id WHERE mi.daily_menu_id=? AND d.published=TRUE", Integer.class, menuId);
        if (count == 0) throw conflict("A menu requires at least one published dish before publication.");
        jdbc.update("UPDATE daily_menu SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP(3),publication_version=publication_version+1 WHERE id=?", menuId);
        return getDailyMenu(date);
    }

    private Map<String, Object> getDish(long dishId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,name,description,base_price,category,protein_source,spice_level,image_url,published,version FROM dish WHERE id=?", dishId);
        if (rows.isEmpty()) throw new ApiException(404, "RESOURCE_NOT_FOUND", "The dish was not found.");
        Map<String, Object> row = rows.get(0); Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", ((Number) row.get("id")).longValue()); result.put("name", row.get("name")); result.put("description", row.get("description"));
        result.put("basePrice", money((BigDecimal) row.get("base_price"))); result.put("category", row.get("category")); result.put("proteinSource", row.get("protein_source"));
        result.put("spiceLevel", row.get("spice_level")); result.put("imageUrl", row.get("image_url")); result.put("published", row.get("published")); result.put("version", ((Number) row.get("version")).longValue());
        long id = ((Number) row.get("id")).longValue();
        result.put("allergens", jdbc.queryForList("SELECT allergen FROM dish_allergen WHERE dish_id=? ORDER BY allergen", String.class, id));
        result.put("optionGroups", optionGroups(id)); return result;
    }

    private List<Map<String, Object>> optionGroups(long dishId) {
        return jdbc.query("SELECT id,name,selection_type,required_selection,min_selections,max_selections,display_order FROM dish_option_group WHERE dish_id=? ORDER BY display_order,id", (rs, i) -> {
            long id = rs.getLong("id"); Map<String, Object> group = new LinkedHashMap<>(); group.put("id", id); group.put("name", rs.getString("name"));
            group.put("selectionType", rs.getString("selection_type")); group.put("required", rs.getBoolean("required_selection")); group.put("minSelections", rs.getInt("min_selections"));
            group.put("maxSelections", rs.getInt("max_selections"));
            group.put("values", jdbc.query("SELECT id,name,price_delta,display_order,active FROM dish_option_value WHERE option_group_id=? ORDER BY display_order,id", (value, n) -> {
                Map<String, Object> v = new LinkedHashMap<>(); v.put("id", value.getLong("id")); v.put("name", value.getString("name")); v.put("priceDelta", money(value.getBigDecimal("price_delta"))); return v;
            }, id)); return group;
        }, dishId);
    }

    private DishInput dishInput(Map<String, Object> body) {
        String name = text(body.get("name"), "name", 1, 120); String description = text(body.get("description"), "description", 1, 1000);
        BigDecimal price = price(body.get("basePrice"), "basePrice"); String category = enumValue(body.get("category"), "category", List.of("CHINESE", "WESTERN", "JAPANESE", "LIGHT_MEAL", "KOREAN", "SOUTHEAST_ASIAN"));
        String protein = text(body.get("proteinSource"), "proteinSource", 1, 100); String spice = enumValue(body.get("spiceLevel"), "spiceLevel", List.of("NONE", "MILD", "MEDIUM", "HOT"));
        String imageUrl = text(body.get("imageUrl"), "imageUrl", 1, 500); boolean published = bool(body.get("published"), "published");
        long version = number(body.get("version"), "version").longValue();
        if (!(body.get("allergens") instanceof List<?> allergensRaw)) throw invalid("allergens", "must be an array");
        List<String> allergens = new ArrayList<>();
        for (Object allergen : allergensRaw) allergens.add(enumValue(allergen, "allergens", List.of("PEANUTS", "DAIRY", "EGG", "GLUTEN", "SOY", "FISH", "SHELLFISH")));
        if (allergens.size() != allergens.stream().distinct().count()) throw invalid("allergens", "must not contain duplicates");
        if (!(body.get("optionGroups") instanceof List<?> rawGroups)) throw invalid("optionGroups", "must be an array");
        List<GroupInput> groups = new ArrayList<>();
        for (Object raw : rawGroups) {
            if (!(raw instanceof Map<?, ?> group)) throw invalid("optionGroups", "contains an invalid group");
            String groupName = text(group.get("name"), "optionGroups.name", 1, 80);
            String type = enumValue(group.get("selectionType"), "optionGroups.selectionType", List.of("SINGLE", "MULTIPLE"));
            boolean required = bool(group.get("required"), "optionGroups.required");
            int min = number(group.get("minSelections"), "optionGroups.minSelections").intValue(); int max = number(group.get("maxSelections"), "optionGroups.maxSelections").intValue();
            int order = number(group.get("displayOrder"), "optionGroups.displayOrder").intValue(); boolean active = bool(group.get("active"), "optionGroups.active");
            if (min < 0 || max < 1 || max > 20 || min > max || (required && min < 1) || ("SINGLE".equals(type) && max != 1)) throw invalid("optionGroups", "selection bounds are invalid");
            if (!(group.get("values") instanceof List<?> rawValues) || rawValues.isEmpty()) throw invalid("optionGroups.values", "must contain at least one value");
            List<ValueInput> values = new ArrayList<>();
            for (Object rawValue : rawValues) {
                if (!(rawValue instanceof Map<?, ?> value)) throw invalid("optionGroups.values", "contains an invalid value");
                values.add(new ValueInput(text(value.get("name"), "optionGroups.values.name", 1, 80), price(value.get("priceDelta"), "optionGroups.values.priceDelta"),
                        number(value.get("displayOrder"), "optionGroups.values.displayOrder").intValue(), bool(value.get("active"), "optionGroups.values.active")));
            }
            groups.add(new GroupInput(groupName, type, required, min, max, order, active, values));
        }
        return new DishInput(name, description, price, category, protein, spice, allergens, imageUrl, published, version, groups);
    }

    private void saveChildren(long dishId, DishInput input) {
        jdbc.update("DELETE FROM dish_allergen WHERE dish_id=?", dishId);
        for (String allergen : input.allergens()) jdbc.update("INSERT INTO dish_allergen(dish_id,allergen) VALUES(?,?)", dishId, allergen);
        List<Long> oldGroups = jdbc.queryForList("SELECT id FROM dish_option_group WHERE dish_id=?", Long.class, dishId);
        for (Long groupId : oldGroups) jdbc.update("DELETE FROM dish_option_value WHERE option_group_id=?", groupId);
        jdbc.update("DELETE FROM dish_option_group WHERE dish_id=?", dishId);
        for (GroupInput group : input.groups()) {
            jdbc.update("INSERT INTO dish_option_group(dish_id,name,selection_type,required_selection,min_selections,max_selections,display_order,active) VALUES(?,?,?,?,?,?,?,?)",
                    dishId, group.name(), group.type(), group.required(), group.min(), group.max(), group.order(), group.active());
            long groupId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            for (ValueInput value : group.values()) jdbc.update("INSERT INTO dish_option_value(option_group_id,name,price_delta,display_order,active) VALUES(?,?,?,?,?)",
                    groupId, value.name(), value.delta(), value.order(), value.active());
        }
    }

    private void ensureDishVersion(long id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT version FROM dish WHERE id=?", id);
        if (rows.isEmpty()) throw new ApiException(404, "RESOURCE_NOT_FOUND", "The dish was not found.");
        throw conflict("The dish has changed. Reload it before saving.");
    }

    private Map<String, Object> getDailyMenu(LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,status,publication_version FROM daily_menu WHERE menu_date=?", date);
        if (rows.isEmpty()) throw new ApiException(404, "RESOURCE_NOT_FOUND", "The daily menu was not found.");
        Map<String, Object> row = rows.get(0); long menuId = ((Number) row.get("id")).longValue();
        List<Map<String, Object>> items = jdbc.query("SELECT mi.id,mi.dish_id,d.name,mi.initial_stock,mi.remaining_stock,mi.visible,mi.stock_version FROM daily_menu_item mi JOIN dish d ON d.id=mi.dish_id WHERE mi.daily_menu_id=? ORDER BY mi.id", (rs, n) -> {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("menuItemId", rs.getLong("id")); item.put("dishId", rs.getLong("dish_id")); item.put("dishName", rs.getString("name"));
            item.put("initialStock", rs.getInt("initial_stock")); item.put("remainingStock", rs.getInt("remaining_stock")); item.put("visible", rs.getBoolean("visible")); item.put("stockVersion", rs.getLong("stock_version")); return item;
        }, menuId);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("date", date.toString()); result.put("status", row.get("status")); result.put("publicationVersion", ((Number) row.get("publication_version")).longValue()); result.put("items", items); return result;
    }

    private void ensurePublishedDish(long id) { if (jdbc.queryForObject("SELECT COUNT(*) FROM dish WHERE id=? AND published=TRUE", Integer.class, id) == 0) throw new ApiException(404, "RESOURCE_NOT_FOUND", "A menu item must reference a published dish."); }
    private void bumpMenuVersions(long dishId) {
        jdbc.update("UPDATE daily_menu SET publication_version=publication_version+1 WHERE id IN (SELECT daily_menu_id FROM daily_menu_item WHERE dish_id=?)", dishId);
    }
    private String imageExtension(String contentType, byte[] bytes) {
        if (contentType == null) return null;
        if (contentType.equals("image/jpeg") && bytes.length > 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return "jpg";
        if (contentType.equals("image/png") && bytes.length > 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47) return "png";
        if (contentType.equals("image/gif") && bytes.length > 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "gif";
        if (contentType.equals("image/webp") && bytes.length > 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[8] == 'W' && bytes[9] == 'E') return "webp";
        return null;
    }
    private Map<String, Object> pageMeta(int page, int size, long total) { Map<String, Object> r = new LinkedHashMap<>(); r.put("page", page); r.put("size", size); r.put("totalItems", total); r.put("totalPages", total == 0 ? 0 : (total + size - 1) / size); return r; }
    private void checkPage(int page, int size) { if (page < 0 || size < 1 || size > 100) throw invalid("page", "page must be non-negative and size must be between 1 and 100"); }
    private String text(Object value, String field, int min, int max) { if (!(value instanceof String s) || s.isBlank() || s.length() < min || s.length() > max) throw invalid(field, "must contain " + min + " to " + max + " characters"); return s; }
    private String enumValue(Object value, String field, List<String> allowed) { if (!(value instanceof String s) || !allowed.contains(s)) throw invalid(field, "contains an unsupported value"); return s; }
    private boolean bool(Object value, String field) { if (!(value instanceof Boolean b)) throw invalid(field, "must be a boolean"); return b; }
    private Number number(Object value, String field) { if (!(value instanceof Number n)) throw invalid(field, "must be a number"); return n; }
    private BigDecimal price(Object value, String field) { try { BigDecimal result = new BigDecimal(value.toString()); if (result.signum() < 0 || result.scale() > 2) throw invalid(field, "must be a non-negative money value with two decimals"); return result.setScale(2); } catch (RuntimeException ex) { if (ex instanceof ApiException ae) throw ae; throw invalid(field, "must be a money string"); } }
    private String money(BigDecimal value) { return value.setScale(2).toPlainString(); }
    private ApiException invalid(String field, String detail) { return new ApiException(400, "VALIDATION_FAILED", "The request contains invalid fields.", List.of(new ApiException.FieldError(field, detail))); }
    private ApiException conflict(String detail) { return new ApiException(409, "OPTIMISTIC_LOCK_CONFLICT", detail); }
    private record DishInput(String name, String description, BigDecimal price, String category, String protein, String spice, List<String> allergens, String imageUrl, boolean published, long version, List<GroupInput> groups) { }
    private record GroupInput(String name, String type, boolean required, int min, int max, int order, boolean active, List<ValueInput> values) { }
    private record ValueInput(String name, BigDecimal delta, int order, boolean active) { }
    private record MenuItemInput(long dishId, int initialStock, boolean visible) { }
}
