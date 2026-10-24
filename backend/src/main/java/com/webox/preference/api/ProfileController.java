package com.webox.preference.api;

import com.webox.platform.security.AuthenticatedActor;
import com.webox.platform.security.CurrentActor;
import com.webox.shared.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/app")
public class ProfileController {
    private static final List<String> ALLERGENS = List.of("PEANUTS", "DAIRY", "EGG", "GLUTEN", "SOY", "FISH", "SHELLFISH");
    private static final List<String> CATEGORIES = List.of("CHINESE", "WESTERN", "JAPANESE", "LIGHT_MEAL", "KOREAN", "SOUTHEAST_ASIAN");
    private static final List<String> SPICE = List.of("NONE", "MILD", "MEDIUM", "HOT");
    private static final List<String> TASTE = List.of("LIGHT", "BALANCED", "RICH");

    private final JdbcTemplate jdbc;
    private final CurrentActor currentActor;

    public ProfileController(JdbcTemplate jdbc, CurrentActor currentActor) {
        this.jdbc = jdbc;
        this.currentActor = currentActor;
    }

    @GetMapping("/preferences")
    public Map<String, Object> getPreferences() {
        long userId = currentActor.require().userId();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT spice_level,taste_intensity,budget_min,budget_max FROM user_preference WHERE user_id=?", userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allergens", jdbc.queryForList("SELECT allergen FROM user_allergen WHERE user_id=? ORDER BY allergen", String.class, userId));
        result.put("preferredCategories", jdbc.queryForList("SELECT category FROM user_preferred_category WHERE user_id=? ORDER BY category", String.class, userId));
        if (rows.isEmpty()) {
            result.put("spiceLevel", null); result.put("tasteIntensity", null); result.put("budgetMin", null); result.put("budgetMax", null);
        } else {
            Map<String, Object> row = rows.get(0);
            result.put("spiceLevel", row.get("spice_level")); result.put("tasteIntensity", row.get("taste_intensity"));
            result.put("budgetMin", money((BigDecimal) row.get("budget_min"))); result.put("budgetMax", money((BigDecimal) row.get("budget_max")));
        }
        return result;
    }

    @PutMapping("/preferences")
    @Transactional
    public Map<String, Object> updatePreferences(@RequestBody Map<String, Object> body) {
        long userId = currentActor.require().userId();
        List<String> allergens = stringList(body.get("allergens"), "allergens", ALLERGENS);
        List<String> categories = stringList(body.get("preferredCategories"), "preferredCategories", CATEGORIES);
        String spice = nullableString(body.get("spiceLevel"), "spiceLevel", SPICE);
        String taste = nullableString(body.get("tasteIntensity"), "tasteIntensity", TASTE);
        BigDecimal min = nullableMoney(body.get("budgetMin"), "budgetMin");
        BigDecimal max = nullableMoney(body.get("budgetMax"), "budgetMax");
        if (min != null && max != null && min.compareTo(max) > 0) throw invalid("budgetMin", "must not exceed budgetMax");
        jdbc.update("INSERT INTO user_preference(user_id,spice_level,taste_intensity,budget_min,budget_max) VALUES(?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE spice_level=VALUES(spice_level),taste_intensity=VALUES(taste_intensity),budget_min=VALUES(budget_min),budget_max=VALUES(budget_max)",
                userId, spice, taste, min, max);
        jdbc.update("DELETE FROM user_allergen WHERE user_id=?", userId);
        jdbc.update("DELETE FROM user_preferred_category WHERE user_id=?", userId);
        allergens.forEach(value -> jdbc.update("INSERT INTO user_allergen(user_id,allergen) VALUES(?,?)", userId, value));
        categories.forEach(value -> jdbc.update("INSERT INTO user_preferred_category(user_id,category) VALUES(?,?)", userId, value));
        return getPreferences();
    }

    @GetMapping("/addresses")
    public List<Map<String, Object>> listAddresses() {
        return jdbc.query("SELECT id,label,address_text,is_default FROM user_address WHERE user_id=? AND deleted_at IS NULL ORDER BY is_default DESC,id",
                (rs, n) -> address(rs.getLong("id"), rs.getString("label"), rs.getString("address_text"), rs.getBoolean("is_default")), currentActor.require().userId());
    }

    @PostMapping("/addresses")
    @Transactional
    public org.springframework.http.ResponseEntity<Map<String, Object>> createAddress(@RequestBody Map<String, Object> body) {
        long userId = currentActor.require().userId();
        AddressWrite input = addressWrite(body);
        if (input.isDefault()) jdbc.update("UPDATE user_address SET is_default=FALSE WHERE user_id=? AND deleted_at IS NULL", userId);
        jdbc.update("INSERT INTO user_address(user_id,label,address_text,is_default) VALUES(?,?,?,?)", userId, input.label(), input.text(), input.isDefault());
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return org.springframework.http.ResponseEntity.status(201).body(address(id, input.label(), input.text(), input.isDefault()));
    }

    @PatchMapping("/addresses/{addressId}")
    @Transactional
    public Map<String, Object> updateAddress(@PathVariable long addressId, @RequestBody Map<String, Object> body) {
        long userId = currentActor.require().userId();
        AddressWrite input = addressWrite(body);
        int owner = jdbc.queryForObject("SELECT COUNT(*) FROM user_address WHERE id=? AND user_id=? AND deleted_at IS NULL", Integer.class, addressId, userId);
        if (owner == 0) {
            int exists = jdbc.queryForObject("SELECT COUNT(*) FROM user_address WHERE id=? AND deleted_at IS NULL", Integer.class, addressId);
            if (exists > 0) throw new ApiException(403, "ACCESS_DENIED", "The address belongs to another user.");
            throw new ApiException(404, "RESOURCE_NOT_FOUND", "The address was not found.");
        }
        if (input.isDefault()) jdbc.update("UPDATE user_address SET is_default=FALSE WHERE user_id=? AND id<>? AND deleted_at IS NULL", userId, addressId);
        jdbc.update("UPDATE user_address SET label=?,address_text=?,is_default=? WHERE id=? AND user_id=?", input.label(), input.text(), input.isDefault(), addressId, userId);
        return address(addressId, input.label(), input.text(), input.isDefault());
    }

    @DeleteMapping("/addresses/{addressId}")
    @Transactional
    public org.springframework.http.ResponseEntity<Void> deleteAddress(@PathVariable long addressId) {
        long userId = currentActor.require().userId();
        int updated = jdbc.update("UPDATE user_address SET deleted_at=CURRENT_TIMESTAMP(3),is_default=FALSE WHERE id=? AND user_id=? AND deleted_at IS NULL", addressId, userId);
        if (updated == 0) {
            int exists = jdbc.queryForObject("SELECT COUNT(*) FROM user_address WHERE id=? AND deleted_at IS NULL", Integer.class, addressId);
            if (exists > 0) throw new ApiException(403, "ACCESS_DENIED", "The address belongs to another user.");
            throw new ApiException(404, "RESOURCE_NOT_FOUND", "The address was not found.");
        }
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    private AddressWrite addressWrite(Map<String, Object> body) {
        String label = body.get("label") == null ? null : body.get("label").toString();
        String text = body.get("addressText") == null ? "" : body.get("addressText").toString();
        Object defaultValue = body.get("isDefault");
        if (label != null && label.length() > 50) throw invalid("label", "must be at most 50 characters");
        if (text.isBlank() || text.length() > 200) throw invalid("addressText", "must contain 1 to 200 characters");
        if (!(defaultValue instanceof Boolean)) throw invalid("isDefault", "must be a boolean");
        return new AddressWrite(label, text, (Boolean) defaultValue);
    }

    private Map<String, Object> address(long id, String label, String text, boolean isDefault) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id); map.put("label", label); map.put("addressText", text); map.put("isDefault", isDefault);
        return map;
    }

    private List<String> stringList(Object value, String field, List<String> allowed) {
        if (!(value instanceof List<?> list)) throw invalid(field, "must be an array");
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String string) || !allowed.contains(string)) throw invalid(field, "contains an unsupported value");
            if (result.contains(string)) throw invalid(field, "must not contain duplicates");
            result.add(string);
        }
        return result;
    }

    private String nullableString(Object value, String field, List<String> allowed) {
        if (value == null) return null;
        if (!(value instanceof String string) || !allowed.contains(string)) throw invalid(field, "contains an unsupported value");
        return string;
    }

    private BigDecimal nullableMoney(Object value, String field) {
        if (value == null) return null;
        try {
            BigDecimal amount = new BigDecimal(value.toString());
            if (amount.signum() < 0 || amount.scale() > 2) throw invalid(field, "must be a non-negative amount with two decimals");
            return amount.setScale(2);
        } catch (NumberFormatException ex) { throw invalid(field, "must be a money string"); }
    }

    private String money(BigDecimal value) { return value == null ? null : value.setScale(2).toPlainString(); }
    private ApiException invalid(String field, String message) {
        return new ApiException(400, "VALIDATION_FAILED", "The request contains invalid fields.", List.of(new ApiException.FieldError(field, message)));
    }
    private record AddressWrite(String label, String text, boolean isDefault) { }
}
