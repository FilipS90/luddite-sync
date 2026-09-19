package com.fstojilj.luddite.sync.common.dto;

/**
 * @param name immediate child name (no path separators)
 * @param size size in bytes; for a directory, the sum of all descendant files
 */
public record TreeEntry(String name, long size) {}
