package io.github.alef2509.ledger.eventstore;

import java.util.UUID;

/** Another writer appended to the stream since it was read. */
public class ConcurrencyException extends RuntimeException {

    public ConcurrencyException(UUID streamId, int expectedVersion) {
        super("Stream %s was modified concurrently (expected version %d)".formatted(streamId, expectedVersion));
    }
}
