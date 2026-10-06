package io.github.alef2509.ledger.eventstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alef2509.ledger.domain.WalletEvent;
import io.github.alef2509.ledger.domain.WalletEvent.FundsDeposited;
import io.github.alef2509.ledger.domain.WalletEvent.FundsHeld;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventSerializerTest {

    private final EventSerializer serializer = new EventSerializer(new ObjectMapper());
    private final UUID id = UUID.randomUUID();

    @Test
    void roundTripsCurrentEvents() {
        WalletEvent event = new FundsHeld(id, UUID.randomUUID(), new BigDecimal("12.50"), "order-9");

        WalletEvent copy = serializer.deserialize(serializer.typeOf(event), serializer.schemaVersionOf(event),
                serializer.serialize(event));

        assertThat(copy).isEqualTo(event);
    }

    @Test
    void keepsLargeAmountsExact() {
        WalletEvent event = new FundsDeposited(id, new BigDecimal("12345678901234567.89"), "EUR");

        assertThat(serializer.deserialize("FundsDeposited", 2, serializer.serialize(event))).isEqualTo(event);
    }

    @Test
    void upcastsVersion1DepositsStoredInCents() {
        String v1 = """
                {"walletId": "%s", "amountInCents": 1999}""".formatted(id);

        WalletEvent event = serializer.deserialize("FundsDeposited", 1, v1);

        assertThat(event).isEqualTo(new FundsDeposited(id, new BigDecimal("19.99"), "EUR"));
    }

    @Test
    void writesTheCurrentSchemaVersion() {
        assertThat(serializer.schemaVersionOf(new FundsDeposited(id, BigDecimal.TEN, "EUR"))).isEqualTo(2);
        assertThat(serializer.schemaVersionOf(new FundsHeld(id, id, BigDecimal.TEN, "x"))).isEqualTo(1);
    }

    @Test
    void refusesUnknownTypes() {
        assertThatThrownBy(() -> serializer.deserialize("AccountDeleted", 1, "{}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
