package io.github.alef2509.ledger.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Facts that happened to a wallet. Events are the source of truth: the current
 * state is never stored, only derived by replaying them. They are immutable and
 * named in the past tense.
 */
public sealed interface WalletEvent {

    UUID walletId();

    record WalletOpened(UUID walletId, String owner, String currency) implements WalletEvent {
    }

    /** Schema v2. v1 stored {@code amountInCents} and had no currency; see the upcaster. */
    record FundsDeposited(UUID walletId, BigDecimal amount, String currency) implements WalletEvent {
    }

    record FundsWithdrawn(UUID walletId, BigDecimal amount) implements WalletEvent {
    }

    /** Money reserved for a pending payment (like a card pre-authorization). */
    record FundsHeld(UUID walletId, UUID holdId, BigDecimal amount, String reference) implements WalletEvent {
    }

    record HoldCaptured(UUID walletId, UUID holdId, BigDecimal amount) implements WalletEvent {
    }

    record HoldReleased(UUID walletId, UUID holdId, BigDecimal amount) implements WalletEvent {
    }

    record WalletFrozen(UUID walletId, String reason) implements WalletEvent {
    }
}
