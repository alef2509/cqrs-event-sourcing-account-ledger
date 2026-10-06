package io.github.alef2509.ledger.web;

import io.github.alef2509.ledger.application.WalletCommandHandler;
import io.github.alef2509.ledger.application.WalletCommandHandler.Result;
import io.github.alef2509.ledger.domain.WalletCommand;
import io.github.alef2509.ledger.domain.WalletCommand.CaptureHold;
import io.github.alef2509.ledger.domain.WalletCommand.Deposit;
import io.github.alef2509.ledger.domain.WalletCommand.Freeze;
import io.github.alef2509.ledger.domain.WalletCommand.OpenWallet;
import io.github.alef2509.ledger.domain.WalletCommand.PlaceHold;
import io.github.alef2509.ledger.domain.WalletCommand.ReleaseHold;
import io.github.alef2509.ledger.domain.WalletCommand.Withdraw;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Write side. Every response carries the new stream version (ETag) and the
 * global position of the last event (X-Event-Position), which a client can pass
 * to the query side to read its own writes.
 */
@RestController
@RequestMapping("/api/v1/wallets")
class WalletCommandController {

    static final String POSITION_HEADER = "X-Event-Position";

    record OpenRequest(@NotBlank @Size(max = 100) String owner,
                       @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    record AmountRequest(@NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }

    record HoldRequest(@NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
                       @NotBlank @Size(max = 100) String reference) {
    }

    record FreezeRequest(@NotBlank @Size(max = 255) String reason) {
    }

    record CommandResponse(UUID walletId, int version, long position) {
    }

    record HoldResponse(UUID walletId, UUID holdId, int version, long position) {
    }

    private final WalletCommandHandler handler;

    WalletCommandController(WalletCommandHandler handler) {
        this.handler = handler;
    }

    @PostMapping
    ResponseEntity<CommandResponse> open(@Valid @RequestBody OpenRequest request) {
        UUID id = UUID.randomUUID();
        Result result = handler.handle(id, new OpenWallet(id, request.owner(), request.currency()), 0, "{}");
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                        .buildAndExpand(id).toUri())
                .headers(headers(result))
                .body(new CommandResponse(id, result.version(), result.position()));
    }

    @PostMapping("/{id}/deposits")
    ResponseEntity<CommandResponse> deposit(@PathVariable UUID id, @Valid @RequestBody AmountRequest request,
                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return execute(id, new Deposit(request.amount()), ifMatch);
    }

    @PostMapping("/{id}/withdrawals")
    ResponseEntity<CommandResponse> withdraw(@PathVariable UUID id, @Valid @RequestBody AmountRequest request,
                                             @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return execute(id, new Withdraw(request.amount()), ifMatch);
    }

    @PostMapping("/{id}/holds")
    ResponseEntity<HoldResponse> hold(@PathVariable UUID id, @Valid @RequestBody HoldRequest request) {
        UUID holdId = UUID.randomUUID();
        Result result = handler.handle(id, new PlaceHold(holdId, request.amount(), request.reference()), null, "{}");
        return ResponseEntity.ok().headers(headers(result))
                .body(new HoldResponse(id, holdId, result.version(), result.position()));
    }

    @PostMapping("/{id}/holds/{holdId}/capture")
    ResponseEntity<CommandResponse> capture(@PathVariable UUID id, @PathVariable UUID holdId) {
        return execute(id, new CaptureHold(holdId), null);
    }

    @PostMapping("/{id}/holds/{holdId}/release")
    ResponseEntity<CommandResponse> release(@PathVariable UUID id, @PathVariable UUID holdId) {
        return execute(id, new ReleaseHold(holdId), null);
    }

    @PostMapping("/{id}/freeze")
    ResponseEntity<CommandResponse> freeze(@PathVariable UUID id, @Valid @RequestBody FreezeRequest request) {
        return execute(id, new Freeze(request.reason()), null);
    }

    private ResponseEntity<CommandResponse> execute(UUID id, WalletCommand command, String ifMatch) {
        Result result = handler.handle(id, command, ETags.parse(ifMatch), "{}");
        return ResponseEntity.ok().headers(headers(result))
                .body(new CommandResponse(id, result.version(), result.position()));
    }

    private static HttpHeaders headers(Result result) {
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(ETags.of(result.version()));
        headers.set(POSITION_HEADER, Long.toString(result.position()));
        return headers;
    }
}
