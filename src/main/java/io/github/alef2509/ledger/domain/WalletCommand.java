package io.github.alef2509.ledger.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Intentions. A command may be rejected; if accepted it produces one or more events. */
public sealed interface WalletCommand {

    record OpenWallet(UUID walletId, String owner, String currency) implements WalletCommand {
    }

    record Deposit(BigDecimal amount) implements WalletCommand {
    }

    record Withdraw(BigDecimal amount) implements WalletCommand {
    }

    record PlaceHold(UUID holdId, BigDecimal amount, String reference) implements WalletCommand {
    }

    record CaptureHold(UUID holdId) implements WalletCommand {
    }

    record ReleaseHold(UUID holdId) implements WalletCommand {
    }

    record Freeze(String reason) implements WalletCommand {
    }
}
