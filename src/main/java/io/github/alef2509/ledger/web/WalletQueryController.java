package io.github.alef2509.ledger.web;

import io.github.alef2509.ledger.application.WalletCommandHandler;
import io.github.alef2509.ledger.domain.WalletState;
import io.github.alef2509.ledger.eventstore.EventStore;
import io.github.alef2509.ledger.eventstore.RecordedEvent;
import io.github.alef2509.ledger.projection.Projector;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side. Wallet and statement come from the projections (eventually
 * consistent, cheap). Events and point-in-time state come from the event store.
 */
@RestController
class WalletQueryController {

    record WalletView(UUID walletId, String owner, String currency, BigDecimal balance, BigDecimal held,
                      BigDecimal available, String status, int version) {
    }

    record StatementLine(long position, String type, BigDecimal amount, String description, Instant at) {
    }

    record EventView(long position, int version, String type, Object data, Instant occurredAt) {
    }

    private final JdbcTemplate jdbc;
    private final Projector projector;
    private final EventStore events;
    private final WalletCommandHandler commands;

    WalletQueryController(JdbcTemplate jdbc, Projector projector, EventStore events, WalletCommandHandler commands) {
        this.jdbc = jdbc;
        this.projector = projector;
        this.events = events;
        this.commands = commands;
    }

    /**
     * @param consistentWith a position returned by a command (X-Event-Position); the
     *                       query waits up to 2 s for the read model to include it
     */
    @GetMapping("/api/v1/wallets/{id}")
    ResponseEntity<WalletView> wallet(@PathVariable UUID id,
                                      @RequestParam(required = false) Long consistentWith) {
        awaitIfRequested(consistentWith);
        return jdbc.query("SELECT * FROM wallet_balances WHERE wallet_id = ?", (rs, i) -> new WalletView(
                        rs.getObject("wallet_id", UUID.class), rs.getString("owner"), rs.getString("currency"),
                        rs.getBigDecimal("balance"), rs.getBigDecimal("held"), rs.getBigDecimal("available"),
                        rs.getString("status"), rs.getInt("version")), id)
                .stream().findFirst()
                .map(view -> ResponseEntity.ok().eTag(ETags.of(view.version())).body(view))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet " + id + " not found"));
    }

    @GetMapping("/api/v1/wallets/{id}/statement")
    List<StatementLine> statement(@PathVariable UUID id,
                                  @RequestParam(required = false) Long consistentWith,
                                  @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        awaitIfRequested(consistentWith);
        return jdbc.query("""
                        SELECT * FROM wallet_transactions WHERE wallet_id = ?
                        ORDER BY global_position DESC LIMIT ?
                        """,
                (rs, i) -> new StatementLine(rs.getLong("global_position"), rs.getString("type"),
                        rs.getBigDecimal("amount"), rs.getString("description"),
                        rs.getTimestamp("occurred_at").toInstant()), id, limit);
    }

    /** The full, immutable history of the wallet: the audit trail comes for free. */
    @GetMapping("/api/v1/wallets/{id}/events")
    List<EventView> events(@PathVariable UUID id) {
        List<RecordedEvent> stream = events.readStream(id);
        if (stream.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet " + id + " not found");
        }
        return stream.stream().map(e -> new EventView(e.globalPosition(), e.streamVersion(),
                e.event().getClass().getSimpleName(), e.event(), e.occurredAt())).toList();
    }

    /** Time travel: the wallet as it was right after event {@code version}. */
    @GetMapping("/api/v1/wallets/{id}/history")
    Map<String, Object> asOf(@PathVariable UUID id, @RequestParam @Min(1) int version) {
        WalletState state = commands.loadAt(id, version);
        if (!state.exists() || state.version() < version) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet " + id + " has no version " + version);
        }
        return Map.of("walletId", state.id(), "version", state.version(), "balance", state.balance(),
                "held", state.held(), "available", state.available(), "frozen", state.frozen());
    }

    @PostMapping("/api/v1/admin/projections/rebuild")
    Map<String, Object> rebuild() {
        projector.rebuild();
        return Map.of("checkpoint", projector.checkpoint());
    }

    private void awaitIfRequested(Long position) {
        if (position != null && !projector.awaitPosition(position, Duration.ofSeconds(2))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Read model has not caught up with position " + position + " yet, retry");
        }
    }
}
