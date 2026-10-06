package io.github.alef2509.ledger.eventstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alef2509.ledger.domain.WalletState;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Snapshots bound the cost of loading long-lived wallets: load the latest
 * snapshot, then only the events after it. They are a cache: a snapshot taken
 * with an older state schema is ignored and the wallet is rebuilt from events.
 */
public class SnapshotStore {

    /** Bump when {@link WalletState} changes shape; old snapshots then stop being used. */
    static final int STATE_SCHEMA_VERSION = 1;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SnapshotStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Optional<WalletState> load(UUID streamId) {
        return jdbc.query("SELECT state FROM snapshots WHERE stream_id = ? AND schema_version = ?",
                (rs, i) -> read(rs.getString("state")), streamId, STATE_SCHEMA_VERSION).stream().findFirst();
    }

    public void save(WalletState state) {
        jdbc.update("""
                INSERT INTO snapshots (stream_id, stream_version, schema_version, state, taken_at)
                VALUES (?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (stream_id) DO UPDATE
                    SET stream_version = EXCLUDED.stream_version, schema_version = EXCLUDED.schema_version,
                        state = EXCLUDED.state, taken_at = EXCLUDED.taken_at
                    WHERE snapshots.stream_version < EXCLUDED.stream_version
                """, state.id(), state.version(), STATE_SCHEMA_VERSION, write(state), Timestamp.from(clock.instant()));
    }

    private String write(WalletState state) {
        try {
            return mapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private WalletState read(String json) {
        try {
            return mapper.readValue(json, WalletState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
