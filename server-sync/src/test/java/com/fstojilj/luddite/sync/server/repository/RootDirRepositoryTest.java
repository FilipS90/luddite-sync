package com.fstojilj.luddite.sync.server.repository;

import com.fstojilj.luddite.sync.common.model.RootDir;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.Optional;
import java.util.Set;

import static com.fstojilj.luddite.sync.server.config.SchemaConstants.ROOT_DIR_TABLE;
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
        jdbc.execute(ROOT_DIR_TABLE);
        repository = new RootDirRepository(jdbc);
    }

    private RootDir buildDir(String name, Boolean isPrivate, String password, String path) {
        return RootDir.builder().name(name).isPrivate(isPrivate).password(password).absolutePath(path).build();
    }

    // ── insert ────────────────────────────────────────────────────────────────

    @Test
    void insert_returnsGeneratedId() {
        int id = repository.insert(buildDir("photos", false, "admin", "/photos"));
        assertThat(id).isGreaterThan(0);
    }

    // ── findAll ───────────────────────────────────────────────────────────────

    @Test
    void findAll_empty_returnsEmptySet() {
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void findAll_withEntries_returnsAll() {
        repository.insert(buildDir("photos", false, null, "/photos"));
        repository.insert(buildDir("docs", false, null, "/docs"));
        Set<RootDir> all = repository.findAll();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(RootDir::getName).containsExactlyInAnyOrder("photos", "docs");
    }

    // ── findByName ────────────────────────────────────────────────────────────

    @Test
    void findByName_existing_returnsDir() {
        repository.insert(buildDir("photos", true, "admin", "/photos"));
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
        int id = repository.insert(buildDir("photos", false, null, "/photos"));
        Optional<RootDir> result = repository.getRootDirById(id);
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("photos");
    }

    // ── deleteRootDirById ─────────────────────────────────────────────────────

    @Test
    void deleteRootDirById_existing_deletesAndReturnsTrue() {
        int id = repository.insert(buildDir("photos", false, null, "/photos"));
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

