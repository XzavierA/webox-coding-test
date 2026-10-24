package com.webox.identity.api;

public record TokenResponse(String accessToken, String tokenType, int expiresIn, UserResponse user) { }
