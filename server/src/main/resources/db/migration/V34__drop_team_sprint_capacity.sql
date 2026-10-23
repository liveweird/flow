-- Drop the legacy per-sprint capacity override (checkup 3, D3-5; decided 2026-10-23, A11). V15 created `metrics.team_sprint_capacity`
-- (one admin-typed MD figure per connection and sprint); V24 (capacity management, domain-model A32) replaced it with
-- `team_capacity_defaults` + `sprint_calendars`, copying its latest figure per team into the new default (V24's one-off seed), and
-- nothing has read or written it since. It lived on only so a connection PURGE could drain it; that line goes with this migration.
-- IRREVERSIBLE: the rows are gone for good -- they are fully superseded by the V24 seed and the capacity tables.
DROP TABLE metrics.team_sprint_capacity;
