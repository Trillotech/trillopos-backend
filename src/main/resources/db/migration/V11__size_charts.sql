-- V11 — size charts (2026-09-29): a shoe or a dress comes in sizes, and every shop labels them
-- its own way (EU, UK, US men, US women, kids, cm, letters, waist…).
--
-- size_chart: the shop's own list of sizes, in order. Built from the library in the code
-- (template_key says which entry) or typed by the shop; either way the shop may edit it.
-- Sizes stay flat SKUs (spec §4): a chart only helps create one product per size and tells
-- which chart a product's size_label belongs to. Editing a chart never renames products —
-- their names and size labels are what they were created with.
--
-- category.size_chart_id: the chart the Add Product form opens with for that category.
-- product.size_chart_id: the chart its size_label came from, so sizes can be ordered.
--
-- New tables are not covered by V9's loop, so this one gets its RLS policy here.

create table size_chart (
    id              uuid         primary key,
    version         bigint       not null,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    created_by      uuid,
    updated_by      uuid,
    organization_id uuid         not null references organization (id),
    archived_at     timestamptz,
    name            varchar(80)  not null,
    short_name      varchar(10),
    kind            varchar(16)  not null,
    template_key    varchar(40),
    labels          text[]       not null,
    constraint size_chart_organization_id_id_key unique (organization_id, id),
    constraint size_chart_kind_check check (kind in ('FOOTWEAR', 'CLOTHING', 'OTHER')),
    constraint size_chart_labels_count_check check (cardinality(labels) between 1 and 80)
);

-- one live copy per library entry: picking "EU shoes" twice finds the same chart
create unique index size_chart_template_key_live_idx
    on size_chart (organization_id, template_key)
    where template_key is not null and archived_at is null;

alter table size_chart enable row level security;
create policy tenant_isolation on size_chart
    using (organization_id = nullif(current_setting('app.org', true), '')::uuid)
    with check (organization_id = nullif(current_setting('app.org', true), '')::uuid);

alter table category
    add column size_chart_id uuid,
    add constraint category_size_chart_fk foreign key (organization_id, size_chart_id)
        references size_chart (organization_id, id);

alter table product
    add column size_chart_id uuid,
    add constraint product_size_chart_fk foreign key (organization_id, size_chart_id)
        references size_chart (organization_id, id);
