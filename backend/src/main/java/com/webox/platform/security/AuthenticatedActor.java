package com.webox.platform.security;

public record AuthenticatedActor(long userId, String role) { }
