package io.github.alef2509.ledger.projection;

import io.github.alef2509.ledger.eventstore.RecordedEvent;

/** A read model kept up to date from the event log. Must be idempotent per event position. */
public interface Projection {

    void apply(RecordedEvent event);

    /** Removes all data, before a rebuild from position 0. */
    void reset();
}
