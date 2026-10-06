package io.github.alef2509.ledger.projection;

import io.github.alef2509.ledger.eventstore.EventStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ProjectionConfiguration {

    @Bean
    Projector projector(EventStore events, JdbcTemplate jdbc, TransactionTemplate transactions,
                        @Value("${ledger.projector.batch-size:500}") int batchSize) {
        return new Projector(events, List.of(new WalletBalanceProjection(jdbc), new WalletStatementProjection(jdbc)),
                jdbc, transactions, batchSize);
    }
}
