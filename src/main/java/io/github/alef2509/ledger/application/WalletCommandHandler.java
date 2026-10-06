package io.github.alef2509.ledger.application;

import io.github.alef2509.ledger.domain.Wallet;
import io.github.alef2509.ledger.domain.WalletCommand;
import io.github.alef2509.ledger.domain.WalletEvent;
import io.github.alef2509.ledger.domain.WalletState;
import io.github.alef2509.ledger.eventstore.ConcurrencyException;
import io.github.alef2509.ledger.eventstore.EventStore;
import io.github.alef2509.ledger.eventstore.RecordedEvent;
import io.github.alef2509.ledger.eventstore.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Command side: load the wallet (snapshot + newer events), decide, append with
 * the version that was read. On a concurrent append the whole cycle is retried
 * against fresh state with a jittered backoff, unless the client pinned a version
 * (If-Match). Under extreme contention a command can still give up with 409.
 */
public class WalletCommandHandler {

    public record Result(UUID walletId, int version, long position) {
    }

    private static final Logger log = LoggerFactory.getLogger(WalletCommandHandler.class);
    private static final int MAX_ATTEMPTS = 10;

    private final EventStore events;
    private final SnapshotStore snapshots;
    private final int snapshotEvery;

    public WalletCommandHandler(EventStore events, SnapshotStore snapshots, int snapshotEvery) {
        this.events = events;
        this.snapshots = snapshots;
        this.snapshotEvery = snapshotEvery;
    }

    /**
     * @param expectedVersion the version the client last saw, or {@code null} for "latest"
     */
    public Result handle(UUID walletId, WalletCommand command, Integer expectedVersion, String metadataJson) {
        for (int attempt = 1; ; attempt++) {
            WalletState state = load(walletId);
            if (expectedVersion != null && state.version() != expectedVersion) {
                throw new WrongExpectedVersionException(walletId, expectedVersion, state.version());
            }
            List<WalletEvent> decided = Wallet.decide(state, command);
            try {
                EventStore.AppendResult appended = events.append(walletId, state.version(), decided, metadataJson);
                maybeSnapshot(state, decided, appended.newVersion());
                return new Result(walletId, appended.newVersion(), appended.lastPosition());
            } catch (ConcurrencyException e) {
                if (expectedVersion != null || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                log.debug("Concurrent append on {}, retrying ({}/{})", walletId, attempt, MAX_ATTEMPTS);
                backOff(attempt);
            }
        }
    }

    /** Random, growing pause so competing writers do not collide again in lockstep. */
    private static void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 5L * attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying", e);
        }
    }

    public WalletState load(UUID walletId) {
        WalletState state = snapshots.load(walletId).orElse(WalletState.NONE);
        for (RecordedEvent recorded : events.readStream(walletId, state.version(), Integer.MAX_VALUE)) {
            state = Wallet.evolve(state, recorded.event());
        }
        return state;
    }

    /** Time travel: the wallet exactly as it was at {@code version}, from events only. */
    public WalletState loadAt(UUID walletId, int version) {
        return Wallet.replay(events.readStream(walletId, 0, version).stream().map(RecordedEvent::event).toList());
    }

    private void maybeSnapshot(WalletState before, List<WalletEvent> decided, int newVersion) {
        if (newVersion / snapshotEvery > before.version() / snapshotEvery) {
            WalletState after = before;
            for (WalletEvent event : decided) {
                after = Wallet.evolve(after, event);
            }
            snapshots.save(after);
        }
    }
}
