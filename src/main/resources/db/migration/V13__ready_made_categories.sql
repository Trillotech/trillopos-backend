-- V13 — ready-made categories (2026-09-29). TrilloPOS offers common categories — Footwear,
-- Women's, Men's and Kids' clothing, Bags, Food, Drinks… — each with its size table where the
-- goods come in sizes: Footwear brings the shoe table (EU, UK, US men, US women, CM). They are
-- offered as choices and become the shop's own the first time it uses one.
--
-- template_key: the ready-made category a shop's category was made from, so it is made once.

alter table category add column template_key varchar(40);

create unique index category_template_key_live_idx
    on category (organization_id, template_key)
    where template_key is not null and archived_at is null;
