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
    private static int waitTicks;
    private static int pages;
    private static int totalListings;
    private static String lastFingerprint = "";
    private static boolean sortConfigured;
    private static int sortModeIndex;
    private static final String[] SORT_MODES = {"Highest Price", "Lowest Price", "Expiring Soonest", "Expiring Latest", "New Items First", "Old Items First"};
    private static String pendingFingerprint = "";
    private static boolean awaitingPageChange;
    private static boolean returningToFirstPage;
    private static boolean autoBuyEnabled;
    private static boolean awaitingBuyConfirmation;
    private static long pendingBuyPrice;
    private static String pendingBuyName = "";
    private static int attemptedBuys;
    private static final int MAX_BUYS_PER_SCAN = 1;
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int NEXT_PAGE_SLOT = 53;
    private static final int SORT_ITEMS_SLOT = 49;
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
            AuctionSniperClient.msg("Join a server before starting a scan."); return;
        }
        if (scanning) {
            AuctionSniperClient.msg("A scan is already running. Use /ahsniper cancel to stop it."); return;
        }
        scanning = true;
        waitTicks = 30;
        pages = 0;
        totalListings = 0;
        lastFingerprint = "";
        sortConfigured = false;
        sortModeIndex = 0;
        awaitingPageChange = false;
        returningToFirstPage = false;
        pendingFingerprint = "";
        attemptedBuys = 0;
        awaitingBuyConfirmation = false;
        pendingBuyPrice = 0;
        pendingBuyName = "";
        attemptedListings.clear();
        observedListings.clear();
        client.getNetworkHandler().sendChatCommand("ah");
        AuctionSniperClient.msg("Opening Auction House. Scanner reads listings; auto-buy remains off unless enabled.");
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
        int listingSlots = Math.max(0, handler.slots.size() - 36);

        if (awaitingBuyConfirmation) {
            int confirmSlot = findConfirmBuySlot(handler, pendingBuyPrice);
            if (confirmSlot >= 0 && client.interactionManager != null) {
                client.interactionManager.clickSlot(handler.syncId, confirmSlot, 0, SlotActionType.PICKUP, client.player);
                awaitingBuyConfirmation = false;
                attemptedBuys++;
                autoBuyEnabled = false;
                AuctionSniperClient.msg("Confirmed purchase: " + pendingBuyName + " for " + pendingBuyPrice + " coins.");
                waitTicks = 20;
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

        if (!sortConfigured) {
            if (SORT_ITEMS_SLOT >= listingSlots || SORT_ITEMS_SLOT >= handler.slots.size()
                    || handler.slots.get(SORT_ITEMS_SLOT).getStack().isEmpty()) {
                finish("Could not safely identify Sort Items in slot 49."); return;
            }
            if (sortModeIndex > 0) {
                if (client.interactionManager == null) { finish("Could not change sort mode."); return; }
                client.interactionManager.clickSlot(handler.syncId, SORT_ITEMS_SLOT, 0, SlotActionType.PICKUP, client.player);
                sortConfigured = true;
                waitTicks = 15;
                lastFingerprint = "";
                AuctionSniperClient.msg("Changing sort to " + SORT_MODES[sortModeIndex] + ".");
                return;
            }
            sortConfigured = true;
            AuctionSniperClient.msg("Scanning sort mode: " + SORT_MODES[sortModeIndex] + ".");
        }

        String fingerprint = fingerprint(handler);
        if (awaitingPageChange) {
            if (fingerprint.equals(pendingFingerprint)) {
                finish("Page did not change after navigation. Stopped safely."); return;
            }
            awaitingPageChange = false;
            pendingFingerprint = "";
        }

        if (returningToFirstPage) {
            int previous = navigationSlot(handler, PREVIOUS_PAGE_SLOT);
            if (previous >= 0) {
                if (client.interactionManager == null) { finish("Could not return to the first Auction House page."); return; }
                pendingFingerprint = fingerprint;
                awaitingPageChange = true;
                client.interactionManager.clickSlot(handler.syncId, previous, 0, SlotActionType.PICKUP, client.player);
                waitTicks = 15;
                AuctionSniperClient.msg("Moving back toward page 1 before changing sort.");
                return;
            }
            returningToFirstPage = false;
            sortModeIndex = (sortModeIndex + 1) % SORT_MODES.length;
            sortConfigured = false;
            lastFingerprint = "";
            waitTicks = 10;
            AuctionSniperClient.msg("Back at page 1. Next sort: " + SORT_MODES[sortModeIndex] + ".");
            return;
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
            if (fresh && autoBuyEnabled && attemptedBuys < MAX_BUYS_PER_SCAN
                    && PriceTracker.sampleCount(key) >= 8 && average != null
                    && price <= AuctionSniperClient.SETTINGS.maxBudget
                    && AuctionSniperClient.getOwnBalance() >= price
                    && average > price
                    && average - price >= AuctionSniperClient.SETTINGS.minimumProfit
                    && isSafeListingName(itemName)
                    && attemptedListings.add(key + "|" + price)) {
                if (client.interactionManager != null) {
                    pendingBuyPrice = price;
                    pendingBuyName = itemName;
                    awaitingBuyConfirmation = true;
                    client.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, client.player);
                    waitTicks = 15;
                    AuctionSniperClient.msg("Opening purchase confirmation for " + itemName + " at " + price + " coins.");
                    return;
                }
            }
            if (fresh) PriceTracker.observe(key, price);
            AuctionSniperClient.msg("Page " + (pages + 1) + " | " + itemName + " | " + price + " coins");
        }

        pages++;
        totalListings += found;
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

        returningToFirstPage = true;
        int previous = navigationSlot(handler, PREVIOUS_PAGE_SLOT);
        if (previous >= 0) {
            if (client.interactionManager == null) { finish("Could not return to the first Auction House page."); return; }
            pendingFingerprint = fingerprint;
            awaitingPageChange = true;
            client.interactionManager.clickSlot(handler.syncId, previous, 0, SlotActionType.PICKUP, client.player);
            waitTicks = 15;
            AuctionSniperClient.msg("Reached the last page. Returning to page 1 before changing sort.");
        } else {
            returningToFirstPage = false;
            sortModeIndex = (sortModeIndex + 1) % SORT_MODES.length;
            sortConfigured = false;
            lastFingerprint = "";
            waitTicks = 10;
            AuctionSniperClient.msg("Already at page 1. Next sort: " + SORT_MODES[sortModeIndex] + ".");
        }
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
            if (!value.contains("click to purchase")) continue;
            if (!value.contains(Long.toString(expectedPrice))) continue;
            return i;
        }
        return -1;
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
            if (!stack.isEmpty()) result.append(i).append(':').append(stack.getItem()).append(':')
                    .append(stack.getName().getString()).append(':').append(stack.getCount()).append('|');
        }
        return result.toString();
    }

    private static void finish(String message) {
        scanning = false;
        autoBuyEnabled = false;
        AuctionSniperClient.msg(message);
    }
}
