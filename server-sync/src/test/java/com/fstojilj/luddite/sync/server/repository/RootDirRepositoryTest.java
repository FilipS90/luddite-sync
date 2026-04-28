package com.fstojilj.luddite.sync.server.repository;

import com.fstojilj.luddite.sync.common.model.RootDir;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests RootDirRepository against a real in-memory SQLite database.
 */
class RootDirRepositoryTest {

    private RootDirRepository repository;

    @BeforeEach
    void setUp() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE root_dir (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    absolute_path TEXT NOT NULL UNIQUE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    modified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )""");
        repository = new RootDirRepository(jdbc);
    }

    private RootDir buildDir(String name, String path) {
        return RootDir.builder().name(name).absolutePath(path).build();
    }

    // ── insert ────────────────────────────────────────────────────────────────

    @Test
    void insert_returnsGeneratedId() {
        int id = repository.insert(buildDir("photos", "/photos"));
        assertThat(id).isGreaterThan(0);
    }

    // ── findAll ───────────────────────────────────────────────────────────────

    @Test
    void findAll_empty_returnsEmptySet() {
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void findAll_withEntries_returnsAll() {
        repository.insert(buildDir("photos", "/photos"));
        repository.insert(buildDir("docs", "/docs"));
        Set<RootDir> all = repository.findAll();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(RootDir::getName).containsExactlyInAnyOrder("photos", "docs");
    }

    // ── findByName ────────────────────────────────────────────────────────────

    @Test
    void findByName_existing_returnsDir() {
        repository.insert(buildDir("photos", "/photos"));
        Optional<RootDir> result = repository.findByName("photos");
        assertThat(result).isPresent();
        assertThat(result.get().getAbsolutePath()).isEqualTo("/photos");
    }

    @Test
    void findByName_notExisting_returnsEmpty() {
        assertThat(repository.findByName("missing")).isEmpty();
    }

    // ── getRootDirById ────────────────────────────────────────────────────────

    @Test
    void getRootDirById_existing_returnsDir() {
        int id = repository.insert(buildDir("photos", "/photos"));
        Optional<RootDir> result = repository.getRootDirById(id);
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("photos");
    }

    // ── deleteRootDirById ─────────────────────────────────────────────────────

    @Test
    void deleteRootDirById_existing_deletesAndReturnsTrue() {
        int id = repository.insert(buildDir("photos", "/photos"));
        boolean deleted = repository.deleteRootDirById(id);
        assertThat(deleted).isTrue();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void deleteRootDirById_notExisting_returnsFalse() {
        boolean deleted = repository.deleteRootDirById(999);
        assertThat(deleted).isFalse();
    }
}

