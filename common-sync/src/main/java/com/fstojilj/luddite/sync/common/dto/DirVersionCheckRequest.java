package com.fstojilj.luddite.sync.common.dto;

import java.util.List;

public record DirVersionCheckRequest(List<DirVersionEntry> dirs) {
}
