package com.fstojilj.luddite.sync.client.config;

import com.fstojilj.luddite.sync.client.ui.UiMode;
import com.fstojilj.luddite.sync.client.ui.UiModeDialog;
import com.fstojilj.luddite.sync.client.ui.UiModeResolver;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.Map;

/**
 * Settles {@code sync.client.ui} before bean conditions are evaluated,
 * so {@code ClientCli} / {@code ClientUI} see a definite value. Runs before the logging
 * system is ready, hence the deferred log.
 */
public class UiModeEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "luddite-ui-mode";
    static final String UI_PROPERTY = "sync.client.ui";

    private final Log log;

    public UiModeEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(UiModeResolver.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String explicit = env.getProperty(UI_PROPERTY);
        String mirrorDir = env.getProperty("sync.client.mirror-dir",
                System.getProperty("user.home") + "/.luddite");
        UiMode mode = new UiModeResolver(
                Path.of(mirrorDir, UiModeResolver.CHOICE_FILE_NAME),
                GraphicsEnvironment.isHeadless(),
                UiModeDialog::prompt,
                log).resolve(explicit);
        env.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(UI_PROPERTY, mode.getValue())));
    }
}
