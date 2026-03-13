package com.fstojilj.luddite.sync.server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ensures the parent directory of the SQLite database file exists before
 * the DataSource bean is created. SQLite cannot create missing parent dirs.
 */
@Component
@Slf4j
public class DatabaseDirectoryInitializer implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        String url = environment.getProperty("spring.datasource.url", "");
        // Strip "jdbc:sqlite:" prefix to get the file path
        if (url.startsWith("jdbc:sqlite:")) {
            String filePath = url.substring("jdbc:sqlite:".length());
            // Resolve any remaining property placeholders
            filePath = environment.resolvePlaceholders(filePath);
            Path dbPath = Path.of(filePath);
            Path parentDir = dbPath.getParent();
            if (parentDir != null) {
                try {
                    Files.createDirectories(parentDir);
                    log.info("Ensured database directory exists: {}", parentDir);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot create database directory: " + parentDir, e);
                }
            }
        }
    }
}

