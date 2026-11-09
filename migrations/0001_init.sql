-- 0001_init.sql — the D1 schema for the infered router, byte-matched to the
-- live database b8c91497-e416-4315-8dba-256f9f5652b0 (2026-11-09 pull).
-- Idempotent: safe to re-run against prod; existing tables are left alone.
--
-- Apply (remote):
--   bunx wrangler d1 execute infered-routing --remote --file migrations/0001_init.sql
-- Apply (local dev copy):
--   bunx wrangler d1 execute infered-routing --file migrations/0001_init.sql

CREATE TABLE IF NOT EXISTS routing_decisions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts TEXT NOT NULL DEFAULT (datetime('now')),
  session_id TEXT,
  selected_model TEXT NOT NULL,
  selected_provider TEXT,
  escalation_level INTEGER NOT NULL DEFAULT 0,
  attempts INTEGER NOT NULL DEFAULT 1,
  latency_ms INTEGER,
  budget_cap TEXT,
  ok INTEGER NOT NULL DEFAULT 1,
  error TEXT,
  requested_model TEXT
);

CREATE TABLE IF NOT EXISTS session_pins (
  session_id TEXT PRIMARY KEY,
  provider TEXT,
  model TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
