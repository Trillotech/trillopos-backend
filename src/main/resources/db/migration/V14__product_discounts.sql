ALTER TABLE product
    ADD COLUMN discount_type VARCHAR(32),
    ADD COLUMN discount_value NUMERIC(19,4),
    ADD COLUMN discount_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN discount_include_wholesale BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT product_discount_type_check CHECK (discount_type IN ('PERCENT', 'FIXED')),
    ADD CONSTRAINT product_discount_value_check CHECK (
        (discount_type IS NULL AND discount_value IS NULL AND NOT discount_enabled AND NOT discount_include_wholesale)
        OR (discount_type IS NOT NULL AND discount_value IS NOT NULL AND discount_value > 0
            AND (discount_type <> 'PERCENT' OR discount_value <= 100))
    );

-- A free item still needs a return document to restore stock at its original cost.
ALTER TABLE sale_return DROP CONSTRAINT sale_return_amounts_range_check,
    ADD CONSTRAINT sale_return_amounts_range_check CHECK
        (refund_amount >= 0 AND tax_amount >= 0 AND tax_amount <= refund_amount);
