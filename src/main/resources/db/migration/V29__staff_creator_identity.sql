-- Preserve all historical USER creator IDs; STAFF has a separate identity namespace.
ALTER TABLE products ADD COLUMN created_by_staff_id BIGINT;
ALTER TABLE pos_receipts ADD COLUMN created_by_staff_id BIGINT;
CREATE INDEX idx_receipts_shop_staff_creator ON pos_receipts (shop_id, created_by_staff_id);
ALTER TABLE products ADD CONSTRAINT products_creator_identity_check
    CHECK (created_by_user_id IS NULL OR created_by_staff_id IS NULL);
ALTER TABLE pos_receipts ADD CONSTRAINT receipts_creator_identity_check
    CHECK (created_by_user_id IS NULL OR created_by_staff_id IS NULL);
