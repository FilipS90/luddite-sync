package com.fstojilj.luddite.sync.client.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the connection-state tracking added to fix the flaky {@code CONNECTED}/
 * {@code DISCONNECTED} flag: state is now set explicitly at well-defined transition points
 * ({@link ClientSyncService#stop()}, {@link ClientSyncService#reconnect()}) instead of being
 * derived from raw socket introspection, so these transitions are asserted directly here
 * rather than by racing the background poll thread.
 */
@ExtendWith(MockitoExtension.class)
class ClientSyncServiceTest {

    @Mock
    private RootDirService rootDirService;
    @Mock
    private FileMetadataService fileMetadataService;
    @Mock
    private ClientIdService clientIdService;
    @Mock
    private ServerApiClient serverApiClient;
    @Mock
    private ClientSocketFactory socketFactory;

    @InjectMocks
    private ClientSyncService clientSyncService;

    @Test
    void getConnectionState_defaultsToDisconnectedBeforeConnecting() {
        assertThat(clientSyncService.getConnectionState()).isEqualTo(ConnectionState.DISCONNECTED);
        assertThat(clientSyncService.isConnected()).isFalse();
    }

    @Test
    void isConnected_isTrueForAnyNonDisconnectedState() {
        ReflectionTestUtils.setField(clientSyncService, "connectionState", ConnectionState.IDLE);
        assertThat(clientSyncService.isConnected()).isTrue();

        ReflectionTestUtils.setField(clientSyncService, "connectionState", ConnectionState.TRANSFERRING);
        assertThat(clientSyncService.isConnected()).isTrue();
    }

    @Test
    void stop_setsConnectionStateToDisconnected() {
        ReflectionTestUtils.setField(clientSyncService, "connectionState", ConnectionState.IDLE);

        clientSyncService.stop();

        assertThat(clientSyncService.getConnectionState()).isEqualTo(ConnectionState.DISCONNECTED);
    }

    @Test
    void reconnect_eagerlySetsConnectionStateToDisconnected() {
        ReflectionTestUtils.setField(clientSyncService, "connectionState", ConnectionState.TRANSFERRING);

        clientSyncService.reconnect();

        assertThat(clientSyncService.getConnectionState()).isEqualTo(ConnectionState.DISCONNECTED);
    }
}
