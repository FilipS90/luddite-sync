package com.fstojilj.luddite.sync.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
public class DuckDNSExecutor {

    /**
     * Virtual thread executor for DuckDNS update job.
     * Virtual threads are perfect for I/O-bound tasks like HTTP calls.
     * They are lightweight and managed by the JVM, creating them on-demand.
     */
    @Bean
    public Executor duckDnsExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

