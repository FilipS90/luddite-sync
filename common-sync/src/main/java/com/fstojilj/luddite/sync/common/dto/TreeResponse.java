package com.fstojilj.luddite.sync.common.dto;

import java.util.List;

/**
 * @param childDirs immediate child subdirectories
 * @param files     immediate child files (no further path segments)
 */
public record TreeResponse(List<TreeEntry> childDirs, List<TreeEntry> files) {}
