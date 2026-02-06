package com.fstojilj.luddite.sync.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class FileMetadata {
    private Long id;
    private String filename;
    private Long rootDirId;
    private String relativePath;
    private String checksum;
    private Long fileSize;
    private String mimeType;
    private Instant createdAt;
    private Instant modifiedAt;
    private Long syncVersion;
}
