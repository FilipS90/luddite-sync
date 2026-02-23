package com.fstojilj.luddite.sync.server.dns;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
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

    @Scheduled(fixedRate = 300000, initialDelay = 0) // Every 5 minutes, run immediately on startup
    @Async("duckDnsExecutor")
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
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to update DuckDNS: {}", e.getMessage(), e);
            return;
        }


        if (response.body().equals("OK")) {
            log.info("DuckDNS updated successfully");
        } else {
            log.error("DuckDNS update failed: {}", response.body());
        }
    }
}
