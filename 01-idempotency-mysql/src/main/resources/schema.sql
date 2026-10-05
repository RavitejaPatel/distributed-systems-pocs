-- Goal 1 schema. Safe to re-run: it drops and recreates the tables.

DROP TABLE IF EXISTS idempotency_keys;
DROP TABLE IF EXISTS payments;

-- Every row here = money moved. A duplicate row = customer charged twice.
CREATE TABLE payments (
    payment_id    VARCHAR(40)  PRIMARY KEY,
    customer_id   VARCHAR(40)  NOT NULL,
    amount_cents  INT          NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

-- One row per idempotency key. The PRIMARY KEY is the real guard:
-- MySQL refuses a second row with the same key, even if two requests arrive at the same moment.
CREATE TABLE idempotency_keys (
    idempotency_key  VARCHAR(64)  PRIMARY KEY,
    request_hash     CHAR(64)     NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    payment_id       VARCHAR(40)  NULL,
    created_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at       TIMESTAMP(3) NOT NULL
);
