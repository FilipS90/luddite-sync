package com.fstojilj.luddite.sync.client.event;

import java.util.List;

public record ServerDirsAvailableEvent(List<String> availableDirs) {
}
