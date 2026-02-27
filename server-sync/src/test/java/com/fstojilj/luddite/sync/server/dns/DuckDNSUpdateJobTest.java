package com.fstojilj.luddite.sync.server.dns;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

@ExtendWith(MockitoExtension.class)
class DuckDNSUpdateJobTest {

    @InjectMocks
    private DuckDNSUpdateJob duckDNSUpdateJob;

    @Test
    void getDomain_shouldReturnInjectedValue() {
        ReflectionTestUtils.setField(duckDNSUpdateJob, "domain", "my-sync");
        assertThat(duckDNSUpdateJob.getDomain()).isEqualTo("my-sync");
    }

    @Test
    void setDomain_shouldUpdateDomain() {
        duckDNSUpdateJob.setDomain("new-domain");
        assertThat(duckDNSUpdateJob.getDomain()).isEqualTo("new-domain");
    }

    @Test
    void getToken_shouldReturnInjectedValue() {
        ReflectionTestUtils.setField(duckDNSUpdateJob, "token", "my-token");
        assertThat(duckDNSUpdateJob.getToken()).isEqualTo("my-token");
    }

    @Test
    void setToken_shouldUpdateToken() {
        duckDNSUpdateJob.setToken("new-token");
        assertThat(duckDNSUpdateJob.getToken()).isEqualTo("new-token");
    }

    @Test
    void updateDuckDNS_emptyToken_shouldNotThrow() {
        ReflectionTestUtils.setField(duckDNSUpdateJob, "domain", "test");
        ReflectionTestUtils.setField(duckDNSUpdateJob, "token", "");
        // Does not throw — logs error and returns gracefully on network failure
        assertThatNoException().isThrownBy(() -> duckDNSUpdateJob.updateDuckDNS());
    }
}

