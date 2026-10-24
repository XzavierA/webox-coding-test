package com.webox.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webox.shared.Problem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        if (!authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            unauthorized(request, response);
            return;
        }
        AuthenticatedActor actor = jwtService.verify(authorization.substring(7));
        if (actor == null) {
            unauthorized(request, response);
            return;
        }
        var authentication = new UsernamePasswordAuthenticationToken(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + actor.role())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    private void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String correlationId = (String) request.getAttribute(com.webox.shared.CorrelationIdFilter.ATTRIBUTE);
        if (correlationId == null) correlationId = UUID.randomUUID().toString();
        response.setStatus(401);
        response.setContentType("application/problem+json");
        objectMapper.writeValue(response.getOutputStream(), new Problem("urn:webox:problem:authentication_required",
                "Unauthorized", 401, "A valid access token is required.", "AUTHENTICATION_REQUIRED",
                correlationId, null, null));
    }
}
