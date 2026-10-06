package io.github.alef2509.ledger.domain;

import io.github.alef2509.ledger.domain.WalletCommand.CaptureHold;
import io.github.alef2509.ledger.domain.WalletCommand.Deposit;
import io.github.alef2509.ledger.domain.WalletCommand.Freeze;
import io.github.alef2509.ledger.domain.WalletCommand.OpenWallet;
import io.github.alef2509.ledger.domain.WalletCommand.PlaceHold;
import io.github.alef2509.ledger.domain.WalletCommand.ReleaseHold;
import io.github.alef2509.ledger.domain.WalletCommand.Withdraw;
import io.github.alef2509.ledger.domain.WalletEvent.FundsDeposited;
import io.github.alef2509.ledger.domain.WalletEvent.FundsHeld;
import io.github.alef2509.ledger.domain.WalletEvent.FundsWithdrawn;
import io.github.alef2509.ledger.domain.WalletEvent.HoldCaptured;
import io.github.alef2509.ledger.domain.WalletEvent.HoldReleased;
import io.github.alef2509.ledger.domain.WalletEvent.WalletFrozen;
import io.github.alef2509.ledger.domain.WalletEvent.WalletOpened;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wallet aggregate as two pure functions, the functional style of event sourcing:
 *
 * <ul>
 *   <li>{@link #decide}: given the current state and a command, which events happen
 *       (or which business rule rejects it). No side effects.</li>
 *   <li>{@link #evolve}: given a state and an event, the next state. Never fails:
 *       events are facts that already happened.</li>
 * </ul>
 * Loading a wallet is just {@code events.reduce(NONE, evolve)}.
 */
public final class Wallet {

    private Wallet() {
    }

    public static List<WalletEvent> decide(WalletState state, WalletCommand command) {
        if (command instanceof OpenWallet open) {
            if (state.exists()) {
                throw new WalletRuleViolation("already-exists", "Wallet %s already exists".formatted(open.walletId()));
            }
            return List.of(new WalletOpened(open.walletId(), open.owner(), open.currency()));
        }
        if (!state.exists()) {
            throw new WalletRuleViolation("not-found", "Wallet does not exist");
        }
        UUID id = state.id();
        return switch (command) {
            case OpenWallet open -> throw new IllegalStateException("handled above");
            case Deposit deposit -> {
                requireActive(state);
                yield List.of(new FundsDeposited(id, positive(deposit.amount()), state.currency()));
            }
            case Withdraw withdraw -> {
                requireActive(state);
                yield List.of(new FundsWithdrawn(id, requireAvailable(state, positive(withdraw.amount()))));
            }
            case PlaceHold hold -> {
                requireActive(state);
                if (state.holds().containsKey(hold.holdId())) {
                    yield List.of(); // idempotent: the same hold placed twice is a no-op
                }
                yield List.of(new FundsHeld(id, hold.holdId(), requireAvailable(state, positive(hold.amount())),
                        hold.reference()));
            }
            case CaptureHold capture -> {
                requireActive(state);
                yield List.of(new HoldCaptured(id, capture.holdId(), requireHold(state, capture.holdId())));
            }
            // Releasing gives money back to the customer, so it is allowed on a frozen wallet.
            case ReleaseHold release -> List.of(new HoldReleased(id, release.holdId(),
                    requireHold(state, release.holdId())));
            case Freeze freeze -> state.frozen() ? List.of() : List.of(new WalletFrozen(id, freeze.reason()));
        };
    }

    public static WalletState evolve(WalletState state, WalletEvent event) {
        int version = state.version() + 1;
        return switch (event) {
            case WalletOpened e -> new WalletState(e.walletId(), e.owner(), e.currency(), BigDecimal.ZERO.setScale(2),
                    Map.of(), false, version);
            case FundsDeposited e -> with(state, state.balance().add(e.amount()), state.holds(), state.frozen(), version);
            case FundsWithdrawn e -> with(state, state.balance().subtract(e.amount()), state.holds(), state.frozen(),
                    version);
            case FundsHeld e -> with(state, state.balance(), put(state.holds(), e.holdId(), e.amount()), state.frozen(),
                    version);
            case HoldCaptured e -> with(state, state.balance().subtract(e.amount()), remove(state.holds(), e.holdId()),
                    state.frozen(), version);
            case HoldReleased e -> with(state, state.balance(), remove(state.holds(), e.holdId()), state.frozen(),
                    version);
            case WalletFrozen e -> with(state, state.balance(), state.holds(), true, version);
        };
    }

    public static WalletState replay(List<? extends WalletEvent> events) {
        WalletState state = WalletState.NONE;
        for (WalletEvent event : events) {
            state = evolve(state, event);
        }
        return state;
    }

    private static WalletState with(WalletState s, BigDecimal balance, Map<UUID, BigDecimal> holds, boolean frozen,
                                    int version) {
        return new WalletState(s.id(), s.owner(), s.currency(), balance, holds, frozen, version);
    }

    private static Map<UUID, BigDecimal> put(Map<UUID, BigDecimal> holds, UUID id, BigDecimal amount) {
        Map<UUID, BigDecimal> copy = new HashMap<>(holds);
        copy.put(id, amount);
        return copy;
    }

    private static Map<UUID, BigDecimal> remove(Map<UUID, BigDecimal> holds, UUID id) {
        Map<UUID, BigDecimal> copy = new HashMap<>(holds);
        copy.remove(id);
        return copy;
    }

    private static void requireActive(WalletState state) {
        if (state.frozen()) {
            throw new WalletRuleViolation("wallet-frozen", "Wallet %s is frozen".formatted(state.id()));
        }
    }

    private static BigDecimal positive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw new WalletRuleViolation("invalid-amount", "Amount must be positive with at most 2 decimals");
        }
        return amount.setScale(2);
    }

    private static BigDecimal requireAvailable(WalletState state, BigDecimal amount) {
        if (state.available().compareTo(amount) < 0) {
            throw new WalletRuleViolation("insufficient-funds",
                    "Available %s %s is less than %s".formatted(state.available(), state.currency(), amount));
        }
        return amount;
    }

    private static BigDecimal requireHold(WalletState state, UUID holdId) {
        BigDecimal amount = state.holds().get(holdId);
        if (amount == null) {
            throw new WalletRuleViolation("hold-not-found", "Hold %s is not active".formatted(holdId));
        }
        return amount;
    }
}
