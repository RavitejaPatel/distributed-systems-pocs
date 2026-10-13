-- ===== Database per service =====
CREATE DATABASE IF NOT EXISTS orders_db;
CREATE DATABASE IF NOT EXISTS payments_db;
CREATE DATABASE IF NOT EXISTS inventory_db;

-- ===== Order Service (the saga orchestrator) =====
CREATE TABLE IF NOT EXISTS orders_db.orders (
  order_id      VARCHAR(40) PRIMARY KEY,
  customer_id   VARCHAR(40) NOT NULL,
  sku           VARCHAR(40) NOT NULL,
  qty           INT         NOT NULL,
  amount_cents  INT         NOT NULL,
  status        VARCHAR(20) NOT NULL,   -- PENDING / CONFIRMED / CANCELLED
  saga_step     VARCHAR(30) NOT NULL,   -- where the saga is (Goal 6)
  updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- ===== Payment Service =====
CREATE TABLE IF NOT EXISTS payments_db.payments (
  order_id      VARCHAR(40) PRIMARY KEY,  -- one payment per order = idempotency key (POC 01!)
  amount_cents  INT         NOT NULL,
  status        VARCHAR(20) NOT NULL,     -- CHARGED / DECLINED / REFUNDED
  updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- ===== Inventory Service =====
CREATE TABLE IF NOT EXISTS inventory_db.stock (
  sku        VARCHAR(40) PRIMARY KEY,
  available  INT NOT NULL
);
CREATE TABLE IF NOT EXISTS inventory_db.reservations (
  order_id   VARCHAR(40) PRIMARY KEY,     -- one reservation per order = idempotency key
  sku        VARCHAR(40) NOT NULL,
  qty        INT         NOT NULL,
  status     VARCHAR(20) NOT NULL         -- RESERVED / RELEASED
);

-- Seed stock: sku-2 is OUT OF STOCK on purpose (to trigger compensation in Goal 5)
INSERT INTO inventory_db.stock (sku, available) VALUES
  ('sku-1', 10), ('sku-2', 0), ('sku-3', 3)
ON DUPLICATE KEY UPDATE available = VALUES(available);