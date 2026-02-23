package com.fstojilj.luddite.sync.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SyncServerApp {

    public static void main(String[] args) {
        SpringApplication.run(SyncServerApp.class, args);
    }
}

