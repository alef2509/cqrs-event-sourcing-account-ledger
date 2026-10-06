-- ============================ Write side: the event store ============================
CREATE TABLE events
(
    global_position BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, -- order across all streams
    stream_id       UUID         NOT NULL,
    stream_version  INTEGER      NOT NULL,                           -- 1, 2, 3... per stream
    event_type      VARCHAR(100) NOT NULL,
    schema_version  INTEGER      NOT NULL,
    payload         JSONB        NOT NULL,
    metadata        JSONB        NOT NULL DEFAULT '{}',
    occurred_at     TIMESTAMPTZ  NOT NULL,
    -- Id of the writing transaction; lets readers skip positions of transactions
    -- that have not committed yet (see ADR 3).
    tx_id           XID8         NOT NULL DEFAULT pg_current_xact_id(),
    -- Optimistic concurrency: two writers appending the same version, only one wins.
    CONSTRAINT uk_events_stream_version UNIQUE (stream_id, stream_version)
);

-- The log is append-only: history cannot be rewritten, not even by mistake.
CREATE FUNCTION reject_event_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'events are immutable: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER events_immutable_rows
    BEFORE UPDATE OR DELETE
    ON events
    FOR EACH ROW
EXECUTE FUNCTION reject_event_mutation();

CREATE TRIGGER events_immutable_table
    BEFORE TRUNCATE
    ON events
    FOR EACH STATEMENT
EXECUTE FUNCTION reject_event_mutation();

-- Snapshots are a cache of the aggregate state at some version; always rebuildable.
CREATE TABLE snapshots
(
    stream_id      UUID PRIMARY KEY,
    stream_version INTEGER     NOT NULL,
    schema_version INTEGER     NOT NULL,
    state          JSONB       NOT NULL,
    taken_at       TIMESTAMPTZ NOT NULL
);

-- ============================ Read side: projections ============================
CREATE TABLE projection_checkpoints
(
    projection VARCHAR(100) PRIMARY KEY,
    position   BIGINT       NOT NULL
);

INSERT INTO projection_checkpoints (projection, position)
VALUES ('wallet-read-model', 0);

CREATE TABLE wallet_balances
(
    wallet_id  UUID PRIMARY KEY,
    owner      VARCHAR(100)   NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    balance    NUMERIC(19, 2) NOT NULL,
    held       NUMERIC(19, 2) NOT NULL,
    available  NUMERIC(19, 2) NOT NULL,
    status     VARCHAR(20)    NOT NULL,
    version    INTEGER        NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL
);

CREATE TABLE wallet_transactions
(
    global_position BIGINT PRIMARY KEY,
    wallet_id       UUID           NOT NULL,
    type            VARCHAR(30)    NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL,
    description     VARCHAR(255)   NOT NULL,
    occurred_at     TIMESTAMPTZ    NOT NULL
);

CREATE INDEX ix_wallet_transactions_wallet ON wallet_transactions (wallet_id, global_position DESC);
