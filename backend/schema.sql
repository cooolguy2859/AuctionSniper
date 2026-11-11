CREATE TABLE IF NOT EXISTS observations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  server_id TEXT NOT NULL,
  item_id TEXT NOT NULL,
  item_name TEXT NOT NULL,
  price INTEGER NOT NULL CHECK (price >= 0),
  player TEXT,
  observed_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_observations_server_item_time
ON observations (server_id, item_id, observed_at DESC);

CREATE INDEX IF NOT EXISTS idx_observations_server_time
ON observations (server_id, observed_at DESC);
