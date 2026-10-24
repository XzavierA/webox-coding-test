package com.webox.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webox.shared.Problem;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfiguration {
    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService, ObjectMapper objectMapper) {
        return new JwtAuthenticationFilter(jwtService, objectMapper);
    }

    @Bean
    RedisRateLimitFilter redisRateLimitFilter(StringRedisTemplate redis, ObjectMapper mapper, Environment environment) {
        return new RedisRateLimitFilter(redis, mapper, environment);
    }

    @Bean
    FilterRegistrationBean<RedisRateLimitFilter> disableServletRateLimitFilterRegistration(RedisRateLimitFilter filter) {
        FilterRegistrationBean<RedisRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter, RedisRateLimitFilter rateLimitFilter,
                                            ObjectMapper mapper) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) -> {
                            String id = request.getAttribute(com.webox.shared.CorrelationIdFilter.ATTRIBUTE) instanceof String value ? value : UUID.randomUUID().toString();
                            response.setStatus(401);
                            response.setContentType("application/problem+json");
                            mapper.writeValue(response.getOutputStream(), new Problem("urn:webox:problem:authentication_required", "Unauthorized", 401,
                                    "A valid access token is required.", "AUTHENTICATION_REQUIRED", id, null, null));
                        })
                        .accessDeniedHandler((request, response, ex) -> {
                            String id = request.getAttribute(com.webox.shared.CorrelationIdFilter.ATTRIBUTE) instanceof String value ? value : UUID.randomUUID().toString();
                            response.setStatus(403);
                            response.setContentType("application/problem+json");
                            mapper.writeValue(response.getOutputStream(), new Problem("urn:webox:problem:access_denied", "Forbidden", 403,
                                    "The authenticated actor is not permitted to access this resource.", "ACCESS_DENIED", id, null, null));
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/media/**").permitAll()
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/app/**").hasAnyRole("EMPLOYEE", "ADMIN")
                        .requestMatchers("/api/v1/auth/me").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(Environment environment) {
        String configuredOrigins = environment.getProperty("webox.allowed-origins", "http://localhost:5173");
        configuredOrigins = configuredOrigins.replace("[", "").replace("]", "");
        List<String> origins = Arrays.stream(configuredOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        CorsConfiguration normal = new CorsConfiguration();
        normal.setAllowedOrigins(origins);
        normal.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        normal.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        normal.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After"));
        normal.setAllowCredentials(false);
        CorsConfiguration cookie = new CorsConfiguration();
        cookie.setAllowedOrigins(origins);
        cookie.setAllowedMethods(List.of("POST", "OPTIONS"));
        cookie.setAllowedHeaders(List.of("Content-Type", "X-Correlation-Id"));
        cookie.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", normal);
        source.registerCorsConfiguration("/api/v1/auth/refresh", cookie);
        source.registerCorsConfiguration("/api/v1/auth/logout", cookie);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    UserDetailsService noSpringLoginUsers() {
        return username -> { throw new UsernameNotFoundException("No form-login user exists"); };
    }
}
