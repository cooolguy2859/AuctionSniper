# AuctionSniper (Fabric 1.21.11)

Client-side Auction House helper for Minecraft Fabric 1.21.11.

## Commands

- `/ahsniper help`
- `/ahsniper scan` — sends `/ah`, waits for the menu, scans visible item names/counts, then attempts to click a Next button.
- `/ahsniper cancel` — stop an active scan.
- `/ahsniper status` — show scan state and settings.
- `/ahsniper profit min <coins>` — save a minimum-profit setting.
- `/ahsniper budget <coins>` — save a maximum-budget setting.
- `/ahsniper hud true|false` — toggle the HUD.

## Build

Use JDK 21 and run `./gradlew build`. GitHub Actions also builds the mod.

## Current limits

The page scanner is best-effort: it looks for a visible item named Next/Forward or common arrow symbols. Server menus may use a different button or layout. It currently reads item names and stack counts, not auction prices, seller names, or custom enchantment lore. Profit and budget are saved settings only; there is no automatic buying or selling. Test manually and verify server rules before using automation.

## Cloud market backend

AuctionSniper can share observed Auction House prices with the Cloudflare Worker at
`https://auction-sniper-api.firewolfjm10.workers.dev`. The mod sends item observations
in the background and refreshes shared averages every five minutes.

1. Deploy the Worker and configure its `API_KEY` secret in Cloudflare.
2. Put the matching key in `config/auction-sniper.properties` as `cloudApiKey=...`.
3. Keep `cloudApiUrl` set to the Worker URL and `serverId=lifesteal.net`.
4. Run `/ahsniper cloud sync` and `/ahsniper cloud status`.

The key is stored in the local Minecraft config file, not committed to this repository.
Shared averages are only eligible for the existing conservative auto-buy checks after
at least eight observations; the mod still requires a recognized confirmation screen.
