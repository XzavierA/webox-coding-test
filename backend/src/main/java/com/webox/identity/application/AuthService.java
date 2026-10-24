package com.webox.identity.application;

import com.webox.identity.api.LoginRequest;
import com.webox.identity.api.RegisterRequest;
import com.webox.identity.api.TokenResponse;
import com.webox.identity.api.UserResponse;
import com.webox.identity.infrastructure.RefreshTokenStore;
import com.webox.platform.security.JwtService;
import com.webox.shared.ApiException;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private static final String LOGIN_FAILURE = "Email or password is incorrect.";
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokens;
    private final String dummyHash;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwtService,
                       RefreshTokenStore refreshTokens) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public Session register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (existsByEmail(email)) throw new ApiException(409, "EMAIL_ALREADY_REGISTERED", "An account with this email already exists.");
        try {
            jdbc.update("INSERT INTO app_user (email, password_hash, display_name, role, enabled) VALUES (?, ?, ?, 'EMPLOYEE', TRUE)",
                    email, passwordEncoder.encode(request.password()), request.displayName().trim());
        } catch (DuplicateKeyException ex) {
            throw new ApiException(409, "EMAIL_ALREADY_REGISTERED", "An account with this email already exists.");
        }
        UserResponse user = findByEmail(email);
        return issue(user);
    }

    public Session login(LoginRequest request) {
        Account account = jdbc.query("SELECT id, email, password_hash, display_name, role, enabled FROM app_user WHERE email = ?",
                rs -> rs.next() ? new Account(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                        rs.getString("display_name"), rs.getString("role"), rs.getBoolean("enabled")) : null,
                normalizeEmail(request.email()));
        String hash = account == null ? dummyHash : account.passwordHash();
        boolean verified = passwordEncoder.matches(request.password(), hash);
        if (account == null || !verified || !account.enabled()) throw new ApiException(401, "AUTHENTICATION_REQUIRED", LOGIN_FAILURE);
        UserResponse user = new UserResponse(account.id(), account.email(), account.displayName(), account.role());
        return issue(user);
    }

    public Session refresh(String oldToken) {
        if (oldToken == null || oldToken.isBlank()) throw new ApiException(401, "AUTHENTICATION_REQUIRED", "A valid refresh token is required.");
        RefreshTokenStore.Rotation rotation = refreshTokens.rotate(oldToken);
        UserResponse user = findById(rotation.userId());
        return new Session(new TokenResponse(jwtService.issue(user.id(), user.role()), "Bearer", 900, user), rotation.token());
    }

    public void logout(String token) {
        refreshTokens.revoke(token);
    }

    public UserResponse currentUser(long id) {
        UserResponse response = findById(id);
        Integer enabled = jdbc.queryForObject("SELECT enabled FROM app_user WHERE id = ?", Integer.class, id);
        if (enabled == null || enabled == 0) throw new ApiException(401, "AUTHENTICATION_REQUIRED", "The user account is not active.");
        return response;
    }

    private Session issue(UserResponse user) {
        return new Session(new TokenResponse(jwtService.issue(user.id(), user.role()), "Bearer", 900, user), refreshTokens.create(user.id()));
    }

    private UserResponse findByEmail(String email) {
        return jdbc.queryForObject("SELECT id, email, display_name, role FROM app_user WHERE email = ?",
                (rs, row) -> new UserResponse(rs.getLong("id"), rs.getString("email"), rs.getString("display_name"), rs.getString("role")), email);
    }

    private UserResponse findById(long id) {
        try {
            return jdbc.queryForObject("SELECT id, email, display_name, role FROM app_user WHERE id = ?",
                    (rs, row) -> new UserResponse(rs.getLong("id"), rs.getString("email"), rs.getString("display_name"), rs.getString("role")), id);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw new ApiException(401, "AUTHENTICATION_REQUIRED", "The user account is not active.");
        }
    }

    private boolean existsByEmail(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_user WHERE email = ?)", Boolean.class, email));
    }

    private String normalizeEmail(String email) { return email.trim().toLowerCase(java.util.Locale.ROOT); }

    private record Account(long id, String email, String passwordHash, String displayName, String role, boolean enabled) { }
    public record Session(TokenResponse response, String refreshToken) { }
}
