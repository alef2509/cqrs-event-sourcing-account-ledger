# 1. Event Sourcing for the wallet ledger

- Status: accepted
- Date: 2026-10-06

## Context

A wallet holds money. Regulators, support and customers ask *how* a balance came
to be, not only what it is. In a CRUD model the history is a side table that can
drift from the balance it is supposed to explain.

## Decision

Store **events**, not state. The `events` table is the source of truth; a wallet's
state is `events.reduce(NONE, evolve)`.

- The aggregate is two pure functions ([`Wallet`](../../src/main/java/io/github/alef2509/ledger/domain/Wallet.java)):
  `decide(state, command) -> events` and `evolve(state, event) -> state`, tested in
  *given events / when command / then events* form.
- The table is **append-only**, enforced by triggers that reject `UPDATE`, `DELETE` and `TRUNCATE`.
- **Optimistic concurrency**: a unique `(stream_id, stream_version)` makes the second of
  two concurrent appends fail. The handler reloads and retries with jittered backoff;
  clients can pin a version with `If-Match` (412 when stale).
- **Snapshots** every 50 events bound load time. They are a disposable cache, versioned
  by state schema, and ignored when the schema changes.
- **Upcasting**: events are never migrated in place. Old schema versions are upgraded in
  memory when read (`FundsDeposited` v1 stored integer cents).

## Consequences

- Complete, tamper-resistant audit trail and time travel (`/history?version=n`) for free.
- New read models can be built later from the full history.
- More moving parts than CRUD: projections, eventual consistency, event versioning.
  Worth it for ledgers, carts and workflows; overkill for reference data.
- Event schemas are a public contract with the future: they need the same care as an API.
