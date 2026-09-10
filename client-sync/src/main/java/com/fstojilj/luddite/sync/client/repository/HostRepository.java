package com.fstojilj.luddite.sync.client.repository;

import com.fstojilj.luddite.sync.client.model.HostEndpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persists the server hosts the client has connected to, together with the last port
 * successfully used for each — the single source of truth for both, so the UI can
 * pre-fill the last-used endpoint and the sync loop can fall back to a known-good port
 * if a fresh REST lookup ever fails.
 *
 * <p>Maps to the {@code host} table in the client SQLite database.
 */
@Repository
@RequiredArgsConstructor
public class HostRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Returns all previously-used hosts, most recently used first.
     */
    public List<String> findAllOrderedByRecency() {
        return jdbcTemplate.queryForList(
                "SELECT name FROM host ORDER BY last_used DESC", String.class);
    }

    /**
     * Returns the most recently used host:port endpoint, if any.
     */
    public Optional<HostEndpoint> findLastUsed() {
        List<HostEndpoint> results = jdbcTemplate.query(
                "SELECT name, port FROM host ORDER BY last_used DESC LIMIT 1",
                (rs, rowNum) -> new HostEndpoint(rs.getString("name"), rs.getInt("port")));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * Returns the last known port for a specific host, if it's been used before.
     *
     * @param name the host name to look up
     */
    public Optional<Integer> findPortForName(String name) {
        List<Integer> results = jdbcTemplate.queryForList(
                "SELECT port FROM host WHERE name = ?", Integer.class, name);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * Records that {@code host:port} was just used, updating its recency and port if
     * the host is already present.
     *
     * @param host the host that was connected to
     * @param port the port currently in use for that host
     */
    public void recordUsed(String name, int port) {
        jdbcTemplate.update("""
                INSERT INTO host (name, port, last_used)
                VALUES (?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET port = excluded.port, last_used = excluded.last_used
                """, name, port, System.currentTimeMillis());
    }

    /**
     * Removes a host from the history.
     *
     * @param name the host to remove
     */
    public void remove(String name) {
        jdbcTemplate.update("DELETE FROM host WHERE name = ?", name);
    }
}
