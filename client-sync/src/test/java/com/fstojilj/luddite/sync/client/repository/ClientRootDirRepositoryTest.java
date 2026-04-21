package com.fstojilj.luddite.sync.client.repository;

import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests RootDirRepository (client) against a real in-memory SQLite database.
 */
class ClientRootDirRepositoryTest {

    private RootDirRepository repository;

    @BeforeEach
    void setUp() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE root_dirs (
                    dir_name TEXT PRIMARY KEY,
                    last_sync_version INTEGER NOT NULL DEFAULT -1
                )""");
        repository = new RootDirRepository(jdbc);
    }

    // ── findAll ───────────────────────────────────────────────────────────────

    @Test
    void findAll_empty_returnsEmptyList() {
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void findAll_withEntries_returnsAll() {
        repository.registerIfAbsent("photos");
        repository.registerIfAbsent("docs");
        List<SyncHandshakeEntry> all = repository.findAll();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(SyncHandshakeEntry::dirName)
                .containsExactlyInAnyOrder("photos", "docs");
    }

    // ── registerIfAbsent ──────────────────────────────────────────────────────

    @Test
    void registerIfAbsent_newDir_insertsWithVersionMinusOne() {
        repository.registerIfAbsent("photos");
        List<SyncHandshakeEntry> all = repository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.getFirst().lastSyncVersion()).isEqualTo(-1L);
    }

    @Test
    void registerIfAbsent_existingDir_doesNotOverwriteVersion() {
        repository.registerIfAbsent("photos");
        repository.updateSyncVersion("photos", 42L);
        repository.registerIfAbsent("photos"); // second call should be no-op
        List<SyncHandshakeEntry> all = repository.findAll();
        assertThat(all.getFirst().lastSyncVersion()).isEqualTo(42L);
    }

    // ── remove ────────────────────────────────────────────────────────────────

    @Test
    void remove_existingDir_removesIt() {
        repository.registerIfAbsent("photos");
        repository.remove("photos");
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void remove_nonExistingDir_doesNotThrow() {
        repository.remove("missing"); // should not throw
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    void reset_setsVersionToMinusOne() {
        repository.registerIfAbsent("photos");
        repository.updateSyncVersion("photos", 100L);
        repository.reset("photos");
        long version = repository.findAll().getFirst().lastSyncVersion();
        assertThat(version).isEqualTo(-1L);
    }

    // ── updateSyncVersion ─────────────────────────────────────────────────────

    @Test
    void updateSyncVersion_advancesVersion() {
        repository.registerIfAbsent("photos");
        repository.updateSyncVersion("photos", 55L);
        assertThat(repository.findAll().getFirst().lastSyncVersion()).isEqualTo(55L);
    }
}


