# AuctionSniper shared market backend

Cloudflare Worker + D1 API for sharing item-price observations between AuctionSniper players.

## Endpoints

- `GET /health` — public health check.
- `POST /v1/observations` — authenticated price observation.
- `GET /v1/prices?serverId=lifesteal.net&limit=100` — authenticated average, low, high, sample count, and last-seen time.
- `GET /v1/history?serverId=lifesteal.net&itemId=minecraft:diamond` — authenticated recent history.

All authenticated routes require `Authorization: Bearer <API_KEY>`.

## D1 setup

Create a D1 database named `auction-sniper-market`, then apply `schema.sql`.

Bind the database to the Worker as `DB`. Add a Worker secret named `API_KEY` with a long random value. Do not commit the API key to GitHub or put it in source code.

## Worker deployment

Deploy `worker.js` as a module Worker. Configure the D1 binding and secret before enabling the mod's network integration. The Worker does not scrape the Minecraft server; the mod must observe listings in menus that players open and submit those observations.

## API example

```http
POST /v1/observations
Authorization: Bearer <API_KEY>
Content-Type: application/json

{
  "serverId": "lifesteal.net",
  "itemId": "minecraft:diamond",
  "itemName": "Diamond",
  "price": 12500,
  "player": "ExamplePlayer"
}
```

This initial backend stores observations and exposes shared aggregates/history. It does not yet implement user accounts, purchase execution, or a public web dashboard.
