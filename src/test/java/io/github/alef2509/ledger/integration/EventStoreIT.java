package io.github.alef2509.ledger.integration;

import io.github.alef2509.ledger.TestcontainersConfiguration;
import io.github.alef2509.ledger.domain.WalletEvent;
import io.github.alef2509.ledger.domain.WalletEvent.FundsDeposited;
import io.github.alef2509.ledger.domain.WalletEvent.WalletOpened;
import io.github.alef2509.ledger.eventstore.ConcurrencyException;
import io.github.alef2509.ledger.eventstore.EventStore;
import io.github.alef2509.ledger.eventstore.RecordedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class EventStoreIT {

    @Autowired
    private EventStore store;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    @Test
    void appendsAndReadsAStreamInOrder() {
        UUID id = UUID.randomUUID();

        EventStore.AppendResult result = store.append(id, 0, List.of(opened(id), deposit(id, "10.00")), "{}");
        store.append(id, 2, List.of(deposit(id, "5.00")), "{}");

        assertThat(result.newVersion()).isEqualTo(2);
        assertThat(store.readStream(id)).extracting(RecordedEvent::streamVersion).containsExactly(1, 2, 3);
        assertThat(store.readStream(id, 1, 2)).singleElement().extracting(RecordedEvent::event)
                .isEqualTo(deposit(id, "10.00"));
    }

    @Test
    void secondWriterWithTheSameExpectedVersionLoses() {
        UUID id = UUID.randomUUID();
        store.append(id, 0, List.of(opened(id)), "{}");

        store.append(id, 1, List.of(deposit(id, "1.00")), "{}");

        assertThatThrownBy(() -> store.append(id, 1, List.of(deposit(id, "2.00")), "{}"))
                .isInstanceOf(ConcurrencyException.class);
        assertThat(store.readStream(id)).hasSize(2);
    }

    @Test
    void historyCannotBeRewritten() {
        UUID id = UUID.randomUUID();
        store.append(id, 0, List.of(opened(id)), "{}");

        assertThatThrownBy(() -> jdbc.update("UPDATE events SET payload = '{}' WHERE stream_id = ?", id))
                .hasMessageContaining("events are immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM events WHERE stream_id = ?", id))
                .hasMessageContaining("events are immutable");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE events"))
                .hasMessageContaining("events are immutable");
    }

    /**
     * A transaction takes position N and stays open; a later one takes N+1 and
     * commits. A naive reader would process N+1, move its checkpoint past N and
     * never see N. The store must hold N+1 back until N's transaction ends.
     */
    @Test
    void readersNeverSkipPositionsOfTransactionsStillInFlight() throws Exception {
        long before = lastPosition();
        UUID slow = UUID.randomUUID();
        UUID fast = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO events (stream_id, stream_version, event_type, schema_version, payload, occurred_at)
                    VALUES (?, 1, 'WalletOpened', 1, ?::jsonb, now())
                    """)) {
                insert.setObject(1, slow);
                insert.setString(2, "{\"walletId\":\"%s\",\"owner\":\"slow\",\"currency\":\"EUR\"}".formatted(slow));
                insert.executeUpdate(); // takes the lower position, not committed yet
            }

            store.append(fast, 0, List.of(opened(fast)), "{}"); // higher position, committed

            assertThat(store.readAll(before, 100)).extracting(RecordedEvent::streamId).doesNotContain(fast, slow);

            connection.commit();
        }

        assertThat(store.readAll(before, 100)).extracting(RecordedEvent::streamId)
                .containsSubsequence(slow, fast);
    }

    @Test
    void oldEventsAreUpcastOnRead() {
        UUID id = UUID.randomUUID();
        store.append(id, 0, List.of(opened(id)), "{}");
        jdbc.update("""
                INSERT INTO events (stream_id, stream_version, event_type, schema_version, payload, occurred_at)
                VALUES (?, 2, 'FundsDeposited', 1, ?::jsonb, now())
                """, id, "{\"walletId\":\"%s\",\"amountInCents\":2550}".formatted(id));

        assertThat(store.readStream(id).getLast().event()).isEqualTo(deposit(id, "25.50"));
    }

    private long lastPosition() {
        Long position = jdbc.queryForObject("SELECT coalesce(max(global_position), 0) FROM events", Long.class);
        return position == null ? 0 : position;
    }

    private static WalletOpened opened(UUID id) {
        return new WalletOpened(id, "Ada", "EUR");
    }

    private static WalletEvent deposit(UUID id, String amount) {
        return new FundsDeposited(id, new BigDecimal(amount), "EUR");
    }
}
