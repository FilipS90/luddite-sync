package com.fstojilj.luddite.sync.server.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncServerPropertiesTest {

    @Test
    void defaultRootDirs_shouldBeEmptyList() {
        SyncServerProperties props = new SyncServerProperties();
        assertThat(props.getRootDirs()).isEmpty();
    }

    @Test
    void setRootDirs_shouldStoreAndReturnList() {
        SyncServerProperties props = new SyncServerProperties();
        props.setRootDirs(List.of("/photos", "/docs"));
        assertThat(props.getRootDirs()).containsExactly("/photos", "/docs");
    }

    @Test
    void setRootDirs_emptyList_shouldClear() {
        SyncServerProperties props = new SyncServerProperties();
        props.setRootDirs(List.of("/photos"));
        props.setRootDirs(List.of());
        assertThat(props.getRootDirs()).isEmpty();
    }
}

