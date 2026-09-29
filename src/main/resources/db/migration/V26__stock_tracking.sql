-- Deploy with checkout/product writers stopped. Flyway runs this atomically on PostgreSQL.
-- Historical sales are a snapshot, never an invented opening or lifetime total.
LOCK TABLE products, pos_receipts, pos_receipt_items, restaurant_orders,
    restaurant_order_items, restaurant_payments IN SHARE ROW EXCLUSIVE MODE;

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM products WHERE product_quantity_amount IS NULL
               OR product_quantity_amount < 0) THEN
        RAISE EXCEPTION 'Stock tracking requires reviewed nonnegative current balances';
    END IF;
END $$;

ALTER TABLE products
    ADD COLUMN opening_balance numeric(19,2),
    ADD COLUMN total_stock numeric(19,2),
    ADD COLUMN sold_quantity numeric(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN stock_correction numeric(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN historical_sold_quantity numeric(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN stock_tracking_started_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    ADD COLUMN stock_tracking_basis varchar(32) NOT NULL DEFAULT 'OPENING_BALANCE';

UPDATE products p SET opening_balance = p.product_quantity_amount,
    total_stock = p.product_quantity_amount,
    historical_sold_quantity =
        COALESCE((SELECT SUM(i.qty) FROM pos_receipt_items i
            JOIN pos_receipts r ON r.id = i.receipt_id
            WHERE i.product_id = p.id::text AND r.shop_id = p.shop_id
              AND r.status = 'COMPLETED'), 0)
        + COALESCE((SELECT SUM(i.quantity) FROM restaurant_order_items i
            JOIN restaurant_orders o ON o.id = i.order_id
            WHERE i.product_id = p.id AND o.shop_id = p.shop_id AND o.status = 'PAID'
              AND EXISTS (SELECT 1 FROM restaurant_payments pay
                  WHERE pay.order_id = o.id AND pay.shop_id = o.shop_id
                    AND pay.status = 'PAID')), 0);

ALTER TABLE products
    ALTER COLUMN opening_balance SET NOT NULL,
    ALTER COLUMN total_stock SET NOT NULL,
    ADD CONSTRAINT chk_product_stock_balance CHECK (
        product_quantity_amount IS NOT NULL AND product_quantity_amount >= 0
        AND opening_balance >= 0 AND total_stock >= opening_balance AND sold_quantity >= 0
        AND product_quantity_amount = total_stock - sold_quantity + stock_correction),
    ADD CONSTRAINT chk_stock_tracking_basis CHECK (stock_tracking_basis IN ('OPENING_BALANCE','FROM_CREATION'));

CREATE TABLE stock_movements (
    id bigserial PRIMARY KEY,
    product_id bigint NOT NULL REFERENCES products(id),
    operation varchar(32) NOT NULL CHECK (operation IN ('OPENING_BALANCE','ADD_STOCK','STOCK_CORRECTION','SALE')),
    quantity_delta numeric(19,2) NOT NULL,
    balance_before numeric(19,2) NOT NULL,
    balance_after numeric(19,2) NOT NULL,
    reference varchar(160) NOT NULL,
    reason varchar(500),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (product_id, reference),
    CHECK (balance_after = balance_before + quantity_delta AND balance_after >= 0),
    CHECK ((operation = 'SALE' AND quantity_delta < 0)
        OR (operation = 'ADD_STOCK' AND quantity_delta > 0)
        OR (operation = 'OPENING_BALANCE' AND quantity_delta >= 0)
        OR operation = 'STOCK_CORRECTION')
);
CREATE INDEX idx_stock_movements_product_time ON stock_movements(product_id, created_at, id);
INSERT INTO stock_movements(product_id, operation, quantity_delta, balance_before, balance_after, reference, reason)
SELECT id, 'OPENING_BALANCE', product_quantity_amount, 0, product_quantity_amount,
    'OPENING', 'Balance at tracking start; earlier replenishments unknown' FROM products;

-- One business request per shop, shared across POS and restaurant channels.
CREATE TABLE stock_requests (
    id bigserial PRIMARY KEY,
    shop_id bigint NOT NULL,
    request_key varchar(100) NOT NULL,
    channel varchar(64) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    response_json text NOT NULL,
    UNIQUE(shop_id, request_key)
);
