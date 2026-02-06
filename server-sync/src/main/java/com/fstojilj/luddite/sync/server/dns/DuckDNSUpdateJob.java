package com.fstojilj.luddite.sync.server.dns;

import jakarta.annotation.PostConstruct;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
@NoArgsConstructor
@Slf4j
public class DuckDNSUpdateJob {

    private static final String DUCK_DNS_DOMAIN = "luddite-sync";
    private static final String DUCK_DNS_TOKEN = "83b58635-8e13-4337-b5ab-027f58eae593";

    @PostConstruct
    public void init() {
        updateDuckDNS(); // Run on startup
    }

    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void updateDuckDNS() {
        String url = String.format(
                "https://www.duckdns.org/update?domains=%s&token=%s",
                DUCK_DNS_DOMAIN,
                DUCK_DNS_TOKEN
        );

        HttpResponse<String> response;

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .build();

            response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }


        if (response.body().equals("OK")) {
            log.info("DuckDNS updated successfully");
        } else {
            log.error("DuckDNS update failed: {}", response.body());
        }
    }
}
