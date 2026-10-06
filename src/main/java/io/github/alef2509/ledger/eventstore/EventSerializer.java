package io.github.alef2509.ledger.eventstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.alef2509.ledger.domain.WalletEvent;
import io.github.alef2509.ledger.domain.WalletEvent.FundsDeposited;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Maps events to JSON and back, with <b>upcasting</b>: events written years ago with
 * an older schema are upgraded in memory when read, so the domain code only ever
 * sees the current shape. Stored events are never rewritten.
 */
public class EventSerializer {

    /** Current schema version per event type; types not listed are at version 1. */
    private static final Map<Class<?>, Integer> CURRENT_VERSIONS = Map.of(FundsDeposited.class, 2);

    /** Upcasters keyed by "Type@fromVersion", each producing the next version. */
    private static final Map<String, UnaryOperator<ObjectNode>> UPCASTERS = Map.of(
            // v1 stored integer cents and no currency: all v1 wallets were in euros.
            "FundsDeposited@1", v1 -> {
                ObjectNode v2 = v1.deepCopy();
                long cents = v2.remove("amountInCents").asLong();
                v2.put("amount", BigDecimal.valueOf(cents, 2));
                v2.put("currency", "EUR");
                return v2;
            });

    private static final Map<String, Class<? extends WalletEvent>> TYPES = Arrays
            .stream(WalletEvent.class.getPermittedSubclasses())
            .map(c -> c.asSubclass(WalletEvent.class))
            .collect(Collectors.toUnmodifiableMap(Class::getSimpleName, Function.identity()));

    private final ObjectMapper mapper;

    /**
     * Money must never pass through {@code double}: reading into a JSON tree (needed
     * for upcasting) would otherwise turn 12.50 into 12.5, or worse, lose precision.
     */
    public EventSerializer(ObjectMapper mapper) {
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    }

    public String typeOf(WalletEvent event) {
        return event.getClass().getSimpleName();
    }

    public int schemaVersionOf(WalletEvent event) {
        return CURRENT_VERSIONS.getOrDefault(event.getClass(), 1);
    }

    public String serialize(WalletEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize " + event, e);
        }
    }

    public WalletEvent deserialize(String type, int schemaVersion, String json) {
        Class<? extends WalletEvent> target = TYPES.get(type);
        if (target == null) {
            throw new IllegalStateException("Unknown event type " + type);
        }
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(json);
            int current = CURRENT_VERSIONS.getOrDefault(target, 1);
            for (int version = schemaVersion; version < current; version++) {
                UnaryOperator<ObjectNode> upcaster = UPCASTERS.get(type + "@" + version);
                if (upcaster == null) {
                    throw new IllegalStateException("No upcaster for %s v%d".formatted(type, version));
                }
                node = upcaster.apply(node);
            }
            return mapper.treeToValue(node, target);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt %s v%d event".formatted(type, schemaVersion), e);
        }
    }
}
