package io.github.alef2509.ledger;

import org.springframework.boot.SpringApplication;

/** {@code ./mvnw spring-boot:test-run}: the app against a throwaway PostgreSQL. */
public class TestLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.from(LedgerApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
