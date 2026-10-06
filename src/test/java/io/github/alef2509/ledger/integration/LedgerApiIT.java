package io.github.alef2509.ledger.integration;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.alef2509.ledger.TestcontainersConfiguration;
import io.github.alef2509.ledger.application.WalletCommandHandler;
import io.github.alef2509.ledger.domain.Wallet;
import io.github.alef2509.ledger.eventstore.EventStore;
import io.github.alef2509.ledger.eventstore.RecordedEvent;
import io.github.alef2509.ledger.eventstore.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LedgerApiIT {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SnapshotStore snapshots;
    @Autowired
    private EventStore events;
    @Autowired
    private WalletCommandHandler commands;

    @Test
    void holdCaptureAndReleaseAreReflectedInTheReadModels() {
        UUID id = open();
        post(id, "/deposits", Map.of("amount", 100));
        ResponseEntity<JsonNode> hold = post(id, "/holds", Map.of("amount", 30, "reference", "order-1"));
        String holdId = hold.getBody().get("holdId").asText();
        post(id, "/holds", Map.of("amount", 20, "reference", "order-2"));
        post(id, "/holds/" + holdId + "/capture", null);
        ResponseEntity<JsonNode> last = post(id, "/withdrawals", Map.of("amount", 10));

        JsonNode wallet = wallet(id, position(last));
        assertThat(wallet.get("balance").decimalValue()).isEqualByComparingTo("60.00"); // 100 - 30 - 10
        assertThat(wallet.get("held").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(wallet.get("available").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(wallet.get("version").asInt()).isEqualTo(6);

        JsonNode statement = rest.getForObject("/api/v1/wallets/{id}/statement?consistentWith={p}", JsonNode.class,
                id, position(last));
        assertThat(statement).extracting(line -> line.get("type").asText())
                .containsExactly("WITHDRAWAL", "PAYMENT", "HOLD", "HOLD", "DEPOSIT");
    }

    @Test
    void businessRulesAreEnforcedOnTheWriteSide() {
        UUID id = open();
        post(id, "/deposits", Map.of("amount", 10));

        ResponseEntity<JsonNode> overdraw = post(id, "/withdrawals", Map.of("amount", 10.01));

        assertThat(overdraw.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(overdraw.getBody().get("type").asText()).isEqualTo("urn:problem-type:ledger:insufficient-funds");
    }

    @Test
    void staleIfMatchIsRejected() {
        UUID id = open();
        post(id, "/deposits", Map.of("amount", 10)); // version 2

        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch("\"1\"");
        ResponseEntity<JsonNode> stale = rest.exchange("/api/v1/wallets/{id}/withdrawals", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 5), headers), JsonNode.class, id);

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
    }

    /**
     * 25 concurrent deposits to one wallet. Conflicts are retried; whatever the
     * outcome, the stream has no gaps and the balance matches the accepted deposits.
     */
    @Test
    void concurrentCommandsAreSerializedByOptimisticConcurrency() throws Exception {
        UUID id = open();
        List<Callable<HttpStatus>> deposits = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            deposits.add(() -> HttpStatus.valueOf(post(id, "/deposits", Map.of("amount", 1)).getStatusCode().value()));
        }

        List<HttpStatus> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<HttpStatus> f : pool.invokeAll(deposits)) {
                statuses.add(f.get());
            }
        }

        long accepted = statuses.stream().filter(HttpStatus.OK::equals).count();
        assertThat(statuses).allMatch(s -> s == HttpStatus.OK || s == HttpStatus.CONFLICT);
        assertThat(accepted).isGreaterThanOrEqualTo(20);
        assertThat(events.readStream(id)).extracting(RecordedEvent::streamVersion)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 1 + (int) accepted).boxed().toList());
        assertThat(commands.load(id).balance()).isEqualByComparingTo(BigDecimal.valueOf(accepted));
    }

    @Test
    void snapshotsAreTakenAndLoadingFromThemMatchesAFullReplay() {
        UUID id = open();
        for (int i = 0; i < 12; i++) {
            post(id, "/deposits", Map.of("amount", 2));
        }

        assertThat(snapshots.load(id)).hasValueSatisfying(s -> assertThat(s.version()).isEqualTo(10)); // every 5 events
        assertThat(commands.load(id))
                .isEqualTo(Wallet.replay(events.readStream(id).stream().map(RecordedEvent::event).toList()));
    }

    @Test
    void timeTravelShowsTheWalletAtAnyVersion() {
        UUID id = open();
        post(id, "/deposits", Map.of("amount", 50));
        post(id, "/withdrawals", Map.of("amount", 20));

        assertThat(rest.getForObject("/api/v1/wallets/{id}/history?version=2", JsonNode.class, id)
                .get("balance").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(rest.getForObject("/api/v1/wallets/{id}/history?version=3", JsonNode.class, id)
                .get("balance").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(rest.getForObject("/api/v1/wallets/{id}/events", JsonNode.class, id))
                .extracting(e -> e.get("type").asText())
                .containsExactly("WalletOpened", "FundsDeposited", "FundsWithdrawn");
    }

    @Test
    void rebuildingTheReadModelsFromTheLogGivesTheSameResult() {
        UUID id = open();
        post(id, "/deposits", Map.of("amount", 70));
        ResponseEntity<JsonNode> last = post(id, "/holds", Map.of("amount", 15, "reference", "order-7"));
        JsonNode before = wallet(id, position(last));
        jdbc.update("UPDATE wallet_balances SET balance = 0 WHERE wallet_id = ?", id); // corrupt the read model

        rest.postForObject("/api/v1/admin/projections/rebuild", null, JsonNode.class);

        assertThat(wallet(id, position(last))).isEqualTo(before);
    }

    private UUID open() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/v1/wallets",
                Map.of("owner", "Ada", "currency", "EUR"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("walletId").asText());
    }

    private ResponseEntity<JsonNode> post(UUID id, String path, Map<String, Object> body) {
        return rest.postForEntity("/api/v1/wallets/" + id + path, body, JsonNode.class);
    }

    private JsonNode wallet(UUID id, long position) {
        return rest.getForObject("/api/v1/wallets/{id}?consistentWith={p}", JsonNode.class, id, position);
    }

    private static long position(ResponseEntity<JsonNode> response) {
        return Long.parseLong(response.getHeaders().getFirst("X-Event-Position"));
    }
}
