package io.github.alef2509.ledger.domain;

/** A command that the wallet's current state does not allow. */
public class WalletRuleViolation extends RuntimeException {

    private final String code;

    public WalletRuleViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
