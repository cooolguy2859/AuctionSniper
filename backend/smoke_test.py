#!/usr/bin/env python3
"""Quick local validation for the Cloudflare D1 schema."""
from pathlib import Path
import sqlite3

root = Path(__file__).resolve().parent
schema = (root / "schema.sql").read_text(encoding="utf-8")

db = sqlite3.connect(":memory:")
db.executescript(schema)

required = {
    "observations", "networth_reports", "order_book", "player_balances",
    "player_events", "team_reports", "ender_shares", "referral_tags",
    "player_accounts", "auth_challenges", "player_presence", "team_members",
    "team_spawners", "networth_snapshots", "player_ender_share",
    "player_referrals", "player_alerts", "player_links", "player_flair",
    "order_book_history",
}
actual = {
    row[0]
    for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")
}
missing = required - actual
if missing:
    raise SystemExit("Missing D1 tables: " + ", ".join(sorted(missing)))

db.execute(
    "INSERT INTO observations(server_id,item_id,item_name,price,observed_at) "
    "VALUES(?,?,?,?,?)",
    ("lifesteal.net", "minecraft:diamond", "Diamond", 12500, 1),
)
db.execute(
    "INSERT INTO networth_snapshots(server_id,player,worth,is_private,updated_at) "
    "VALUES(?,?,?,?,?)",
    ("lifesteal.net", "ExamplePlayer", 250000, 0, 1),
)
db.execute(
    "INSERT INTO team_reports(server_id,team_name,leader,worth,updated_at) "
    "VALUES(?,?,?,?,?)",
    ("lifesteal.net", "ExampleTeam", "ExamplePlayer", 250000, 1),
)
assert db.execute("SELECT COUNT(*) FROM observations").fetchone()[0] == 1
assert db.execute("SELECT COUNT(*) FROM networth_snapshots").fetchone()[0] == 1
assert db.execute("SELECT COUNT(*) FROM team_reports").fetchone()[0] == 1
print(f"D1 schema OK: {len(required)} required tables and sample inserts passed.")
