package com.webox.identity.api;

public record UserResponse(long id, String email, String displayName, String role) { }
