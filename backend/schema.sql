CREATE TABLE IF NOT EXISTS observations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  server_id TEXT NOT NULL,
  item_id TEXT NOT NULL,
  item_name TEXT NOT NULL,
  price INTEGER NOT NULL CHECK (price >= 0),
  player TEXT,
  observed_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_observations_server_item_time ON observations(server_id,item_id,observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_observations_server_time ON observations(server_id,observed_at DESC);

CREATE TABLE IF NOT EXISTS networth_reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT NOT NULL, server_id TEXT NOT NULL,
  worth INTEGER NOT NULL, is_private INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS order_book (
  id INTEGER PRIMARY KEY AUTOINCREMENT, server_id TEXT NOT NULL, ticker TEXT NOT NULL,
  bid INTEGER NOT NULL DEFAULT 0, demand INTEGER NOT NULL DEFAULT 0, orders INTEGER NOT NULL DEFAULT 0,
  listed INTEGER NOT NULL DEFAULT 0, material TEXT, depth_json TEXT, updated_at INTEGER NOT NULL,
  UNIQUE(server_id,ticker)
);
CREATE TABLE IF NOT EXISTS player_balances (
  server_id TEXT NOT NULL, player TEXT NOT NULL, balance INTEGER NOT NULL, updated_at INTEGER NOT NULL,
  PRIMARY KEY(server_id,player)
);
CREATE TABLE IF NOT EXISTS player_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT, server_id TEXT NOT NULL, player TEXT NOT NULL,
  event_type TEXT NOT NULL, item_name TEXT, price INTEGER NOT NULL DEFAULT 0,
  quantity INTEGER NOT NULL DEFAULT 0, occurred_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS team_reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT, server_id TEXT NOT NULL, team_name TEXT NOT NULL,
  leader TEXT NOT NULL, worth INTEGER NOT NULL DEFAULT 0, spawners INTEGER NOT NULL DEFAULT 0,
  tracked INTEGER NOT NULL DEFAULT 0, total_members INTEGER NOT NULL DEFAULT 0,
  verified INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, UNIQUE(server_id,team_name)
);
CREATE TABLE IF NOT EXISTS ender_shares (
  server_id TEXT NOT NULL, player TEXT NOT NULL, shared INTEGER NOT NULL DEFAULT 0,
  value INTEGER NOT NULL DEFAULT 0, items_json TEXT NOT NULL DEFAULT '[]', updated_at INTEGER NOT NULL,
  PRIMARY KEY(server_id,player)
);
CREATE TABLE IF NOT EXISTS referral_tags (
  server_id TEXT NOT NULL, player TEXT NOT NULL, tag TEXT NOT NULL, PRIMARY KEY(server_id,player)
);
CREATE TABLE IF NOT EXISTS player_accounts (
  server_id TEXT NOT NULL, player TEXT NOT NULL, token TEXT NOT NULL,
  created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL, PRIMARY KEY(server_id,player)
);
CREATE TABLE IF NOT EXISTS auth_challenges (
  server_id TEXT NOT NULL, player TEXT NOT NULL, nonce TEXT NOT NULL, expires_at INTEGER NOT NULL,
  PRIMARY KEY(server_id,player)
);
CREATE TABLE IF NOT EXISTS player_presence (
  server_id TEXT NOT NULL, player TEXT NOT NULL, seen_at INTEGER NOT NULL, PRIMARY KEY(server_id,player)
);
CREATE TABLE IF NOT EXISTS team_members (
  server_id TEXT NOT NULL, team_name TEXT NOT NULL, role TEXT NOT NULL, player TEXT NOT NULL,
  updated_at INTEGER NOT NULL, PRIMARY KEY(server_id,team_name,player)
);
CREATE TABLE IF NOT EXISTS team_spawners (
  server_id TEXT NOT NULL, team_name TEXT NOT NULL, kind TEXT NOT NULL, count INTEGER NOT NULL,
  updated_at INTEGER NOT NULL, PRIMARY KEY(server_id,team_name,kind)
);
CREATE TABLE IF NOT EXISTS networth_snapshots (
  id INTEGER PRIMARY KEY AUTOINCREMENT, server_id TEXT NOT NULL, player TEXT NOT NULL,
  worth INTEGER NOT NULL, is_private INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS player_ender_share (
  server_id TEXT NOT NULL, player TEXT NOT NULL, shared INTEGER NOT NULL DEFAULT 0,
  value INTEGER NOT NULL DEFAULT 0, items_json TEXT NOT NULL DEFAULT '[]', updated_at INTEGER NOT NULL,
  PRIMARY KEY(server_id,player)
);
CREATE TABLE IF NOT EXISTS player_referrals (
  server_id TEXT NOT NULL, player TEXT NOT NULL, referrer TEXT NOT NULL, created_at INTEGER NOT NULL,
  PRIMARY KEY(server_id,player)
);
CREATE TABLE IF NOT EXISTS player_alerts (
  server_id TEXT NOT NULL, player TEXT NOT NULL, ticker TEXT NOT NULL, target INTEGER NOT NULL,
  below INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(server_id,player,ticker)
);
CREATE TABLE IF NOT EXISTS player_links (
  server_id TEXT NOT NULL, player TEXT NOT NULL, code TEXT NOT NULL, linked_at INTEGER NOT NULL,
  PRIMARY KEY(server_id,player)
);
CREATE TABLE IF NOT EXISTS player_flair (
  server_id TEXT NOT NULL, player TEXT NOT NULL, color TEXT NOT NULL DEFAULT '',
  tag_id TEXT NOT NULL DEFAULT '', role_id TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL,
  PRIMARY KEY(server_id,player)
);
CREATE TABLE IF NOT EXISTS order_book_history (
  id INTEGER PRIMARY KEY AUTOINCREMENT, server_id TEXT NOT NULL, ticker TEXT NOT NULL,
  bid INTEGER NOT NULL, demand INTEGER NOT NULL, orders INTEGER NOT NULL, observed_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_networth_server_worth ON networth_reports(server_id,is_private,worth DESC);
CREATE INDEX IF NOT EXISTS idx_nws_lookup ON networth_snapshots(server_id,player,updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_server_ticker ON order_book(server_id,ticker);
CREATE INDEX IF NOT EXISTS idx_events_server_time ON player_events(server_id,occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_team_members ON team_members(server_id,team_name);
CREATE INDEX IF NOT EXISTS idx_presence ON player_presence(server_id,seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_history ON order_book_history(server_id,ticker,observed_at DESC);
