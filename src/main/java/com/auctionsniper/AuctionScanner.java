package com.auctionsniper;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.HashSet;
import java.util.Set;

public final class AuctionScanner {
    private static boolean scanning;
    private static boolean movingForward = true;
    private static int waitTicks;
    private static int pages;
    private static int totalListings;
    private static String lastFingerprint = "";
    private static final Set<String> uniqueItems = new HashSet<>();
    // Menu slot indexes: 1 = previous page, 8 = next page.
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int NEXT_PAGE_SLOT = 53;
    private static final int SORT_ITEMS_SLOT = 49;
    private static boolean sortConfigured;
    private static boolean autoBuyEnabled;
    private static int attemptedBuys;
    private static final int MAX_BUYS_PER_SCAN = 1;
    private static final Set<String> attemptedListings = new HashSet<>();
    private static final Set<String> observedListings = new HashSet<>();

    private AuctionScanner() {}

    public static void initialize() { ClientTickEvents.END_CLIENT_TICK.register(AuctionScanner::tick); }
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
            AuctionSniperClient.msg("Join a server before starting a scan.");
            return;
        }
        if (scanning) {
            AuctionSniperClient.msg("A scan is already running. Use /ahsniper cancel to stop it.");
            return;
        }
        scanning = true;
        movingForward = true;
        waitTicks = 30;
        pages = 0;
        totalListings = 0;
        lastFingerprint = "";
        sortConfigured = false;
        uniqueItems.clear();
        attemptedBuys = 0;
        attemptedListings.clear();
        observedListings.clear();
        client.getNetworkHandler().sendChatCommand("ah");
        AuctionSniperClient.msg("Opening Auction House. Scan only reads listings; it will not click auction items.");
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
        if (!(client.currentScreen instanceof HandledScreen<?>)) {
            finish("Auction House closed. Scan stopped."); return;
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        if (!sortConfigured) {
            int listingSlots = Math.max(0, handler.slots.size() - 36);
            if (SORT_ITEMS_SLOT >= listingSlots || SORT_ITEMS_SLOT >= handler.slots.size()
                    || handler.slots.get(SORT_ITEMS_SLOT).getStack().isEmpty()) {
                finish("Could not find Sort Items control in slot 49. Stopped safely.");
                return;
            }
            if (client.interactionManager == null) {
                finish("Could not interact with Sort Items.");
                return;
            }
            // The supplied menu screenshot shows Highest Price selected; one toggle selects Lowest Price.
            client.interactionManager.clickSlot(handler.syncId, SORT_ITEMS_SLOT, 0,
                    SlotActionType.PICKUP, client.player);
            sortConfigured = true;
            waitTicks = 15;
            AuctionSniperClient.msg("Changed Sort Items from Highest Price to Lowest Price.");
            return;
        }
        String fingerprint = fingerprint(handler);
        if (fingerprint.equals(lastFingerprint) && !lastFingerprint.isEmpty()) {
            finish("Page did not change after navigation. Stopped safely."); return;
        }
        lastFingerprint = fingerprint;

        int listingSlots = Math.max(0, handler.slots.size() - 36);
        int found = 0;
        for (int i = 0; i < listingSlots; i++) {
            if (i >= PREVIOUS_PAGE_SLOT && i <= NEXT_PAGE_SLOT) continue;
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            found++;
            String itemName = stack.getName().getString();
            uniqueItems.add(itemName);
            MarketDatabase.observe(itemName, stack.getCount());
            Long listingPrice = PriceTracker.readPrice(stack);
            if (listingPrice != null) {
                String key = PriceTracker.itemKey(stack);
                Long median = PriceTracker.median(key);
                String observation = fingerprint + "|" + i + "|" + key + "|" + listingPrice;
                boolean newObservation = observedListings.add(observation);
                if (newObservation && autoBuyEnabled && attemptedBuys < MAX_BUYS_PER_SCAN
                        && median != null && PriceTracker.sampleCount(key) >= 8
                        && listingPrice <= AuctionSniperClient.SETTINGS.maxBudget
                        && AuctionSniperClient.getOwnBalance() >= listingPrice
                        && median > listingPrice
                        && median - listingPrice >= AuctionSniperClient.SETTINGS.minimumProfit
                        && listingPrice <= median / 2
                        && isSafeListingName(itemName)
                        && attemptedListings.add(key + "|" + listingPrice)) {
                    if (client.interactionManager != null) {
                        // A click may open a server confirmation menu; never click that menu automatically.
                        client.interactionManager.clickSlot(handler.syncId, i, 0,
                                SlotActionType.PICKUP, client.player);
                        attemptedBuys++;
                        autoBuyEnabled = false;
                        finish("Clicked one qualifying listing (" + listingPrice
                                + " coins). Auto-buy disabled. Review any server confirmation screen manually.");
                        return;
                    }
                }
                if (newObservation) PriceTracker.observe(key, listingPrice);
            }
            AuctionSniperClient.msg("Page " + (pages + 1) + " | " + itemName + " x" + stack.getCount());
        }
        pages++;
        totalListings += found;
        AuctionSniperClient.msg("Page " + pages + " scanned: " + found + " non-empty slots.");

        int nav = findNavigationButton(handler, movingForward);
        if (nav < 0 && movingForward) {
            movingForward = false;
            nav = findNavigationButton(handler, false);
            if (nav >= 0) AuctionSniperClient.msg("Reached last page. Going backward.");
        } else if (nav < 0) {
            movingForward = true;
            nav = findNavigationButton(handler, true);
            if (nav >= 0) AuctionSniperClient.msg("Reached first page. Starting next scan cycle.");
        }
        if (nav < 0) {
            finish("No clearly labelled page button found. Stopped rather than risk clicking an auction listing."); return;
        }
        if (client.interactionManager == null) {
            finish("Could not interact with the Auction House menu."); return;
        }
        client.interactionManager.clickSlot(handler.syncId, nav, 0, SlotActionType.PICKUP, client.player);
        waitTicks = 15;
    }

    private static int findNavigationButton(ScreenHandler handler, boolean next) {
        int listingSlots = Math.max(0, handler.slots.size() - 36);
        int slot = next ? NEXT_PAGE_SLOT : PREVIOUS_PAGE_SLOT;
        if (slot >= listingSlots || slot >= handler.slots.size()) return -1;
        if (handler.slots.get(slot).getStack().isEmpty()) return -1;
        return slot;
    }

    private static boolean isSafeListingName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return !lower.contains("rank") && !lower.contains("upgrade")
                && !lower.contains("permanent") && !lower.contains("claim")
                && !lower.contains("sort") && !lower.contains("search")
                && !lower.contains("filter") && !lower.contains("page");
    }

    private static String fingerprint(ScreenHandler handler) {
        StringBuilder result = new StringBuilder();
        int listingSlots = Math.max(0, handler.slots.size() - 36);
        for (int i = 0; i < listingSlots; i++) {
            if (i >= PREVIOUS_PAGE_SLOT && i <= NEXT_PAGE_SLOT) continue;
            ItemStack stack = handler.slots.get(i).getStack();
            if (!stack.isEmpty()) result.append(i).append(':').append(stack.getItem().toString())
                    .append(':').append(stack.getName().getString()).append(':').append(stack.getCount()).append('|');
        }
        return result.toString();
    }

    private static void finish(String message) {
        scanning = false;
        autoBuyEnabled = false;
        AuctionSniperClient.msg(message);
    }
}
