package io.github.alef2509.ledger.projection;

import io.github.alef2509.ledger.eventstore.EventStore;
import io.github.alef2509.ledger.eventstore.RecordedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

/**
 * Catch-up subscription: polls the log after its checkpoint and applies each
 * event to every projection. Read models, checkpoint and log share the database,
 * so a batch and its checkpoint commit atomically: each event is applied exactly
 * once per projection, even across crashes. The checkpoint row is locked, so only
 * one instance projects at a time.
 */
public class Projector {

    static final String NAME = "wallet-read-model";

    private static final Logger log = LoggerFactory.getLogger(Projector.class);

    private final EventStore events;
    private final List<Projection> projections;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int batchSize;

    public Projector(EventStore events, List<Projection> projections, JdbcTemplate jdbc,
                     TransactionTemplate transactions, int batchSize) {
        this.events = events;
        this.projections = projections;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ledger.projector.poll-interval:100ms}")
    public void poll() {
        Integer applied;
        do {
            applied = transactions.execute(status -> {
                long checkpoint = jdbc.queryForObject(
                        "SELECT position FROM projection_checkpoints WHERE projection = ? FOR UPDATE", Long.class, NAME);
                List<RecordedEvent> batch = events.readAll(checkpoint, batchSize);
                for (RecordedEvent event : batch) {
                    projections.forEach(projection -> projection.apply(event));
                }
                if (!batch.isEmpty()) {
                    jdbc.update("UPDATE projection_checkpoints SET position = ? WHERE projection = ?",
                            batch.getLast().globalPosition(), NAME);
                }
                return batch.size();
            });
        } while (applied != null && applied == batchSize);
    }

    public long checkpoint() {
        return jdbc.queryForObject("SELECT position FROM projection_checkpoints WHERE projection = ?", Long.class, NAME);
    }

    /** Read-your-writes: wait until the read models include {@code position}. */
    public boolean awaitPosition(long position, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (checkpoint() < position) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Drops the read models and replays the whole log into them. */
    public void rebuild() {
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT position FROM projection_checkpoints WHERE projection = ? FOR UPDATE",
                    Long.class, NAME);
            projections.forEach(Projection::reset);
            jdbc.update("UPDATE projection_checkpoints SET position = 0 WHERE projection = ?", NAME);
        });
        log.info("Read models reset; replaying the event log");
        poll();
    }
}
