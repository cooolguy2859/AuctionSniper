# AuctionSniper (Fabric 1.21.11)

Client-side market tools and Auction House automation for Minecraft Fabric 1.21.11.

## Main commands

- `/ahsniper help` — command list.
- `/ahsniper scan` — scan Auction House pages.
- `/ahsniper autobuy on|off` — arm or disable guarded auto-buy.
- `/ahsniper cancel` — stop the active scan.
- `/ahsniper status` — view scan state and settings.
- `/ahsniper profit min <coins>` — minimum estimated profit.
- `/ahsniper budget <coins|balance>` — maximum spend for an auto-buy run.
- `/ahsniper cloud sync|status` — refresh or inspect shared market data.
- `/ahsniper hud true|false` — toggle the net-worth HUD.
- `/ahsniper gui` — open the command menu.

## Auto-buy safety

Auto-buy is opt-in and remains off until enabled. It uses the shared Deal Advisor,
local price history, shared price observations, and the shared order book when fresh
data is available. The advisor uses buy orders only as a liquidity signal; a buy
order is not proof that an item sold and is never treated as guaranteed profit.

Before buying, the scanner checks the configured budget, available balance, minimum
estimated profit, confidence threshold, listing identity, and the confirmation
screen's exact displayed price. It waits for a server purchase confirmation before
counting a purchase. If confirmation is unclear or times out, the scan stops safely.
Market estimates can still be wrong; test with auto-buy disabled first and follow
the server's rules.

## Shared market backend

The optional Cloudflare Worker stores listing observations, shared price summaries,
and order-book snapshots for the configured `serverId`. The client syncs shared
prices and order-book data in the background. A local config file stores the API
settings; do not publish API keys or config files.

The shared price data is based on submitted observations, not a verified exchange
feed. Listing observations and buy-order bids are not confirmed sale prices.
Confirmed completed sales are uploaded separately only when the server's Auction
House history explicitly reports a `sold item` entry. A listing disappearing or
being deleted is not counted as a sale.

## Build and checks

Use JDK 21 and run:

```sh
gradle build --no-daemon
node --input-type=module --check < backend/worker.js
node backend/worker_smoke_test.mjs
python backend/smoke_test.py
```

GitHub Actions runs the Worker syntax check, backend smoke tests, D1 schema check,
and Fabric/Gradle build on each push to `main`.

## Known limits

- Auction House page navigation and confirmation-menu recognition depend on the
  server's current menu layout and may need adjustment if the server changes it.
- Auto-buy requires a recognized confirmation screen and explicit server success
  message; ambiguous results intentionally stop rather than assume a purchase.
- Shared prices depend on the observations contributed to this backend. They are
  estimates, not guaranteed resale values.
- The mod cannot infer a sale merely because a listing disappears.
