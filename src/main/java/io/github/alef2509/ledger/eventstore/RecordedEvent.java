package io.github.alef2509.ledger.eventstore;

import io.github.alef2509.ledger.domain.WalletEvent;

import java.time.Instant;
import java.util.UUID;

/** An event as stored: the domain event plus its position in its stream and in the global log. */
public record RecordedEvent(long globalPosition, UUID streamId, int streamVersion, WalletEvent event,
                            Instant occurredAt) {
}
