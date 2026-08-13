-- ---------------------------------------------------------------------------
-- HD-52 (Query by custom fields in HQL search) — JSONB supporting index
-- ---------------------------------------------------------------------------
-- Custom-field predicates compile to a correlated EXISTS over issue_field_values,
-- filtered by field_id and then by a per-type predicate on the JSONB `value`
-- column (jsonb_extract_path_text for scalars, jsonb_exists / `?` for
-- MULTI_SELECT membership). A GIN index on `value` supports the containment/
-- key-existence operators (@>, ?) used by MULTI_SELECT lookups.
--
-- field_id already has a btree (idx_issue_field_values_fld in the V1 baseline),
-- which serves the correlation's field filter — no duplicate added here.
--
-- Uses the DEFAULT jsonb_ops opclass (not jsonb_path_ops): jsonb_path_ops does
-- not support the `?` key-/element-existence operator that MULTI_SELECT membership
-- (jsonb_exists) relies on. jsonb_ops supports both `?` and `@>`.
--
-- Indexes only, additive; no CHAR(n) / PG ENUM. IF NOT EXISTS keeps it idempotent.

CREATE INDEX IF NOT EXISTS idx_issue_field_values_value_gin
    ON issue_field_values USING gin (value);
