package com.fstojilj.luddite.sync.common.dto;

import java.util.List;

/**
 * @param childNames immediate child subdirectory names
 * @param fileNames  immediate child file names (no further path segments)
 */
public record TreeResponse(List<String> childNames, List<String> fileNames) {}
