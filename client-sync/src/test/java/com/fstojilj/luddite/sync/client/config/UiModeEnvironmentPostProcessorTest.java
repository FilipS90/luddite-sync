package com.fstojilj.luddite.sync.client.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UiModeEnvironmentPostProcessorTest {

    @Test
    void explicitProperty_isKeptAndPublishedFirst() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                UiModeEnvironmentPostProcessor.UI_PROPERTY, "cli")));

        new UiModeEnvironmentPostProcessor(new DeferredLogs()).postProcessEnvironment(env, null);

        assertThat(env.getProperty(UiModeEnvironmentPostProcessor.UI_PROPERTY)).isEqualTo("cli");
        assertThat(env.getPropertySources().iterator().next().getName())
                .isEqualTo(UiModeEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
    }
}
