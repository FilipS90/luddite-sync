package com.fstojilj.luddite.sync.client.config;

import com.fstojilj.luddite.sync.client.model.HostEndpoint;
import com.fstojilj.luddite.sync.client.repository.HostRepository;
import com.fstojilj.luddite.sync.client.service.ClientSocketFactory;
import com.fstojilj.luddite.sync.client.service.ServerApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Resolves the server host and port to use on startup, before the sync loop makes its
 * first connection attempt. The {@code host} DB table is the single source of
 * truth for both — there is no {@code application.yml} fallback for either.
 *
 * <p>On every launch after the very first, this restores the last-used endpoint from
 * the DB. On the very first-ever launch (empty {@code host}), it seeds the DB
 * with {@link HostEndpoint#bootstrapDefault()} — a one-time bootstrap value that is
 * immediately persisted, so from that point on the DB alone drives host and port,
 * including this default itself. The port is kept fresh thereafter by
 * {@code ClientSyncService}, which re-discovers it over REST on every (re)connect and
 * writes it back here.
 *
 * <p>Runs before {@code ClientSyncService} thanks to {@code @Order(2)}, after
 * {@code SchemaInitializer} ({@code @Order(1)}) has created the {@code host}
 * table. Does not depend on {@code ClientSyncService} to avoid a circular bean
 * dependency (that service is needed by {@code HostSettingsService} for the reverse
 * direction — triggering a reconnect after a live host switch).
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(2)
public class HostSettingsInitializer implements ApplicationRunner {

    private final HostRepository hostRepository;
    private final ClientSocketFactory socketFactory;
    private final ServerApiClient serverApiClient;

    @Override
    public void run(ApplicationArguments args) {
        HostEndpoint endpoint = hostRepository.findLastUsed().orElseGet(HostEndpoint::bootstrapDefault);
        socketFactory.setServerHost(endpoint.host());
        socketFactory.setServerPort(endpoint.port());
        serverApiClient.switchHost(endpoint.host());
        hostRepository.recordUsed(endpoint.host(), endpoint.port());
        log.info("Using host:port {}:{}", endpoint.host(), endpoint.port());
    }
}
