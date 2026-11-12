# AuctionSniper Cloudflare backend

The owned backend is a Cloudflare Worker named auction-sniper-api, backed by the D1 database auction-sniper-market. The Fabric client is pinned to this Worker, including when an older config file contains a different cloudApiUrl; backend discovery and the old localhost fallback are disabled.

## Routes

The Worker serves the AuctionSniper v1 API and compatibility routes used by the existing mod UI.

- GET /health
- POST /v1/observations
- GET /v1/prices, GET /v1/history, GET /v1/movers
- POST /v1/networth/report, GET /v1/networth/top, GET /v1/networth/series
- POST /v1/orders, GET /v1/orders
- POST /v1/team/report, GET /v1/team/top, GET /v1/team
- POST /v1/balances, GET /v1/balances/top
- POST /v1/events, GET /v1/events
- POST /v1/ender-share, GET /v1/ender-share

Compatibility routes cover player provisioning/auth, access checks, net-worth and team boards, ender-chest sharing, tickers and movers, charts and order books, alerts, referrals, flair, mod-user presence, avatar proxy, store and partner metadata.

Authenticated routes accept Authorization: Bearer <API_KEY> (v1) or X-Market-Key (legacy client). The key is shared with mod clients and should be treated as a public client credential, not a user password.

## D1

Apply schema.sql to the D1 database. It uses CREATE TABLE/INDEX IF NOT EXISTS and can be applied to an existing database.

Bind D1 as DB and set a Worker secret named API_KEY. Preserve both bindings when updating Worker source.

## Server IDs

The default database partition and tracking hostname are both lifesteal.net. In config/auction-sniper.properties, serverId controls the D1 data partition and serverHost controls which Minecraft hostname activates legacy tracking. They are separate on purpose: for example, serverId can be a stable label while serverHost remains the actual address. The v1 API receives serverId for every observation/report, and legacy calls send X-Server-Id. Do not combine unrelated networks under one ID.

## Data and integrations

The Worker stores new observations going forward; history from a separate backend is not automatically copied. The mod records item prices it sees while players open supported market/order screens.

Discord linking still needs a separate Discord bot/webhook integration and returns HTTP 503 rather than falsely claiming a link succeeded. The shop and partner endpoints currently return empty catalogues until entries are added; custom artwork returns 404 until images are stored. Avatar images are proxied from mc-heads.net. These are explicit integration/data gaps, not connections to the old backend.

## Example

POST /v1/observations with Authorization: Bearer <API_KEY> and JSON body:

{
  "serverId": "lifesteal.net",
  "itemId": "minecraft:diamond",
  "itemName": "Diamond",
  "price": 12500,
  "player": "ExamplePlayer"
}
