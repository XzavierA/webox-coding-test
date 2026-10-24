package com.webox.platform.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {
    private final byte[] secret;
    private final String issuer;
    private final String audience;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(@Value("${webox.security.jwt-secret:}") String configuredSecret,
                      @Value("${webox.security.issuer:webox}") String issuer,
                      @Value("${webox.security.audience:webox-app}") String audience,
                      @Value("${webox.security.access-ttl-seconds:900}") long ttlSeconds) {
        this.secret = configuredSecret == null || configuredSecret.isBlank()
                ? ephemeralKey() : configuredSecret.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) throw new IllegalArgumentException("JWT signing key must be at least 32 bytes");
        this.issuer = issuer;
        this.audience = audience;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.clock = Clock.systemUTC();
    }

    public String issue(long userId, String role) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(Long.toString(userId)).claim("role", role).issuer(issuer).audience(audience)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(ttl))).jwtID(UUID.randomUUID().toString()).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Unable to sign access token", ex);
        }
    }

    public AuthenticatedActor verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(secret))) return null;
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expiration = claims.getExpirationTime();
            if (expiration == null || !expiration.toInstant().isAfter(clock.instant())) return null;
            if (!issuer.equals(claims.getIssuer()) || claims.getAudience() == null || !claims.getAudience().contains(audience)) return null;
            long userId = Long.parseLong(claims.getSubject());
            String role = claims.getStringClaim("role");
            if (userId < 1 || !("EMPLOYEE".equals(role) || "ADMIN".equals(role))) return null;
            return new AuthenticatedActor(userId, role);
        } catch (Exception ex) {
            return null;
        }
    }

    private byte[] ephemeralKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
