package com.fstojilj.luddite.sync.common.dto;

/**
 * Request body for the {@code POST /api/dirs/{name}/auth} endpoint.
 *
 * @param clientId     the stable client identifier
 * @param passwordHash the SHA-256 hex password hash for the private directory
 */
public record AuthRequest(String clientId, String passwordHash) {}
