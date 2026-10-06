package io.github.alef2509.ledger.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * State of a wallet as derived from its events. {@code balance} includes held
 * money; {@code available()} is what can still be spent.
 *
 * @param version number of events applied (the stream version), 0 for a wallet that does not exist yet
 */
public record WalletState(UUID id, String owner, String currency, BigDecimal balance, Map<UUID, BigDecimal> holds,
                          boolean frozen, int version) {

    public static final WalletState NONE = new WalletState(null, null, null, BigDecimal.ZERO, Map.of(), false, 0);

    public WalletState {
        holds = Map.copyOf(holds);
    }

    public boolean exists() {
        return id != null;
    }

    public BigDecimal held() {
        return holds.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal available() {
        return balance.subtract(held());
    }
}
