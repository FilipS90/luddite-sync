package com.fstojilj.luddite.sync.server.service;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of private-directory authorizations, keyed by {@code clientId}.
 *
 * <p>Populated by {@code DirsController} when a client successfully authenticates
 * against a private directory via {@code POST /api/dirs/{name}/auth}. Read by
 * {@code FileSocketService} to gate access to private dirs on every SYNC and FILE
 * request. Evicted when the client's socket disconnects.
 */
@Service
public class AuthCacheService {

    private final ConcurrentHashMap<String, Set<String>> cache = new ConcurrentHashMap<>();

    /**
     * Records that {@code clientId} has been granted access to {@code dirName}.
     *
     * @param clientId the client's stable identifier
     * @param dirName  the private directory name that was authorized
     */
    public void grantAccess(String clientId, String dirName) {
        cache.computeIfAbsent(clientId, k -> ConcurrentHashMap.newKeySet()).add(dirName);
    }

    /**
     * Returns {@code true} if {@code clientId} has previously been granted access
     * to {@code dirName} via HTTP auth.
     *
     * @param clientId the client's stable identifier
     * @param dirName  the directory name to check
     * @return {@code true} if access was granted, {@code false} otherwise
     */
    public boolean isAuthorized(String clientId, String dirName) {
        Set<String> authorized = cache.get(clientId);
        return authorized != null && authorized.contains(dirName);
    }

    /**
     * Removes all cached authorizations for {@code clientId}.
     * Should be called when the client's socket disconnects.
     *
     * @param clientId the client whose entries should be evicted
     */
    public void evict(String clientId) {
        cache.remove(clientId);
    }
}
