-- Existing posted sales are closed; new online sales start open in the service.
ALTER TABLE sale ADD COLUMN progress VARCHAR(32) NOT NULL DEFAULT 'CLOSED',
    ADD CONSTRAINT sale_progress_check CHECK (progress IN ('OPEN', 'CLOSED', 'CANCELED'));

-- Separate returned cash from released credit on deposit/unpaid cancellations.
ALTER TABLE sale_return
    ADD COLUMN credit_refund_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN rounding_refund_amount NUMERIC(19,4) NOT NULL DEFAULT 0;
UPDATE sale_return SET credit_refund_amount = refund_amount WHERE refund_method = 'CREDIT';
ALTER TABLE sale_return ADD CONSTRAINT sale_return_credit_refund_check CHECK
    (credit_refund_amount >= 0 AND credit_refund_amount <= refund_amount
        AND (refund_method <> 'CREDIT' OR credit_refund_amount = refund_amount));

CREATE TABLE sale_progress_event (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_by UUID,
    organization_id UUID NOT NULL REFERENCES organization(id),
    archived_at TIMESTAMPTZ,
    sale_id UUID NOT NULL,
    from_progress VARCHAR(32) NOT NULL,
    to_progress VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    CONSTRAINT sale_progress_event_sale_fk FOREIGN KEY (organization_id, sale_id) REFERENCES sale(organization_id, id),
    CONSTRAINT sale_progress_event_from_progress_check CHECK (from_progress IN ('OPEN', 'CLOSED', 'CANCELED')),
    CONSTRAINT sale_progress_event_to_progress_check CHECK (to_progress IN ('OPEN', 'CLOSED', 'CANCELED'))
);
CREATE INDEX sale_progress_event_sale_idx ON sale_progress_event(sale_id, created_at, id);
ALTER TABLE sale_progress_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sale_progress_event
    USING (organization_id = nullif(current_setting('app.org', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.org', true), '')::uuid);
