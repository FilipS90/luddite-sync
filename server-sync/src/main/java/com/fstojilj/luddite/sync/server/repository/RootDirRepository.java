package com.fstojilj.luddite.sync.server.repository;

import com.fstojilj.luddite.sync.common.model.RootDir;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
@Slf4j
public class RootDirRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<RootDir> rowMapper = (rs, _) -> RootDir.builder()
            .id(rs.getLong("id"))
            .name(rs.getString("name"))
            .absolutePath(rs.getString("absolute_path"))
            .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null)
            .modifiedAt(rs.getTimestamp("modified_at") != null ? rs.getTimestamp("modified_at").toInstant() : null)
            .build();


    public long insert(RootDir rootDir) {
        String sql = """
                INSERT INTO root_dir (name, absolute_path)
                VALUES (?, ?)
                """;

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, rootDir.getName());
            ps.setString(2, rootDir.getAbsolutePath());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to retrieve generated key for FileMetadata");
        }

        return key.longValue();
    }


    public Set<RootDir> findAll() {
        String sql = "SELECT * FROM root_dir";
        return new HashSet<>(jdbcTemplate.query(sql, rowMapper));
    }

    public boolean deleteRootDirById(long id) {
        String sql = "DELETE FROM root_dir WHERE id = ?";
        int rowsAffected = jdbcTemplate.update(sql, id);
        return rowsAffected > 0;
    }


    public Optional<RootDir> getRootDirById(long rootDirId) {
        String sql = "SELECT * FROM root_dir WHERE id = ?";
        return Optional.ofNullable(jdbcTemplate.queryForObject(sql, rowMapper, rootDirId));
    }

    public Optional<RootDir> findByName(String name) {
        String sql = "SELECT * FROM root_dir WHERE name = ?";
        List<RootDir> results = jdbcTemplate.query(sql, rowMapper, name);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }
}
