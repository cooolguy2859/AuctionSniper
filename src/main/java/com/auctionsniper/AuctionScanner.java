package com.auctionsniper;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.HashSet;
import java.util.Set;

public final class AuctionScanner {
    private static boolean scanning;
    private static int waitTicks;
    private static int pages;
    private static int totalListings;
    private static String lastFingerprint = "";
    private static boolean sortConfigured;
    private static int sortModeIndex;

    private static String pendingFingerprint = "";
    private static boolean awaitingPageChange;
    private static int pageChangeWaitRetries;
    private static boolean returningToFirstPage;
    private static boolean reopenAfterPurchase;
    private static boolean autoBuyEnabled;
    private static boolean awaitingBuyConfirmation;
    private static boolean awaitingServerPurchaseResult;
    private static int purchaseResultWait;
    private static boolean purchaseSucceeded;
    private static long pendingBuyPrice;
    private static String pendingBuyName = "";
    private static int attemptedBuys;
    private static long totalSpentThisRun;
    private static String pendingBuyKey = "";
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int NEXT_PAGE_SLOT = 53;
    private static final int SORT_ITEMS_SLOT = 49;
    private static final Set<String> attemptedListings = new HashSet<>();
    private static final Set<String> observedListings = new HashSet<>();

    private AuctionScanner() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(AuctionScanner::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!awaitingServerPurchaseResult) return;
            String line = message.getString().toLowerCase(java.util.Locale.ROOT);
            if (line.contains("purchase successful") || line.contains("you bought") || line.contains("successfully purchased") || line.contains("bought for")) {
                purchaseSucceeded = true;
            } else if (line.contains("not enough") || line.contains("purchase failed") || line.contains("already sold") || line.contains("no longer available")) {
                purchaseSucceeded = false;
                purchaseResultWait = 0;
            }
        });
    }
    public static boolean isScanning() { return scanning; }
    public static int getPages() { return pages; }
    public static boolean isAutoBuyEnabled() { return autoBuyEnabled; }
    public static void setAutoBuyEnabled(boolean enabled) {
        autoBuyEnabled = enabled;
        AuctionSniperClient.msg("Auto-buy " + (enabled ? "armed (strict price checks)" : "disabled") + ".");
    }
    public static int getPricedListings() { return PriceTracker.pricedListings(); }

    public static void startScan() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) {
            AuctionSniperClient.msg("Join a server before starting a scan."); return;
        }
        if (scanning) {
            AuctionSniperClient.msg("A scan is already running. Use /ahsniper cancel to stop it."); return;
        }
        scanning = true;
        waitTicks = 15;
        pages = 0;
        totalListings = 0;
        lastFingerprint = "";
        sortConfigured = false;
        sortModeIndex = 0;
        awaitingPageChange = false;
        pageChangeWaitRetries = 0;
        returningToFirstPage = false;
        pendingFingerprint = "";
        attemptedBuys = 0;
        totalSpentThisRun = 0;
        pendingBuyKey = "";
        reopenAfterPurchase = false;
        awaitingBuyConfirmation = false;
        awaitingServerPurchaseResult = false;
        purchaseSucceeded = false;
        purchaseResultWait = 0;
        pendingBuyPrice = 0;
        pendingBuyName = "";
        attemptedListings.clear();
        observedListings.clear();
        client.getNetworkHandler().sendChatCommand("ah");
        AuctionSniperClient.msg("Continuous Auction House scan started. Auto-buy stays off unless you enable it with /ahsniper autobuy on.");
    }

    public static void cancel() {
        if (!scanning) { AuctionSniperClient.msg("No scan is running."); return; }
        finish("Scan cancelled. Pages visited: " + pages + ", listings seen: " + totalListings + ".");
    }

    private static void tick(MinecraftClient client) {
        if (!scanning) return;
        if (client.player == null || client.getNetworkHandler() == null) {
            finish("Scan stopped because you left the server."); return;
        }
        if (waitTicks-- > 0) return;
        if (awaitingServerPurchaseResult) {
            if (purchaseSucceeded) {
                awaitingServerPurchaseResult = false;
                attemptedBuys++;
                totalSpentThisRun = totalSpentThisRun > Long.MAX_VALUE - pendingBuyPrice
                        ? Long.MAX_VALUE : totalSpentThisRun + pendingBuyPrice;
                AuctionSniperClient.recordOwnPurchase(pendingBuyPrice);
                PriceTracker.recordPurchase(pendingBuyKey, pendingBuyPrice);
                AuctionSniperClient.msg("Confirmed purchase: " + pendingBuyName + " for " + pendingBuyPrice
                        + " coins. Run spending: " + totalSpentThisRun + "/" + AuctionSniperClient.SETTINGS.maxBudget + ".");
                if (totalSpentThisRun >= AuctionSniperClient.SETTINGS.maxBudget) {
                    finish("Auto-buy spending cap reached. Total spent: " + totalSpentThisRun + " coins.");
                    return;
                }
                reopenAfterPurchase = true;
                waitTicks = 20;
                return;
            }
            if (--purchaseResultWait <= 0) {
                awaitingServerPurchaseResult = false;
                finish("Purchase result was not confirmed by server chat. Auto-buy stopped safely; check your inventory and balance.");
                return;
            }
            return;
        }
        if (reopenAfterPurchase) {
            reopenAfterPurchase = false;
            client.getNetworkHandler().sendChatCommand("ah");
            observedListings.clear();
            awaitingPageChange = false;
            pendingFingerprint = "";
            waitTicks = 15;
            return;
        }
        if (!(client.currentScreen instanceof HandledScreen<?>)) {
            finish("Auction House closed. Scan stopped."); return;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        int listingSlots = Math.max(0, handler.slots.size() - 36);

        if (awaitingBuyConfirmation) {
            int confirmSlot = findConfirmBuySlot(handler, pendingBuyPrice);
            if (confirmSlot >= 0 && client.interactionManager != null) {
                client.interactionManager.clickSlot(handler.syncId, confirmSlot, 0, SlotActionType.PICKUP, client.player);
                awaitingBuyConfirmation = false;
                awaitingServerPurchaseResult = true;
                purchaseSucceeded = false;
                purchaseResultWait = 100;
                AuctionSniperClient.msg("Confirmation clicked for " + pendingBuyName + ". Waiting for server result...");
                waitTicks = 1;
                return;
            }
            if (isAuctionBuyMenu(handler)) {
                // Confirmation menu is present, but its price or button did not match exactly.
                finish("Purchase not confirmed: could not verify the green Confirm Buy button and exact price.");
                return;
            }
            finish("Purchase stopped: confirmation menu was not recognized.");
            return;
        }

        // Full Auction House scan: never interact with the sort control.
        sortConfigured = true;

        String fingerprint = fingerprint(handler);
        if (awaitingPageChange) {
            if (fingerprint.equals(pendingFingerprint)) {
                // Auction House updates can arrive late; wait a little longer before declaring failure.
                if (pageChangeWaitRetries++ < 3) {
                    waitTicks = 8;
                    return;
                }
                finish("Page did not change after waiting for the server. Stopped safely.");
                return;
            }
            awaitingPageChange = false;
            pageChangeWaitRetries = 0;
            pendingFingerprint = "";
        }

        int found = 0;
        for (int i = 0; i < listingSlots; i++) {
            if (i == PREVIOUS_PAGE_SLOT || i == NEXT_PAGE_SLOT || i == SORT_ITEMS_SLOT) continue;
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            String itemName = stack.getName().getString();
            Long price = PriceTracker.readPrice(stack);
            if (price == null) continue;
            found++;
            String key = PriceTracker.itemKey(stack);
            String observation = fingerprint + "|" + i + "|" + key + "|" + price;
            boolean fresh = observedListings.add(observation);
            Long average = PriceTracker.average(key);
            MarketApi.RemotePrice sharedPrice = MarketApi.price(key);
            if (average == null && sharedPrice != null) average = sharedPrice.average();
            if (fresh && autoBuyEnabled
                    && totalSpentThisRun <= AuctionSniperClient.SETTINGS.maxBudget
                    && price <= AuctionSniperClient.SETTINGS.maxBudget - totalSpentThisRun
                    && (PriceTracker.sampleCount(key) >= 8 || (sharedPrice != null && sharedPrice.samples() >= 8)) && average != null
                    && price <= AuctionSniperClient.SETTINGS.maxBudget
                    && AuctionSniperClient.getOwnBalance() >= price
                    && average > price
                    && average - price >= AuctionSniperClient.SETTINGS.minimumProfit
                    && isSafeListingName(itemName)
                    && attemptedListings.add(key + "|" + price)) {
                if (client.interactionManager != null) {
                    pendingBuyPrice = price;
                    pendingBuyName = itemName;
                    pendingBuyKey = key;
                    awaitingBuyConfirmation = true;
                    client.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, client.player);
                    waitTicks = 6;
                    AuctionSniperClient.msg("Opening purchase confirmation for " + itemName + " at " + price + " coins.");
                    return;
                }
            }
            if (fresh) {
                PriceTracker.observe(key, price);
                MarketApi.observe(key, itemName, price);
            }
        }

        pages++;
        totalListings += found;
        PriceTracker.flush();
        AuctionSniperClient.msg("Page " + pages + " scanned: " + found + " priced listings.");

        int next = navigationSlot(handler, NEXT_PAGE_SLOT);
        if (next >= 0) {
            if (client.interactionManager == null) { finish("Could not navigate Auction House."); return; }
            pendingFingerprint = fingerprint;
            awaitingPageChange = true;
            client.interactionManager.clickSlot(handler.syncId, next, 0, SlotActionType.PICKUP, client.player);
            waitTicks = 15;
            return;
        }

        AuctionSniperClient.msg("Full Auction House pass complete. Restarting from page 1.");
        client.getNetworkHandler().sendChatCommand("ah");
        observedListings.clear();
        awaitingPageChange = false;
        pendingFingerprint = "";
        waitTicks = 15;
    }

    private static int findConfirmBuySlot(ScreenHandler handler, long expectedPrice) {
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            StringBuilder text = new StringBuilder(stack.getName().getString());
            var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
            if (lore != null) {
                for (net.minecraft.text.Text line : lore.lines()) text.append(" ").append(line.getString());
            }
            String value = text.toString().toLowerCase(java.util.Locale.ROOT);
            if (!value.contains("confirm buy")) continue;
            if (!containsExactPrice(value, expectedPrice)) continue;
            return i;
        }
        return -1;
    }

    private static boolean containsExactPrice(String text, long expectedPrice) {
        if (expectedPrice <= 0) return false;
        // Match a complete numeric token so 1,200 cannot accidentally match 11,200.
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?<![0-9])([0-9][0-9,]*)(?![0-9])").matcher(text);
        while (matcher.find()) {
            try {
                long parsed = Long.parseLong(matcher.group(1).replace(",", ""));
                if (parsed == expectedPrice) return true;
            } catch (NumberFormatException ignored) { }
        }
        return false;
    }

    private static boolean isAuctionBuyMenu(ScreenHandler handler) {
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("confirm buy") || name.contains("cancel buy")) return true;
            var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
            if (lore != null) {
                for (net.minecraft.text.Text line : lore.lines()) {
                    String value = line.getString().toLowerCase(java.util.Locale.ROOT);
                    if (value.contains("confirm buy") || value.contains("cancel buy")) return true;
                }
            }
        }
        return false;
    }

    private static int navigationSlot(ScreenHandler handler, int slot) {
        int listingSlots = Math.max(0, handler.slots.size() - 36);
        if (slot >= listingSlots || slot >= handler.slots.size()) return -1;
        ItemStack button = handler.slots.get(slot).getStack();
        if (button.isEmpty()) return -1;
        String label = button.getName().getString().toLowerCase(java.util.Locale.ROOT);
        // Do not click arbitrary occupied slots: require a page-specific label.
        if (slot == NEXT_PAGE_SLOT && (label.contains("next") || label.contains("page"))) return slot;
        if (slot == PREVIOUS_PAGE_SLOT && (label.contains("previous") || label.contains("back") || label.contains("page"))) return slot;
        return -1;
    }

    private static boolean isSafeListingName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return !lower.contains("rank") && !lower.contains("upgrade") && !lower.contains("permanent")
                && !lower.contains("claim") && !lower.contains("sort") && !lower.contains("search")
                && !lower.contains("filter") && !lower.contains("page");
    }

    private static String fingerprint(ScreenHandler handler) {
        StringBuilder result = new StringBuilder();
        int listingSlots = Math.max(0, handler.slots.size() - 36);
        for (int i = 0; i < listingSlots; i++) {
            if (i == PREVIOUS_PAGE_SLOT || i == NEXT_PAGE_SLOT || i == SORT_ITEMS_SLOT) continue;
            ItemStack stack = handler.slots.get(i).getStack();
            if (!stack.isEmpty()) {
                result.append(i).append(':').append(stack.getItem()).append(':')
                        .append(stack.getName().getString()).append(':').append(stack.getCount())
                        .append(':').append(PriceTracker.readPrice(stack)).append(':');
                var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
                if (lore != null) {
                    for (net.minecraft.text.Text line : lore.lines()) result.append(line.getString()).append(';');
                }
                result.append('|');
            }
        }
        return result.toString();
    }

    private static void finish(String message) {
        PriceTracker.flush();
        scanning = false;
        autoBuyEnabled = false;
        awaitingServerPurchaseResult = false;
        AuctionSniperClient.msg(message);
    }
}
