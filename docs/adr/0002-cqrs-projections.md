# 2. CQRS with asynchronous projections and read-your-writes

- Status: accepted
- Date: 2026-10-06

## Context

Replaying events for every read is too slow for listings, and the queries ("balance",
"statement") want different shapes than the aggregate.

## Decision

- Separate read models (`wallet_balances`, `wallet_transactions`), maintained by a
  **projector**: a catch-up subscription that polls the log after its checkpoint.
- Read models, checkpoint and log live in the same database, so a batch and its
  checkpoint commit together: each event is applied **exactly once** per projection.
  Projections are also written to be idempotent per position.
- The checkpoint row is locked (`FOR UPDATE`) during a batch, so several application
  instances can run without projecting concurrently.
- **Read-your-writes**: commands return the event's global position
  (`X-Event-Position`); queries accept `?consistentWith=<position>` and wait up to 2 s for
  the projection to reach it, otherwise `503` (retry).
- **Rebuild**: `POST /api/v1/admin/projections/rebuild` wipes the read models and replays
  the whole log, which fixes a corrupted read model or bootstraps a new one.

## Consequences

- Reads are single-row lookups. The write model stays small and focused on rules.
- Reads are **eventually consistent** by default. Clients that need their own write
  must pass the position.
- Projections must be maintained alongside event changes. A new event type that a
  projection ignores is a silent bug, so the projections switch exhaustively over the
  sealed event type and the compiler flags it.
