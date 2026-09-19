package com.fstojilj.luddite.sync.common.util;

import java.util.Locale;

public final class FileSizeFormat {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private FileSizeFormat() {}

    public static String humanReadable(long bytes) {
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }
}
