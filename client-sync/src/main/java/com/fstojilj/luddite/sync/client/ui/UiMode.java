package com.fstojilj.luddite.sync.client.ui;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Front-end the client boots with; the value matches the {@code sync.client.ui} property.
 */
@Getter
@RequiredArgsConstructor
public enum UiMode {
    CLI("cli"),
    SWING("swing");

    private final String value;

    public static UiMode fromValue(String value) {
        for (UiMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown sync.client.ui value: " + value);
    }
}
