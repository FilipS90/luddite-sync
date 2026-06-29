package com.fstojilj.luddite.sync.common.dto;

import java.util.Map;

/**
 * Maps dirName → current server sync version.
 */
public record DirVersionCheckResponse(Map<String, Long> versions) {
}
