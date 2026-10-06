package io.github.alef2509.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alef2509.ledger.application.WalletCommandHandler;
import io.github.alef2509.ledger.eventstore.EventSerializer;
import io.github.alef2509.ledger.eventstore.EventStore;
import io.github.alef2509.ledger.eventstore.SnapshotStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class LedgerConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EventStore eventStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper, Clock clock) {
        return new EventStore(jdbc, transactions, new EventSerializer(mapper), clock);
    }

    @Bean
    SnapshotStore snapshotStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        return new SnapshotStore(jdbc, mapper, clock);
    }

    @Bean
    WalletCommandHandler walletCommandHandler(EventStore events, SnapshotStore snapshots,
                                              @Value("${ledger.snapshot-every:50}") int snapshotEvery) {
        return new WalletCommandHandler(events, snapshots, snapshotEvery);
    }
}
