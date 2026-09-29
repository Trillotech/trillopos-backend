-- V12 — size charts become conversion tables (2026-09-29). One chart holds every sizing system
-- side by side, one row per size, so a shop sees at once that EU 42 is UK 8, US men's 9, US
-- women's 10.5 and 26.5 cm. V11 kept one system per chart; it was never deployed.
--
-- systems: the column names, the one the shop labels its stock with first (product names use it:
-- "Converse · EU 42"). '' is a column without a name, for S/M/L.
-- cells: the table row by row, cardinality(systems) cells per row; '' where a size has no match
-- in that system. The first cell of a row is the size's label and is unique in the chart.
--
-- product.size_equivalents: the same size in the chart's other systems when the product was made,
-- "UK 8 · US M 9 · CM 26.5". A snapshot, like everything else on a product: the shop may correct
-- it for one brand without touching the chart (brands differ by up to half a size).

alter table size_chart
    add column systems text[],
    add column cells text[];

update size_chart set systems = array[coalesce(short_name, '')], cells = labels;

alter table size_chart
    alter column systems set not null,
    alter column cells set not null,
    drop constraint size_chart_labels_count_check,
    drop column labels,
    drop column short_name,
    add constraint size_chart_systems_count_check check (cardinality(systems) between 1 and 8),
    -- nullif: no division by zero when systems is empty (the check above refuses that row)
    add constraint size_chart_cells_shape_check
        check (cardinality(cells) between 1 and 640
               and cardinality(cells) % nullif(cardinality(systems), 0) = 0);

alter table product add column size_equivalents varchar(255);
