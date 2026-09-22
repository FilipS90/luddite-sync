package com.fstojilj.luddite.sync.server.controller;

import com.fstojilj.luddite.sync.common.dto.AuthRequest;
import com.fstojilj.luddite.sync.common.dto.DirListResponse;
import com.fstojilj.luddite.sync.common.dto.TreeEntry;
import com.fstojilj.luddite.sync.common.dto.TreeResponse;
import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.service.AuthCacheService;
import com.fstojilj.luddite.sync.server.service.FileMetadataService;
import com.fstojilj.luddite.sync.server.service.RootDirService;
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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * Lists all public (non-private) root directories with their total live file size.
     * Private directories are never exposed here; clients must use
     * {@code POST /api/dirs/{name}/auth} to access them directly by name.
     */
    @GetMapping
    public DirListResponse listDirs() {
        Map<Integer, Long> sizes = fileMetadataService.sumFileSizeByRootDir();
        List<TreeEntry> publicDirs = rootDirService.findAll().stream()
                .filter(rd -> !rd.isPrivate())
                .sorted(Comparator.comparing(RootDir::getName))
                .map(rd -> new TreeEntry(rd.getName(), sizes.getOrDefault(rd.getId(), 0L)))
                .toList();
        return new DirListResponse(publicDirs);
    }

    /**
     * Returns the immediate child directories and files under {@code under} (defaults to root level).
     * Requires {@code X-Auth-Hash} header for private directories. A private directory
     * without a valid hash answers 404, identically to a directory that does not exist,
     * so the endpoint cannot be used to discover private directory names.
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
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(fileMetadataService.findImmediateChildren(dir.getId(), under));
    }

    /**
     * Validates the password for a private directory.
     * Returns 400 for a public directory, 200 on the correct password, and 403 otherwise.
     * An unknown name answers 403 too, identically to a wrong password, so the endpoint
     * cannot be used to discover private directory names.
     *
     * @param name    root directory name
     * @param request body containing the SHA-256 hex password hash
     */
    @PostMapping("/{name}/auth")
    public ResponseEntity<Void> authenticate(
            @PathVariable String name,
            @RequestBody AuthRequest request) {

        Optional<RootDir> dirOpt = rootDirService.findByName(name);
        if (dirOpt.isEmpty()) {
            log.warn("POST /api/dirs/{}/auth — DENIED (unknown dir)", name);
            return ResponseEntity.status(403).build();
        }
        if (!dirOpt.get().isPrivate()) {
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isAuthorized(RootDir dir, String providedHash) {
        return providedHash != null && providedHash.equals(dir.getPassword());
    }
}
