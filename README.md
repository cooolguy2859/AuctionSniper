# AuctionSniper (Fabric 1.21.11)

An early client-side Auction House helper scaffold for Minecraft Fabric 1.21.11.

## Build

- Install JDK 21.
- Open this repository in IntelliJ IDEA or run `./gradlew build` after adding the Gradle wrapper.
- GitHub Actions builds the project and uploads the JAR as a workflow artifact.

## Current status

This is a starter project, not the complete sniper described in the feature request. The current code does not perform automatic purchases or sales, scan every Auction House page, determine market prices, validate custom-item authenticity, or render player balances above heads. It only provides basic commands and visible-GUI inspection. Use manual review; verify all server rules before using automation.

## Downloading a test build

Open the **Actions** tab, select the latest successful build, and download the `AuctionSniper-jar` artifact. This is a test build, not a release.