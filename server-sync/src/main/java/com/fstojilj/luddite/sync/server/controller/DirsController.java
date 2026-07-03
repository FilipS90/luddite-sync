package com.fstojilj.luddite.sync.server.controller;

import com.fstojilj.luddite.sync.common.dto.AuthRequest;
import com.fstojilj.luddite.sync.common.dto.DirListResponse;
import com.fstojilj.luddite.sync.common.dto.DirVersionCheckRequest;
import com.fstojilj.luddite.sync.common.dto.DirVersionCheckResponse;
import com.fstojilj.luddite.sync.common.dto.DirVersionEntry;
import com.fstojilj.luddite.sync.common.dto.TreeResponse;
import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.service.AuthCacheService;
import com.fstojilj.luddite.sync.server.service.FileMetadataService;
import com.fstojilj.luddite.sync.server.service.RootDirService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for directory operations.
 *
 * <p>Handles HTTP negotiation (directory listing, version check, private-dir auth).
 * File byte transfer and delete ACKs are handled by the binary TCP socket in
 * {@code FileSocketService}. When a client authenticates a private directory here,
 * {@code AuthCacheService} is populated so the socket service can gate access accordingly.
 */
@RestController
@RequestMapping("/api/dirs")
@RequiredArgsConstructor
@Slf4j
public class DirsController {

    private final RootDirService rootDirService;
    private final FileMetadataService fileMetadataService;
    private final AuthCacheService authCacheService;

    /**
     * Lists all public (non-private) root directory names.
     * Private directories are never exposed here; clients must use
     * {@code POST /api/dirs/{name}/auth} to access them directly by name.
     */
    @GetMapping
    public DirListResponse listDirs() {
        List<String> publicDirs = rootDirService.findAll().stream()
                .filter(rd -> !rd.isPrivate())
                .map(RootDir::getName)
                .sorted()
                .toList();
        return new DirListResponse(publicDirs);
    }

    /**
     * Returns the immediate child directory names under {@code under} (defaults to root level).
     * Requires {@code X-Auth-Hash} header for private directories.
     *
     * @param name     root directory name
     * @param under    relative path to look under; omit or pass {@code ""} for root level
     * @param authHash optional password hash header for private dirs
     */
    @GetMapping("/{name}/tree")
    public ResponseEntity<TreeResponse> getTree(
            @PathVariable String name,
            @RequestParam(required = false, defaultValue = "") String under,
            @RequestHeader(value = "X-Auth-Hash", required = false) String authHash) {

        Optional<RootDir> dirOpt = rootDirService.findByName(name);
        if (dirOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RootDir dir = dirOpt.get();
        if (dir.isPrivate() && !isAuthorized(dir, authHash)) {
            log.warn("GET /api/dirs/{}/tree — unauthorized (missing or wrong X-Auth-Hash)", name);
            return ResponseEntity.status(403).build();
        }

        List<String> childDirs  = fileMetadataService.findImmediateChildDirNames(dir.getId(), under);
        List<String> childFiles = fileMetadataService.findImmediateChildFileNames(dir.getId(), under);
        return ResponseEntity.ok(new TreeResponse(childDirs, childFiles));
    }

    /**
     * Validates the password for a private directory.
     * Returns 400 if the directory is not private or does not exist.
     * Returns 200 with {@code granted=false} (not 403) on wrong password so the client
     * can distinguish "wrong password" from "server error".
     *
     * @param name    root directory name
     * @param request body containing the SHA-256 hex password hash
     */
    @PostMapping("/{name}/auth")
    public ResponseEntity<Void> authenticate(
            @PathVariable String name,
            @RequestBody AuthRequest request) {

        Optional<RootDir> dirOpt = rootDirService.findByName(name);
        if (dirOpt.isEmpty() || !dirOpt.get().isPrivate()) {
            return ResponseEntity.badRequest().build();
        }

        RootDir dir = dirOpt.get();
        boolean granted = dir.getPassword() != null && dir.getPassword().equals(request.passwordHash());
        if (granted) {
            log.info("POST /api/dirs/{}/auth — GRANTED", name);
            authCacheService.grantAccess(request.clientId(), name);
            return ResponseEntity.ok().build();
        } else {
            log.warn("POST /api/dirs/{}/auth — DENIED (wrong password)", name);
            return ResponseEntity.status(403).build();
        }
    }

    /**
     * Version check — client sends the list of dir names it is subscribed to (plus optional
     * password hash for private dirs); server returns the current {@code MAX(sync_version)}
     * per dir. The client compares against its own stored version and decides whether to
     * open a socket SYNC request.
     *
     * <p>Dirs with an invalid or missing password hash are silently omitted from the response.
     *
     * @param request body listing subscribed dirs
     */
    @PostMapping("/versions")
    public DirVersionCheckResponse checkVersions(@RequestBody DirVersionCheckRequest request) {
        Map<String, Long> versions = new HashMap<>();

        for (DirVersionEntry entry : request.dirs()) {
            Optional<RootDir> dirOpt = rootDirService.findByName(entry.dirName());
            if (dirOpt.isEmpty()) {
                log.debug("checkVersions: unknown dir '{}', skipping", entry.dirName());
                continue;
            }

            RootDir dir = dirOpt.get();
            if (dir.isPrivate() && !isAuthorized(dir, entry.passwordHash())) {
                log.warn("checkVersions: unauthorized access attempt for private dir '{}', skipping", entry.dirName());
                continue;
            }

            Long version = fileMetadataService.getMaxSyncVersionForDir(dir.getId());
            versions.put(entry.dirName(), version != null ? version : 0L);
        }

        return new DirVersionCheckResponse(versions);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isAuthorized(RootDir dir, String providedHash) {
        return providedHash != null && providedHash.equals(dir.getPassword());
    }
}
