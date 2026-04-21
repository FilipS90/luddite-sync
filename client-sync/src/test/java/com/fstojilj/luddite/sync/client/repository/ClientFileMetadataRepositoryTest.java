package com.fstojilj.luddite.sync.client.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests FileMetadataRepository (client) against a real in-memory SQLite database.
 */
class ClientFileMetadataRepositoryTest {

    private FileMetadataRepository repository;

    @BeforeEach
    void setUp() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE file_metadata (
                    dir_name      TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    PRIMARY KEY (dir_name, relative_path)
                )""");
        repository = new FileMetadataRepository(jdbc);
    }

    // ── upsert ────────────────────────────────────────────────────────────────

    @Test
    void upsert_insertsRecord() {
        repository.upsert("photos", "photos/img.jpg");
        List<String> result = repository.findAllByDir("photos");
        assertThat(result).containsExactly("photos/img.jpg");
    }

    @Test
    void upsert_calledTwice_doesNotDuplicate() {
        repository.upsert("photos", "photos/img.jpg");
        repository.upsert("photos", "photos/img.jpg");
        assertThat(repository.findAllByDir("photos")).hasSize(1);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_existingRecord_removesIt() {
        repository.upsert("photos", "photos/img.jpg");
        repository.delete("photos", "photos/img.jpg");
        assertThat(repository.findAllByDir("photos")).isEmpty();
    }

    @Test
    void delete_nonExistingRecord_doesNotThrow() {
        repository.delete("photos", "photos/ghost.jpg"); // should not throw
    }

    // ── findAllByDir ──────────────────────────────────────────────────────────

    @Test
    void findAllByDir_empty_returnsEmptyList() {
        assertThat(repository.findAllByDir("photos")).isEmpty();
    }

    @Test
    void findAllByDir_multipleFiles_returnsAll() {
        repository.upsert("photos", "photos/a.jpg");
        repository.upsert("photos", "photos/b.jpg");
        repository.upsert("docs", "docs/readme.txt");

        List<String> photos = repository.findAllByDir("photos");
        assertThat(photos).containsExactlyInAnyOrder("photos/a.jpg", "photos/b.jpg");

        List<String> docs = repository.findAllByDir("docs");
        assertThat(docs).containsExactly("docs/readme.txt");
    }
}

