# AuctionSniper (Fabric 1.21.11)

An early client-side Auction House helper for Minecraft Fabric 1.21.11.

## Commands

- `/ahsniper help`
- `/ahsniper scan` — sends the server command `/ah`, waits for the Auction House menu, scans visible listing slots, and attempts to click a detected Next button to continue through pages.
- `/ahsniper status`
- `/ahsniper profit min <coins>`
- `/ahsniper budget <coins>`

## Build

- Install JDK 21.
- Run `./gradlew build`.
- GitHub Actions builds the project and uploads the JAR as a workflow artifact.

## Current limits

Page navigation is best-effort because Auction House menus can use different button names and layouts. The scanner currently reports item names and stack counts only. It does not read prices or custom enchantment lore, calculate market values, buy or sell items, or show balances above players' heads. Test on the server and stop the scan if the menu behaves unexpectedly.