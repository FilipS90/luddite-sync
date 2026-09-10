package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.client.model.HostEndpoint;
import com.fstojilj.luddite.sync.client.repository.HostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates switching the server host at runtime from the UI: updates the socket
 * factory and REST client, persists the new host (and its last-known port) to history,
 * and triggers a reconnect.
 */
@Service
@RequiredArgsConstructor
public class HostSettingsService {

    private final HostRepository hostRepository;
    private final ClientSocketFactory socketFactory;
    private final ServerApiClient serverApiClient;
    private final ClientSyncService clientSyncService;

    /**
     * Returns previously-used hosts, most recently used first.
     */
    public List<String> getHistory() {
        return hostRepository.findAllOrderedByRecency();
    }

    /**
     * Returns the host currently configured for the socket connection.
     */
    public String getCurrentHost() {
        return socketFactory.getServerHost();
    }

    /**
     * Switches the client to a new server host: updates the socket factory and REST
     * client, records the endpoint in history, and forces an immediate reconnect.
     *
     * <p>The port is seeded from this host's last-known port in the DB (or the
     * bootstrap default, for a host never seen before) purely as a starting point —
     * {@code ClientSyncService} re-discovers the real port over REST on the very next
     * connect cycle and corrects it if needed.
     *
     * <p>Reconnecting to the host that is already configured is deliberately not a
     * no-op: re-pressing CONNECT is how the user forces a re-poll after the server's
     * shared dirs changed, and it also re-points the REST client, which would
     * otherwise stay on whatever host it was last built against.
     *
     * @param host the new server host
     */
    public void switchTo(String host) {
        String trimmed = host == null ? "" : host.trim();
        if (trimmed.isEmpty()) return;

        int port = hostRepository.findPortForName(trimmed).orElse(HostEndpoint.DEFAULT_PORT);
        socketFactory.setServerHost(trimmed);
        socketFactory.setServerPort(port);
        serverApiClient.switchHost(trimmed);
        hostRepository.recordUsed(trimmed, port);
        clientSyncService.reconnect();
    }

    /**
     * Removes a host from the history dropdown.
     *
     * @param host the host to forget
     */
    public void forget(String host) {
        hostRepository.remove(host);
    }
}
