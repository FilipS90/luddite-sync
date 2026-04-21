package com.fstojilj.luddite.sync.server.repository;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests FileMetadataRepository against a real in-memory SQLite database.
 */
class FileMetadataRepositoryTest {

    private FileMetadataRepository repository;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        jdbcTemplate = new JdbcTemplate(ds);

        jdbcTemplate.execute("""
                CREATE TABLE root_dir (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    absolute_path TEXT NOT NULL UNIQUE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    modified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE file_metadata (
                    id            INTEGER  PRIMARY KEY AUTOINCREMENT,
                    filename      TEXT     NOT NULL,
                    root_dir_id   INTEGER  NOT NULL REFERENCES root_dir(id) ON DELETE CASCADE,
                    relative_path TEXT     NOT NULL,
                    checksum      TEXT     NOT NULL,
                    file_size     INTEGER  NOT NULL,
                    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    modified_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    sync_version  BIGINT,
                    deleted       BOOLEAN  NOT NULL DEFAULT FALSE,
                    client_ids    TEXT,
                    UNIQUE (root_dir_id, relative_path, filename)
                )""");

        // Insert a root_dir row so FK constraint is satisfied
        jdbcTemplate.update("INSERT INTO root_dir (name, absolute_path) VALUES ('photos', '/photos')");

        repository = new FileMetadataRepository(jdbcTemplate);
    }

    private FileMetadata buildMeta(String filename, String relativePath) {
        return FileMetadata.builder()
                .filename(filename)
                .rootDirId(1)
                .relativePath(relativePath)
                .checksum("abc123")
                .fileSize(1024L)
                .createdAt(Instant.now())
                .modifiedAt(Instant.now())
                .syncVersion(null)
                .deleted(false)
                .clientIds(null)
                .build();
    }

    // ── add ───────────────────────────────────────────────────────────────────

    @Test
    void add_validMetadata_returnsGeneratedId() {
        long id = repository.add(buildMeta("photo.jpg", "photo.jpg"));
        assertThat(id).isGreaterThan(0);
    }

    // ── findChangedSince ──────────────────────────────────────────────────────

    @Test
    void findChangedSince_noSyncVersion_returnsUndeliveredRows() {
        repository.add(buildMeta("photo.jpg", "photo.jpg"));
        List<FileMetadata> changed = repository.findChangedSince(1, -1L, 100);
        assertThat(changed).hasSize(1);
        assertThat(changed.getFirst().filename()).isEqualTo("photo.jpg");
    }

    @Test
    void findChangedSince_afterVersionStamp_doesNotReturn() {
        repository.add(buildMeta("photo.jpg", "photo.jpg"));
        repository.updateSyncVersion(1, "photo.jpg", 5L);
        List<FileMetadata> changed = repository.findChangedSince(1, 5L, 100);
        assertThat(changed).isEmpty();
    }

    @Test
    void findChangedSince_respectsLimit() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        repository.add(buildMeta("b.jpg", "b.jpg"));
        repository.add(buildMeta("c.jpg", "c.jpg"));
        List<FileMetadata> changed = repository.findChangedSince(1, -1L, 2);
        assertThat(changed).hasSize(2);
    }

    // ── updateSyncVersion ─────────────────────────────────────────────────────

    @Test
    void updateSyncVersion_setsVersion() {
        repository.add(buildMeta("photo.jpg", "photo.jpg"));
        repository.updateSyncVersion(1, "photo.jpg", 10L);
        List<FileMetadata> rows = repository.findChangedSince(1, 9L, 100);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().syncVersion()).isEqualTo(10L);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_existingRow_removesIt() {
        repository.add(buildMeta("photo.jpg", "photo.jpg"));
        repository.delete(1, "photo.jpg");
        assertThat(repository.findChangedSince(1, -1L, 100)).isEmpty();
    }

    // ── deleteAllByRootDirId ──────────────────────────────────────────────────

    @Test
    void deleteAllByRootDirId_removesAll() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        repository.add(buildMeta("b.jpg", "b.jpg"));
        repository.deleteAllByRootDirId(1);
        assertThat(repository.findChangedSince(1, -1L, 100)).isEmpty();
    }

    // ── softDelete ────────────────────────────────────────────────────────────

    @Test
    void softDelete_marksRowAsDeleted() {
        repository.add(buildMeta("photo.jpg", "photo.jpg"));
        repository.updateSyncVersion(1, "photo.jpg", 1L);
        repository.softDelete(1L, "photo.jpg", 2L, "hw-id-1");
        List<FileMetadata> changed = repository.findChangedSince(1, 1L, 100);
        assertThat(changed).hasSize(1);
        assertThat(changed.getFirst().deleted()).isTrue();
        assertThat(changed.getFirst().clientIds()).isEqualTo("hw-id-1");
    }

    // ── acknowledgeDelete ─────────────────────────────────────────────────────

    @Test
    void acknowledgeDelete_lastClient_hardDeletesRow() {
        repository.add(buildMeta("photo.jpg", "photo.jpg"));
        repository.softDelete(1L, "photo.jpg", 1L, "hw-id-1");
        repository.acknowledgeDelete(1, "photo.jpg", "hw-id-1");
        assertThat(repository.findChangedSince(1, -1L, 100)).isEmpty();
    }

    @Test
    void acknowledgeDelete_oneOfTwoClients_removesFromList() {
        repository.add(buildMeta("photo.jpg", "photo.jpg"));
        repository.softDelete(1L, "photo.jpg", 1L, "hw-id-1,hw-id-2");
        repository.acknowledgeDelete(1, "photo.jpg", "hw-id-1");

        List<FileMetadata> rows = repository.findChangedSince(1, -1L, 100);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().clientIds()).isEqualTo("hw-id-2");
    }

    // ── getMaxSyncVersionByRootDir ────────────────────────────────────────────

    @Test
    void getMaxSyncVersionByRootDir_noRows_returnsEmptyMap() {
        Map<Integer, Long> result = repository.getMaxSyncVersionByRootDir();
        assertThat(result).isEmpty();
    }

    @Test
    void getMaxSyncVersionByRootDir_withRows_returnsMaxPerDir() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        repository.add(buildMeta("b.jpg", "b.jpg"));
        repository.updateSyncVersion(1, "a.jpg", 5L);
        repository.updateSyncVersion(1, "b.jpg", 10L);
        Map<Integer, Long> result = repository.getMaxSyncVersionByRootDir();
        assertThat(result).containsEntry(1, 10L);
    }
}

