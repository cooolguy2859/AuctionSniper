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