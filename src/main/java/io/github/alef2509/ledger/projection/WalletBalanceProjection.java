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
import java.util.UUID;

/**
 * Denormalized balance per wallet, shaped for the "show my wallet" query: no
 * replay at read time, just one row. The {@code version < ?} guards make
 * re-applying an event harmless.
 */
class WalletBalanceProjection implements Projection {

    private final JdbcTemplate jdbc;

    WalletBalanceProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void apply(RecordedEvent recorded) {
        UUID id = recorded.streamId();
        int version = recorded.streamVersion();
        Timestamp at = Timestamp.from(recorded.occurredAt());
        switch (recorded.event()) {
            case WalletOpened e -> jdbc.update("""
                    INSERT INTO wallet_balances (wallet_id, owner, currency, balance, held, available, status, version, updated_at)
                    VALUES (?, ?, ?, 0, 0, 0, 'ACTIVE', ?, ?) ON CONFLICT (wallet_id) DO NOTHING
                    """, id, e.owner(), e.currency(), version, at);
            case FundsDeposited e -> move(id, version, at, e.amount(), BigDecimal.ZERO);
            case FundsWithdrawn e -> move(id, version, at, e.amount().negate(), BigDecimal.ZERO);
            case FundsHeld e -> move(id, version, at, BigDecimal.ZERO, e.amount());
            case HoldCaptured e -> move(id, version, at, e.amount().negate(), e.amount().negate());
            case HoldReleased e -> move(id, version, at, BigDecimal.ZERO, e.amount().negate());
            case WalletFrozen e -> jdbc.update("""
                    UPDATE wallet_balances SET status = 'FROZEN', version = ?, updated_at = ?
                    WHERE wallet_id = ? AND version < ?
                    """, version, at, id, version);
        }
    }

    private void move(UUID id, int version, Timestamp at, BigDecimal balanceDelta, BigDecimal heldDelta) {
        jdbc.update("""
                UPDATE wallet_balances
                SET balance = balance + ?, held = held + ?, available = available + ? - ?, version = ?, updated_at = ?
                WHERE wallet_id = ? AND version < ?
                """, balanceDelta, heldDelta, balanceDelta, heldDelta, version, at, id, version);
    }

    @Override
    public void reset() {
        jdbc.update("DELETE FROM wallet_balances");
    }
}
