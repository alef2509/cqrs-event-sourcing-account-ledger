package io.github.alef2509.ledger.projection;

import io.github.alef2509.ledger.domain.WalletEvent.FundsDeposited;
import io.github.alef2509.ledger.domain.WalletEvent.FundsHeld;
import io.github.alef2509.ledger.domain.WalletEvent.FundsWithdrawn;
import io.github.alef2509.ledger.domain.WalletEvent.HoldCaptured;
import io.github.alef2509.ledger.domain.WalletEvent.HoldReleased;
import io.github.alef2509.ledger.domain.WalletEvent.WalletFrozen;
import io.github.alef2509.ledger.domain.WalletEvent.WalletOpened;
import io.github.alef2509.ledger.eventstore.RecordedEvent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * A customer-facing statement: one line per money movement, in plain words. A
 * second read model over the same events, shaped differently, is the point of CQRS.
 */
class WalletStatementProjection implements Projection {

    private final JdbcTemplate jdbc;

    WalletStatementProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void apply(RecordedEvent recorded) {
        switch (recorded.event()) {
            case FundsDeposited e -> line(recorded, "DEPOSIT", e.amount(), "Deposit");
            case FundsWithdrawn e -> line(recorded, "WITHDRAWAL", e.amount().negate(), "Withdrawal");
            case FundsHeld e -> line(recorded, "HOLD", BigDecimal.ZERO, "Pending payment " + e.reference() + " (" + e.amount() + " reserved)");
            case HoldCaptured e -> line(recorded, "PAYMENT", e.amount().negate(), "Payment captured");
            case HoldReleased e -> line(recorded, "HOLD_RELEASED", BigDecimal.ZERO, "Reservation of " + e.amount() + " released");
            case WalletOpened e -> {
            }
            case WalletFrozen e -> {
            }
        }
    }

    private void line(RecordedEvent recorded, String type, BigDecimal amount, String description) {
        jdbc.update("""
                INSERT INTO wallet_transactions (global_position, wallet_id, type, amount, description, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (global_position) DO NOTHING
                """, recorded.globalPosition(), recorded.streamId(), type, amount, description,
                Timestamp.from(recorded.occurredAt()));
    }

    @Override
    public void reset() {
        jdbc.update("DELETE FROM wallet_transactions");
    }
}
