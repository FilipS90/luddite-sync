package com.fstojilj.luddite.sync.client.ui;

import com.fstojilj.luddite.sync.client.ui.UiModeResolver.PromptResult;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class UiModeResolverTest {

    @TempDir
    Path tempDir;

    private Path choiceFile;
    private AtomicInteger promptCalls;

    @BeforeEach
    void setUp() {
        choiceFile = tempDir.resolve(UiModeResolver.CHOICE_FILE_NAME);
        promptCalls = new AtomicInteger();
    }

    private Supplier<PromptResult> prompting(UiMode mode, boolean remember) {
        return () -> {
            promptCalls.incrementAndGet();
            return new PromptResult(mode, remember);
        };
    }

    private UiModeResolver resolver(boolean headless, Supplier<PromptResult> prompt) {
        return new UiModeResolver(choiceFile, headless, prompt, LogFactory.getLog(UiModeResolver.class));
    }

    private void storeChoice(String ui, String remaining) throws IOException {
        Files.writeString(choiceFile, "ui=" + ui + "\nremaining=" + remaining + "\n");
    }

    private Properties stored() throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(choiceFile)) {
            props.load(in);
        }
        return props;
    }

    @Test
    void explicitValue_winsWithoutPromptOrFile() {
        UiModeResolver resolver = resolver(false, prompting(UiMode.CLI, true));

        assertThat(resolver.resolve("swing")).isEqualTo(UiMode.SWING);
        assertThat(resolver.resolve("CLI")).isEqualTo(UiMode.CLI);
        assertThat(promptCalls).hasValue(0);
        assertThat(choiceFile).doesNotExist();
    }

    @Test
    void explicitSwing_onHeadless_fallsBackToCli() {
        UiModeResolver resolver = resolver(true, prompting(UiMode.SWING, true));

        assertThat(resolver.resolve("swing")).isEqualTo(UiMode.CLI);
        assertThat(resolver.resolve("cli")).isEqualTo(UiMode.CLI);
        assertThat(promptCalls).hasValue(0);
        assertThat(choiceFile).doesNotExist();
    }

    @Test
    void headless_startsCliAndLeavesStoredChoiceUntouched() throws IOException {
        storeChoice("swing", "3");

        UiMode mode = resolver(true, prompting(UiMode.SWING, true)).resolve(null);

        assertThat(mode).isEqualTo(UiMode.CLI);
        assertThat(promptCalls).hasValue(0);
        assertThat(stored().getProperty("remaining")).isEqualTo("3");
    }

    @Test
    void prompt_notRemembered_writesNothing() {
        UiMode mode = resolver(false, prompting(UiMode.SWING, false)).resolve(null);

        assertThat(mode).isEqualTo(UiMode.SWING);
        assertThat(promptCalls).hasValue(1);
        assertThat(choiceFile).doesNotExist();
    }

    @Test
    void prompt_remembered_storesChoiceForFiveLaunches() throws IOException {
        UiMode mode = resolver(false, prompting(UiMode.CLI, true)).resolve(null);

        assertThat(mode).isEqualTo(UiMode.CLI);
        assertThat(stored().getProperty("ui")).isEqualTo("cli");
        assertThat(stored().getProperty("remaining"))
                .isEqualTo(String.valueOf(UiModeResolver.REMEMBER_LAUNCHES));
    }

    @Test
    void storedChoice_usedAndDecremented() throws IOException {
        storeChoice("swing", "5");

        UiMode mode = resolver(false, prompting(UiMode.CLI, false)).resolve(null);

        assertThat(mode).isEqualTo(UiMode.SWING);
        assertThat(promptCalls).hasValue(0);
        assertThat(stored().getProperty("ui")).isEqualTo("swing");
        assertThat(stored().getProperty("remaining")).isEqualTo("4");
    }

    @Test
    void lastRememberedLaunch_deletesFileThenPromptsAgain() throws IOException {
        storeChoice("swing", "1");
        UiModeResolver resolver = resolver(false, prompting(UiMode.CLI, false));

        assertThat(resolver.resolve(null)).isEqualTo(UiMode.SWING);
        assertThat(choiceFile).doesNotExist();
        assertThat(promptCalls).hasValue(0);

        assertThat(resolver.resolve(null)).isEqualTo(UiMode.CLI);
        assertThat(promptCalls).hasValue(1);
    }

    @Test
    void unreadableFile_isDiscardedAndUserIsPrompted() throws IOException {
        Files.writeString(choiceFile, "ui=teletype\nremaining=lots\n");

        UiMode mode = resolver(false, prompting(UiMode.CLI, false)).resolve(null);

        assertThat(mode).isEqualTo(UiMode.CLI);
        assertThat(promptCalls).hasValue(1);
        assertThat(choiceFile).doesNotExist();
    }

    @Test
    void unknownExplicitValue_isIgnoredAndUserIsPrompted() {
        UiMode mode = resolver(false, prompting(UiMode.SWING, false)).resolve("desktop");

        assertThat(mode).isEqualTo(UiMode.SWING);
        assertThat(promptCalls).hasValue(1);
    }

    @Test
    void promptFailure_fallsBackToCli() {
        UiModeResolver resolver = resolver(false, () -> {
            throw new IllegalStateException("no dialog");
        });

        assertThat(resolver.resolve(null)).isEqualTo(UiMode.CLI);
        assertThat(choiceFile).doesNotExist();
    }
}
