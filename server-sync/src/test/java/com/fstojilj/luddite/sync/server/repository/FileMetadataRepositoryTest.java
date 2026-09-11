package com.fstojilj.luddite.sync.server.repository;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.fstojilj.luddite.sync.server.config.SchemaConstants.FILE_METADATA_TABLE;
import static com.fstojilj.luddite.sync.server.config.SchemaConstants.ROOT_DIR_TABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        jdbcTemplate.execute(ROOT_DIR_TABLE);
        jdbcTemplate.execute(FILE_METADATA_TABLE);

        // Insert a root_dir row so FK constraint is satisfied
        jdbcTemplate.update("INSERT INTO root_dir (name, is_private, password, absolute_path) VALUES ('photos', false, 'admin123', '/photos')");

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

    // ── upsert ────────────────────────────────────────────────────────────────

    @Test
    void upsert_newPath_insertsLiveRow() {
        repository.upsert(buildMeta("a.jpg", "a.jpg"));
        List<FileMetadata> rows = repository.findChangedSince(1, -1L, 100);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().deleted()).isFalse();
        assertThat(rows.getFirst().syncVersion()).isNull();
    }

    @Test
    void upsert_softDeletedPath_revivesRowAndKeepsClientIds() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        repository.addClientIdToAll(1, List.of("a.jpg"), "hw-id-1");
        repository.softDelete(1L, "a.jpg", 5L);

        repository.upsert(buildMeta("a.jpg", "a.jpg").toBuilder().checksum("new").fileSize(2048L).build());

        List<FileMetadata> rows = repository.findChangedSince(1, -1L, 100);
        assertThat(rows).hasSize(1);
        FileMetadata row = rows.getFirst();
        assertThat(row.deleted()).isFalse();
        assertThat(row.checksum()).isEqualTo("new");
        assertThat(row.fileSize()).isEqualTo(2048L);
        assertThat(row.syncVersion()).isNull();
        assertThat(row.clientIds()).isEqualTo("hw-id-1");
    }

    @Test
    void upsert_livePath_refreshesAndResetsSyncVersionKeepingClientIds() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        repository.updateSyncVersion(1, "a.jpg", 7L);
        repository.addClientIdToAll(1, List.of("a.jpg"), "hw-id-1");

        repository.upsert(buildMeta("a.jpg", "a.jpg").toBuilder().checksum("new").build());

        FileMetadata row = repository.findChangedSince(1, -1L, 100).getFirst();
        assertThat(row.checksum()).isEqualTo("new");
        assertThat(row.syncVersion()).isNull();
        assertThat(row.clientIds()).isEqualTo("hw-id-1");
    }

    // ── addClientIdToAll ──────────────────────────────────────────────────────

    @Test
    void addClientIdToAll_nullClientIds_setsClientId() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        repository.add(buildMeta("b.jpg", "b.jpg"));
        repository.addClientIdToAll(1, List.of("a.jpg", "b.jpg"), "hw-id-1");

        List<FileMetadata> rows = repository.findChangedSince(1, -1L, 100);
        assertThat(rows).hasSize(2)
                .allSatisfy(r -> assertThat(r.clientIds()).isEqualTo("hw-id-1"));
    }

    @Test
    void addClientIdToAll_existingClient_appendsCommaSeparated() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        repository.addClientIdToAll(1, List.of("a.jpg"), "hw-id-1");
        repository.addClientIdToAll(1, List.of("a.jpg"), "hw-id-2");

        assertThat(repository.findChangedSince(1, -1L, 100).getFirst().clientIds())
                .isEqualTo("hw-id-1,hw-id-2");
    }

    @Test
    void addClientIdToAll_sameClientTwice_isIdempotent() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        repository.addClientIdToAll(1, List.of("a.jpg"), "hw-id-1");
        repository.addClientIdToAll(1, List.of("a.jpg"), "hw-id-1");

        assertThat(repository.findChangedSince(1, -1L, 100).getFirst().clientIds())
                .isEqualTo("hw-id-1");
    }

    @Test
    void addClientIdToAll_prefixOfExistingId_isStillAppended() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        repository.addClientIdToAll(1, List.of("a.jpg"), "hw-id-10");
        repository.addClientIdToAll(1, List.of("a.jpg"), "hw-id-1");

        assertThat(repository.findChangedSince(1, -1L, 100).getFirst().clientIds())
                .isEqualTo("hw-id-10,hw-id-1");
    }

    @Test
    void addClientIdToAll_onlyTouchesGivenPaths() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        repository.add(buildMeta("b.jpg", "b.jpg"));
        repository.addClientIdToAll(1, List.of("a.jpg"), "hw-id-1");

        List<FileMetadata> rows = repository.findChangedSince(1, -1L, 100);
        assertThat(rows).filteredOn(r -> r.relativePath().equals("b.jpg"))
                .singleElement().satisfies(r -> assertThat(r.clientIds()).isNull());
    }

    @Test
    void addClientIdToAll_noPaths_isNoOp() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        repository.addClientIdToAll(1, List.of(), "hw-id-1");
        assertThat(repository.findChangedSince(1, -1L, 100).getFirst().clientIds()).isNull();
    }

    // ── softDelete ────────────────────────────────────────────────────────────

    @Test
    void softDelete_marksRowAsDeletedAndKeepsClientIds() {
        repository.add(buildMeta("photo.jpg", "photo.jpg"));
        repository.updateSyncVersion(1, "photo.jpg", 1L);
        repository.addClientIdToAll(1, List.of("photo.jpg"), "hw-id-1");
        repository.softDelete(1L, "photo.jpg", 2L);

        List<FileMetadata> changed = repository.findChangedSince(1, 1L, 100);
        assertThat(changed).hasSize(1);
        assertThat(changed.getFirst().deleted()).isTrue();
        assertThat(changed.getFirst().clientIds()).isEqualTo("hw-id-1");
    }

    // ── acknowledgeDelete ─────────────────────────────────────────────────────

    @Test
    void acknowledgeDelete_lastClient_hardDeletesRow() {
        repository.add(buildMeta("photo.jpg", "photo.jpg"));
        repository.addClientIdToAll(1, List.of("photo.jpg"), "hw-id-1");
        repository.softDelete(1L, "photo.jpg", 1L);
        repository.acknowledgeDelete(1, "photo.jpg", "hw-id-1");
        assertThat(repository.findChangedSince(1, -1L, 100)).isEmpty();
    }

    @Test
    void acknowledgeDelete_oneOfTwoClients_removesFromList() {
        repository.add(buildMeta("photo.jpg", "photo.jpg"));
        repository.addClientIdToAll(1, List.of("photo.jpg"), "hw-id-1");
        repository.addClientIdToAll(1, List.of("photo.jpg"), "hw-id-2");
        repository.softDelete(1L, "photo.jpg", 1L);
        repository.acknowledgeDelete(1, "photo.jpg", "hw-id-1");

        List<FileMetadata> rows = repository.findChangedSince(1, -1L, 100);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().clientIds()).isEqualTo("hw-id-2");
    }

    @Test
    void acknowledgeDelete_noClientsEverHeldFile_hardDeletesRow() {
        repository.add(buildMeta("photo.jpg", "photo.jpg"));
        repository.softDelete(1L, "photo.jpg", 1L);
        repository.acknowledgeDelete(1, "photo.jpg", "hw-id-1");
        assertThat(repository.findChangedSince(1, -1L, 100)).isEmpty();
    }

    // ── delete-propagation lifecycle ──────────────────────────────────────────
    // client_ids = clients holding the file; a soft-deleted row lives until each of them ACKs.

    @Test
    void lifecycle_allHoldersAck_rowIsHardDeletedOnlyAfterTheLastOne() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        receivedBy("a.jpg", "c1", "c2", "c3");
        repository.softDelete(1L, "a.jpg", 5L);

        repository.acknowledgeDelete(1, "a.jpg", "c1");
        repository.acknowledgeDelete(1, "a.jpg", "c2");
        assertThat(row("a.jpg")).isPresent()
                .get().satisfies(r -> assertThat(r.clientIds()).isEqualTo("c3"));

        repository.acknowledgeDelete(1, "a.jpg", "c3");
        assertThat(row("a.jpg")).isEmpty();
    }

    @Test
    void lifecycle_offlineHolder_keepsTombstoneUntilItComesBack() {
        repository.add(buildMeta("d.jpg", "d.jpg"));
        receivedBy("d.jpg", "c1", "c2", "c3");
        repository.softDelete(1L, "d.jpg", 5L);
        repository.acknowledgeDelete(1, "d.jpg", "c1");
        repository.acknowledgeDelete(1, "d.jpg", "c2");

        FileMetadata tombstone = row("d.jpg").orElseThrow();
        assertThat(tombstone.deleted()).isTrue();
        assertThat(tombstone.clientIds()).isEqualTo("c3");
        assertThat(repository.findChangedSince(1, 4L, 100))
                .as("c3 must still be told about the delete on its next poll")
                .extracting(FileMetadata::relativePath).containsExactly("d.jpg");
    }

    @Test
    void acknowledgeDelete_fromClientNotInList_doesNotShortenListOrHardDelete() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        receivedBy("a.jpg", "c1", "c2");
        repository.softDelete(1L, "a.jpg", 5L);

        repository.acknowledgeDelete(1, "a.jpg", "stranger");

        assertThat(row("a.jpg")).isPresent()
                .get().satisfies(r -> assertThat(r.clientIds()).isEqualTo("c1,c2"));
    }

    @Test
    void acknowledgeDelete_onLiveRow_isIgnored() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        receivedBy("a.jpg", "c1");

        repository.acknowledgeDelete(1, "a.jpg", "c1");

        FileMetadata live = row("a.jpg").orElseThrow();
        assertThat(live.deleted()).isFalse();
        assertThat(live.clientIds()).isEqualTo("c1");
    }

    @Test
    void lifecycle_modifyThenDelete_holdersSurviveTheModify() {
        repository.add(buildMeta("e.jpg", "e.jpg"));
        receivedBy("e.jpg", "c1", "c2", "c3");

        repository.upsert(buildMeta("e.jpg", "e.jpg").toBuilder().checksum("v2").build()); // modify
        repository.softDelete(1L, "e.jpg", 6L);

        assertThat(row("e.jpg").orElseThrow().clientIds()).isEqualTo("c1,c2,c3");
        repository.acknowledgeDelete(1, "e.jpg", "c1");
        assertThat(row("e.jpg")).as("c2 and c3 still pending").isPresent();
    }

    @Test
    void lifecycle_deleteThenRecreate_revivesRowAndReDeliversToEveryone() {
        repository.add(buildMeta("e.jpg", "e.jpg"));
        receivedBy("e.jpg", "c1", "c2", "c3");
        repository.softDelete(1L, "e.jpg", 5L);
        repository.acknowledgeDelete(1, "e.jpg", "c1");
        repository.acknowledgeDelete(1, "e.jpg", "c3");

        repository.upsert(buildMeta("e.jpg", "e.jpg").toBuilder().checksum("recreated").build());

        FileMetadata revived = row("e.jpg").orElseThrow();
        assertThat(revived.deleted()).isFalse();
        assertThat(revived.syncVersion()).isNull();
        assertThat(revived.checksum()).isEqualTo("recreated");
        assertThat(revived.clientIds()).isEqualTo("c2");
        assertThat(repository.findChangedSince(1, 99L, 100))
                .as("unversioned row is offered to every client regardless of their cursor")
                .extracting(FileMetadata::relativePath).containsExactly("e.jpg");

        receivedBy("e.jpg", "c1", "c2", "c3");
        assertThat(row("e.jpg").orElseThrow().clientIds()).isEqualTo("c2,c1,c3");
    }

    @Test
    void add_whileTombstoneOccupiesPath_violatesUniqueConstraint() {
        repository.add(buildMeta("a.jpg", "a.jpg"));
        receivedBy("a.jpg", "c1");
        repository.softDelete(1L, "a.jpg", 5L);

        assertThatThrownBy(() -> repository.add(buildMeta("a.jpg", "a.jpg")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UNIQUE constraint failed");
    }

    private void receivedBy(String path, String... clientIds) {
        for (String c : clientIds) repository.addClientIdToAll(1, List.of(path), c);
    }

    private Optional<FileMetadata> row(String path) {
        return repository.findChangedSince(1, -1L, 100).stream()
                .filter(r -> r.relativePath().equals(path)).findFirst();
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

