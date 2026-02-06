package com.fstojilj.luddite.sync.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class RootDir {
    private Long id;
    private String name;
    private String absolutePath;
    private Instant createdAt;
    private Instant modifiedAt;
}
