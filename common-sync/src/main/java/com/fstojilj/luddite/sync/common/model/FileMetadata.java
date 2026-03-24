package com.fstojilj.luddite.sync.common.model;

import lombok.Builder;

import java.time.Instant;

@Builder(toBuilder = true)
public record FileMetadata(
        Long id,
        String filename,
        Long rootDirId,
        String relativePath,
        Long fileSize,
        String checksum,
        Instant createdAt,
        Instant modifiedAt,
        Long syncVersion,
        boolean deleted,
        String clientIds
) {
}
