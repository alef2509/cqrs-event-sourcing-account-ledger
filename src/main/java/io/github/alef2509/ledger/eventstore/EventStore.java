package io.github.alef2509.ledger.eventstore;

import io.github.alef2509.ledger.domain.WalletEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Append-only event log on PostgreSQL.
 *
 * <ul>
 *   <li>{@link #append} writes events with consecutive stream versions after
 *       {@code expectedVersion}; the unique (stream, version) constraint turns a
 *       concurrent append into a {@link ConcurrencyException}.</li>
 *   <li>{@link #readStream} rebuilds one aggregate.</li>
 *   <li>{@link #readAll} feeds projections in global order and never skips an event
 *       of a transaction that is still in flight (see ADR 3).</li>
 * </ul>
 */
public class EventStore {

    public record AppendResult(int newVersion, long lastPosition) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EventSerializer serializer;
    private final Clock clock;
    private final RowMapper<RecordedEvent> rowMapper;

    public EventStore(JdbcTemplate jdbc, TransactionTemplate transactions, EventSerializer serializer, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.serializer = serializer;
        this.clock = clock;
        this.rowMapper = (rs, i) -> new RecordedEvent(
                rs.getLong("global_position"),
                rs.getObject("stream_id", UUID.class),
                rs.getInt("stream_version"),
                serializer.deserialize(rs.getString("event_type"), rs.getInt("schema_version"), rs.getString("payload")),
                rs.getTimestamp("occurred_at").toInstant());
    }

    public AppendResult append(UUID streamId, int expectedVersion, List<WalletEvent> events, String metadataJson) {
        if (events.isEmpty()) {
            return new AppendResult(expectedVersion, -1);
        }
        try {
            return transactions.execute(status -> {
                long lastPosition = -1;
                int version = expectedVersion;
                Timestamp now = Timestamp.from(clock.instant());
                for (WalletEvent event : events) {
                    version++;
                    lastPosition = jdbc.queryForObject("""
                                    INSERT INTO events (stream_id, stream_version, event_type, schema_version,
                                                        payload, metadata, occurred_at)
                                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                                    RETURNING global_position
                                    """, Long.class,
                            streamId, version, serializer.typeOf(event), serializer.schemaVersionOf(event),
                            serializer.serialize(event), metadataJson, now);
                }
                return new AppendResult(version, lastPosition);
            });
        } catch (DuplicateKeyException e) {
            throw new ConcurrencyException(streamId, expectedVersion);
        }
    }

    /** Events of one stream with {@code afterVersion < version <= upToVersion}. */
    public List<RecordedEvent> readStream(UUID streamId, int afterVersion, int upToVersion) {
        return jdbc.query("""
                SELECT * FROM events
                WHERE stream_id = ? AND stream_version > ? AND stream_version <= ?
                ORDER BY stream_version
                """, rowMapper, streamId, afterVersion, upToVersion);
    }

    public List<RecordedEvent> readStream(UUID streamId) {
        return readStream(streamId, 0, Integer.MAX_VALUE);
    }

    /**
     * Next events after {@code position} in global order, but only from transactions
     * that are older than every transaction still running. Identity values are
     * allocated before commit, so without this filter a reader could see position
     * 11 committed, move its checkpoint past 10, and lose 10 when it commits later.
     */
    public List<RecordedEvent> readAll(long position, int limit) {
        return jdbc.query("""
                SELECT * FROM events
                WHERE global_position > ?
                  AND tx_id < pg_snapshot_xmin(pg_current_snapshot())
                ORDER BY global_position
                LIMIT ?
                """, rowMapper, position, limit);
    }
}
