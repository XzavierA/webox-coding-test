package com.webox.identity.api;

import com.webox.identity.application.AuthService;
import com.webox.identity.application.AuthService.Session;
import com.webox.platform.security.AuthenticatedActor;
import com.webox.platform.security.CurrentActor;
import com.webox.shared.ApiException;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final CurrentActor currentActor;
    private final Environment environment;
    private final List<String> allowedOrigins;

    public AuthController(AuthService auth, CurrentActor currentActor, Environment environment,
                          @Value("${webox.allowed-origins[0]:http://localhost:5173}") String origin) {
        this.auth = auth;
        this.currentActor = currentActor;
        this.environment = environment;
        this.allowedOrigins = List.of(origin);
    }

    @PostMapping("/register")
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        return session(auth.register(request), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return session(auth.login(request), HttpStatus.OK);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@CookieValue(value = "webox_refresh", required = false) String cookie,
                                                  @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin) {
        requireAllowedOrigin(origin);
        return session(auth.refresh(cookie), HttpStatus.OK);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(value = "webox_refresh", required = false) String cookie,
                                       @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin) {
        requireAllowedOrigin(origin);
        auth.logout(cookie);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearedCookie().toString()).build();
    }

    @GetMapping("/me")
    public UserResponse currentUser() {
        AuthenticatedActor actor = currentActor.require();
        return auth.currentUser(actor.userId());
    }

    private ResponseEntity<TokenResponse> session(Session session, HttpStatus status) {
        return ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(session.response());
    }

    private void requireAllowedOrigin(String origin) {
        if (origin == null || !allowedOrigins.contains(origin)) {
            throw new ApiException(403, "ACCESS_DENIED", "This request origin is not allowed.");
        }
    }

    private ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from("webox_refresh", value).httpOnly(true).sameSite("Strict")
                .path("/api/v1/auth").maxAge(Duration.ofSeconds(1_209_600)).secure(secureCookies()).build();
    }

    private ResponseCookie clearedCookie() {
        return ResponseCookie.from("webox_refresh", "").httpOnly(true).sameSite("Strict")
                .path("/api/v1/auth").maxAge(Duration.ZERO).secure(secureCookies()).build();
    }

    private boolean secureCookies() { return !environment.acceptsProfiles(Profiles.of("local", "test")); }
}
