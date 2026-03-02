package com.fstojilj.luddite.sync.server.dns;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
@Slf4j
public class DuckDNSUpdateJob {

    @Getter
    @Setter
    @Value("${sync.dns.domain}")
    private volatile String domain;

    @Getter
    @Setter
    @Value("${sync.dns.token}")
    private volatile String token;

    @Scheduled(fixedRate = 300000, initialDelay = 0) // Every 5 minutes, run immediately on startup
    @Async("duckDnsExecutor")
    public void updateDuckDNS() {
        String url = String.format(
                "https://www.duckdns.org/update?domains=%s&token=%s",
                domain,
                token
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


        if (response.body().contains("OK")) {
            log.info("DuckDNS updated successfully");
        } else {
            log.error("DuckDNS update failed: {}", response.body());
        }
    }
}
