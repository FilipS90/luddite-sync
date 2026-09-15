package com.fstojilj.luddite.sync.client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.awt.GraphicsEnvironment;

@SpringBootApplication
public class SyncClientApp {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SyncClientApp.class);
        app.setHeadless(GraphicsEnvironment.isHeadless());
        app.run(args);
    }
}
