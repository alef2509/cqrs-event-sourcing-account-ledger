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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Given (past events) / when (command) / then (new events or rejection). */
class WalletTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID HOLD = UUID.randomUUID();

    private static final WalletOpened OPENED = new WalletOpened(ID, "Ada", "EUR");

    @Test
    void opensANewWallet() {
        then(given(), new OpenWallet(ID, "Ada", "EUR"), OPENED);
    }

    @Test
    void cannotOpenTwice() {
        rejected(given(OPENED), new OpenWallet(ID, "Ada", "EUR"), "already-exists");
    }

    @Test
    void depositsUseTheWalletCurrencyAndTwoDecimals() {
        then(given(OPENED), new Deposit(new BigDecimal("10")), new FundsDeposited(ID, new BigDecimal("10.00"), "EUR"));
    }

    @Test
    void rejectsInvalidAmounts() {
        rejected(given(OPENED), new Deposit(BigDecimal.ZERO), "invalid-amount");
        rejected(given(OPENED), new Deposit(new BigDecimal("1.001")), "invalid-amount");
    }

    @Test
    void cannotWithdrawHeldMoney() {
        WalletState state = given(OPENED, deposited("100.00"), held("80.00"));

        assertThat(state.available()).isEqualByComparingTo("20.00");
        rejected(state, new Withdraw(new BigDecimal("20.01")), "insufficient-funds");
        then(state, new Withdraw(new BigDecimal("20")), new FundsWithdrawn(ID, new BigDecimal("20.00")));
    }

    @Test
    void capturingAHoldSpendsTheMoney() {
        WalletState state = given(OPENED, deposited("100.00"), held("30.00"));

        then(state, new CaptureHold(HOLD), new HoldCaptured(ID, HOLD, new BigDecimal("30.00")));
        WalletState after = Wallet.evolve(state, new HoldCaptured(ID, HOLD, new BigDecimal("30.00")));
        assertThat(after.balance()).isEqualByComparingTo("70.00");
        assertThat(after.held()).isEqualByComparingTo("0");
    }

    @Test
    void releasingAHoldMakesTheMoneyAvailableAgain() {
        WalletState state = given(OPENED, deposited("100.00"), held("30.00"),
                new HoldReleased(ID, HOLD, new BigDecimal("30.00")));

        assertThat(state.available()).isEqualByComparingTo("100.00");
        rejected(state, new CaptureHold(HOLD), "hold-not-found");
    }

    @Test
    void placingTheSameHoldTwiceIsANoOp() {
        then(given(OPENED, deposited("100.00"), held("30.00")),
                new PlaceHold(HOLD, new BigDecimal("30.00"), "order-1"));
    }

    @Test
    void frozenWalletOnlyAllowsReleasingHolds() {
        WalletState state = given(OPENED, deposited("100.00"), held("30.00"), new WalletFrozen(ID, "fraud review"));

        rejected(state, new Deposit(BigDecimal.ONE), "wallet-frozen");
        rejected(state, new CaptureHold(HOLD), "wallet-frozen");
        then(state, new ReleaseHold(HOLD), new HoldReleased(ID, HOLD, new BigDecimal("30.00")));
        then(state, new Freeze("again")); // already frozen: nothing happens
    }

    @Test
    void stateCountsTheEventsAsItsVersion() {
        assertThat(given(OPENED, deposited("1.00"), deposited("2.00")).version()).isEqualTo(3);
    }

    private static FundsDeposited deposited(String amount) {
        return new FundsDeposited(ID, new BigDecimal(amount), "EUR");
    }

    private static FundsHeld held(String amount) {
        return new FundsHeld(ID, HOLD, new BigDecimal(amount), "order-1");
    }

    private static WalletState given(WalletEvent... history) {
        return Wallet.replay(List.of(history));
    }

    private static void then(WalletState state, WalletCommand command, WalletEvent... expected) {
        assertThat(Wallet.decide(state, command)).containsExactly(expected);
    }

    private static void rejected(WalletState state, WalletCommand command, String code) {
        assertThatThrownBy(() -> Wallet.decide(state, command))
                .isInstanceOfSatisfying(WalletRuleViolation.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
