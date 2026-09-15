package com.fstojilj.luddite.sync.client.ui;

import lombok.RequiredArgsConstructor;
import org.apache.commons.logging.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Decides which front-end to start.
 *
 * <p>An explicit {@code sync.client.ui} value wins, except that a headless system always
 * gets the CLI since the desktop UI cannot open there.
 * Otherwise the user is prompted, and the answer can be remembered for
 * {@link #REMEMBER_LAUNCHES} launches in {@code ui-choice.properties} next to the client-id file.
 */
@RequiredArgsConstructor
public class UiModeResolver {

    public static final int REMEMBER_LAUNCHES = 5;
    public static final String CHOICE_FILE_NAME = "ui-choice.properties";

    private static final String KEY_UI = "ui";
    private static final String KEY_REMAINING = "remaining";

    private final Path choiceFile;
    private final boolean headless;
    private final Supplier<PromptResult> prompt;
    private final Log log;

    public record PromptResult(UiMode mode, boolean remember) {
    }

    public UiMode resolve(String explicitValue) {
        UiMode explicit = parseExplicit(explicitValue);
        if (headless) {
            if (explicit == UiMode.SWING) {
                log.warn("sync.client.ui=swing requested but no display is available, starting CLI");
            } else {
                log.info("Headless environment detected, starting CLI");
            }
            return UiMode.CLI;
        }
        if (explicit != null) {
            return explicit;
        }
        UiMode stored = consumeStoredChoice();
        if (stored != null) {
            return stored;
        }
        PromptResult result;
        try {
            result = prompt.get();
        } catch (RuntimeException e) {
            log.warn("UI mode prompt failed, starting CLI: " + e.getMessage());
            return UiMode.CLI;
        }
        if (result.remember()) {
            write(result.mode(), REMEMBER_LAUNCHES);
        }
        return result.mode();
    }

    private UiMode parseExplicit(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UiMode.fromValue(value.trim());
        } catch (IllegalArgumentException e) {
            log.warn(e.getMessage() + ", ignoring it");
            return null;
        }
    }

    private UiMode consumeStoredChoice() {
        if (!Files.exists(choiceFile)) {
            return null;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(choiceFile)) {
            props.load(in);
            UiMode mode = UiMode.fromValue(props.getProperty(KEY_UI, ""));
            int remaining = Integer.parseInt(props.getProperty(KEY_REMAINING, "0"));
            if (remaining <= 0) {
                Files.deleteIfExists(choiceFile);
                return null;
            }
            if (remaining == 1) {
                Files.deleteIfExists(choiceFile);
            } else {
                write(mode, remaining - 1);
            }
            return mode;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Discarding unreadable " + choiceFile + ": " + e.getMessage());
            discard();
            return null;
        }
    }

    private void discard() {
        try {
            Files.deleteIfExists(choiceFile);
        } catch (IOException e) {
            log.warn("Could not delete " + choiceFile + ": " + e.getMessage());
        }
    }

    private void write(UiMode mode, int remaining) {
        Properties props = new Properties();
        props.setProperty(KEY_UI, mode.getValue());
        props.setProperty(KEY_REMAINING, String.valueOf(remaining));
        try {
            Files.createDirectories(choiceFile.getParent());
            try (OutputStream out = Files.newOutputStream(choiceFile)) {
                props.store(out, null);
            }
        } catch (IOException e) {
            log.warn("Could not save UI choice to " + choiceFile + ": " + e.getMessage());
        }
    }
}
