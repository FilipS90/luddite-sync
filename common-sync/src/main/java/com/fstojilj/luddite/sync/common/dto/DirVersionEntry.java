package com.fstojilj.luddite.sync.common.dto;

/**
 * One entry in a version-check request.
 * {@code passwordHash} is null for public directories.
 */
public record DirVersionEntry(String dirName, String passwordHash) {}
