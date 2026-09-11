-- One row per upload: the audit trail, and the progress counters the status endpoint polls.
CREATE TABLE import_run (
    id              bigserial PRIMARY KEY,
    file_name       varchar(255) NOT NULL,
    status          varchar(16)  NOT NULL,   -- PENDING | RUNNING | COMPLETED | FAILED
    started_at      timestamptz  NOT NULL DEFAULT now(),
    finished_at     timestamptz,
    reference_date  date         NOT NULL,   -- the import's "today", so stock ages are consistent
    rows_read       integer      NOT NULL DEFAULT 0,
    rows_imported   integer      NOT NULL DEFAULT 0,
    rows_rejected   integer      NOT NULL DEFAULT 0,
    failure_message text
);

-- import_id records which run last wrote the row; an upsert overwrites it, so it means
-- "last written by" rather than "owned by".
CREATE TABLE product (
    id                bigserial PRIMARY KEY,
    import_id         bigint        NOT NULL REFERENCES import_run (id),
    source_row_number integer       NOT NULL,
    product_sku       varchar(64)   NOT NULL,
    product_name      varchar(255)  NOT NULL,
    category          varchar(255)  NOT NULL,
    purchase_date     date          NOT NULL,
    unit_price        numeric(12, 2) NOT NULL CHECK (unit_price >= 0),
    quantity          integer       NOT NULL CHECK (quantity > 0),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT product_unique_sku_date UNIQUE (product_sku, purchase_date)
);

CREATE TABLE import_rejection (
    import_id  bigint  NOT NULL REFERENCES import_run (id) ON DELETE CASCADE,
    row_number integer NOT NULL,
    reason     text,
    PRIMARY KEY (import_id, row_number)
);

-- Two indexes on purpose. product_unique_sku_date is the upsert's conflict target and also
-- serves SKU-ordered reads; this one serves purchase-date order and, because stock age is a
-- monotone function of the date, stock-age order too. The remaining sortable columns fall back
-- to a scan plus top-N sort -- cheaper overall than paying for seven more indexes on every load.
CREATE INDEX product_purchase_date_idx ON product (purchase_date, id);
