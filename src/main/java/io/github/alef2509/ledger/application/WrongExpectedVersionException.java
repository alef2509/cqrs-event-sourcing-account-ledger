package io.github.alef2509.ledger.application;

import java.util.UUID;

public class WrongExpectedVersionException extends RuntimeException {

    public WrongExpectedVersionException(UUID walletId, int expected, int actual) {
        super("Wallet %s is at version %d, expected %d".formatted(walletId, actual, expected));
    }
}
