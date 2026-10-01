ALTER TABLE products ADD COLUMN deleted_at TIMESTAMPTZ NULL;
CREATE INDEX idx_products_active_shop ON products (shop_id) WHERE deleted_at IS NULL;
