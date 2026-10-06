# 3. Never skip events: reading the log by committed transactions

- Status: accepted
- Date: 2026-10-06

## Context

The global position is an identity column. PostgreSQL hands out identity values when
rows are **inserted**, not when transactions **commit**:

```
tx A: INSERT ... → position 10      (still open)
tx B: INSERT ... → position 11, COMMIT
projector: reads > 9 → sees 11, checkpoint = 11
tx A: COMMIT                         → position 10 is never projected
```

This silently loses events in read models under concurrent writes. Common fixes are a
global lock on append (serializes all writers), or a delay window ("ignore events
younger than N ms", which is a guess).

## Decision

Each event records the id of the transaction that wrote it (`tx_id xid8 DEFAULT
pg_current_xact_id()`). The projector only reads events whose transaction is older than
every transaction still running:

```sql
WHERE global_position > :checkpoint
  AND tx_id < pg_snapshot_xmin(pg_current_snapshot())
```

While A is open, B's event (written by a younger transaction) is held back too. Once A
finishes, both become visible in position order.

## Consequences

- No event is ever skipped, writers never wait for each other, and nothing depends on timing.
- A long-running transaction delays projections (not writes) until it ends. Keep write
  transactions short.
- The integration test `readersNeverSkipPositionsOfTransactionsStillInFlight` reproduces
  the scenario with a real open transaction.
