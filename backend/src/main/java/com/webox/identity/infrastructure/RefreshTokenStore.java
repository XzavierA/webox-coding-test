package com.webox.identity.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenStore {
    private static final String PREFIX = "webox:refresh:";
    private static final String FAMILY_PREFIX = "webox:refresh-family:";
    private static final DefaultRedisScript<List> ROTATE_SCRIPT = new DefaultRedisScript<>("""
            local state = redis.call('HGET', KEYS[1], 'state')
            if not state then return {'INVALID'} end
            local family = redis.call('HGET', KEYS[1], 'family')
            local familyKey = 'webox:refresh-family:' .. family
            if state ~= 'ACTIVE' then
              local members = redis.call('SMEMBERS', familyKey)
              for _, tokenHash in ipairs(members) do
                redis.call('HSET', 'webox:refresh:' .. tokenHash, 'state', 'REVOKED')
              end
              return {'REUSED'}
            end
            local userId = redis.call('HGET', KEYS[1], 'userId')
            redis.call('HSET', KEYS[1], 'state', 'ROTATED', 'nextHash', ARGV[1])
            redis.call('HSET', KEYS[2], 'userId', userId, 'family', family, 'state', 'ACTIVE')
            redis.call('SADD', familyKey, ARGV[1])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]))
            redis.call('EXPIRE', familyKey, tonumber(ARGV[2]))
            return {'ROTATED', userId, family}
            """, List.class);
    private static final DefaultRedisScript<List> REVOKE_SCRIPT = new DefaultRedisScript<>("""
            local state = redis.call('HGET', KEYS[1], 'state')
            if not state then return {'MISSING'} end
            local family = redis.call('HGET', KEYS[1], 'family')
            local members = redis.call('SMEMBERS', 'webox:refresh-family:' .. family)
            for _, tokenHash in ipairs(members) do
              redis.call('HSET', 'webox:refresh:' .. tokenHash, 'state', 'REVOKED')
            end
            return {'REVOKED'}
            """, List.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenStore(StringRedisTemplate redis,
                             @Value("${webox.security.refresh-ttl-seconds:1209600}") long ttlSeconds) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public String create(long userId) {
        String token = randomToken();
        String hash = hash(token);
        String family = java.util.UUID.randomUUID().toString();
        String tokenKey = PREFIX + hash;
        String familyKey = FAMILY_PREFIX + family;
        redis.opsForHash().putAll(tokenKey, java.util.Map.of("userId", Long.toString(userId), "family", family, "state", "ACTIVE"));
        redis.opsForSet().add(familyKey, hash);
        redis.expire(tokenKey, ttl);
        redis.expire(familyKey, ttl);
        return token;
    }

    public Rotation rotate(String oldToken) {
        String nextToken = randomToken();
        List<?> result = redis.execute(ROTATE_SCRIPT,
                List.of(PREFIX + hash(oldToken), PREFIX + hash(nextToken)), hash(nextToken), Long.toString(ttl.toSeconds()));
        String state = result == null || result.isEmpty() ? "INVALID" : result.get(0).toString();
        if (!"ROTATED".equals(state)) {
            throw new com.webox.shared.ApiException(401, "AUTHENTICATION_REQUIRED", "A valid refresh token is required.");
        }
        return new Rotation(Long.parseLong(result.get(1).toString()), result.get(2).toString(), nextToken);
    }

    public void revoke(String token) {
        if (token == null || token.isBlank()) return;
        redis.execute(REVOKE_SCRIPT, List.of(PREFIX + hash(token)));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record Rotation(long userId, String familyId, String token) { }
}
