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
    private static int emptyPageCount;
    private static final Set<String> seenPages = new HashSet<>();
    private static final Set<String> uniqueItems = new HashSet<>();

    private AuctionScanner() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(AuctionScanner::tick);
    }

    public static boolean isScanning() { return scanning; }
    public static int getPages() { return pages; }

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
        waitTicks = 30;
        pages = 0;
        totalListings = 0;
        emptyPageCount = 0;
        seenPages.clear();
        uniqueItems.clear();

        client.getNetworkHandler().sendChatCommand("ah");
        AuctionSniperClient.msg("Opening Auction House. Scan started...");
    }

    public static void cancel() {
        if (!scanning) {
            AuctionSniperClient.msg("No scan is running.");
            return;
        }
        finish("Scan cancelled. Pages: " + pages + ", listings: " + totalListings + ".");
    }

    private static void tick(MinecraftClient client) {
        if (!scanning || client.player == null) return;
        if (waitTicks-- > 0) return;

        if (!(client.currentScreen instanceof HandledScreen<?>)) {
            finish("Auction House menu was not detected. Open it and try again.");
            return;
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        String fingerprint = fingerprint(handler);
        if (!seenPages.add(fingerprint)) {
            finish("Repeated page detected; stopping to avoid a loop. Pages: " + pages
                    + ", listings: " + totalListings + ", unique item types: " + uniqueItems.size() + ".");
            return;
        }

        int listingSlots = Math.max(0, handler.slots.size() - 36);
        int found = 0;
        for (int i = 0; i < listingSlots; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            found++;
            String itemName = stack.getName().getString();
            uniqueItems.add(itemName);
            AuctionSniperClient.msg("Page " + (pages + 1) + " | " + itemName + " x" + stack.getCount());
        }

        pages++;
        totalListings += found;
        emptyPageCount = found == 0 ? emptyPageCount + 1 : 0;
        AuctionSniperClient.msg("Page " + pages + " scanned: " + found + " non-empty slots.");

        if (pages >= 100) {
            finish("Safety limit of 100 pages reached. Listings: " + totalListings + ".");
            return;
        }
        if (emptyPageCount >= 2) {
            finish("Two empty pages found. Listings: " + totalListings + ".");
            return;
        }

        int nextSlot = findNextButton(handler);
        if (nextSlot < 0) {
            finish("Could not identify a Next button. Pages: " + pages + ", listings: "
                    + totalListings + ", unique item types: " + uniqueItems.size() + ".");
            return;
        }
        if (client.interactionManager == null) {
            finish("Could not interact with the Auction House menu.");
            return;
        }

        client.interactionManager.clickSlot(handler.syncId, nextSlot, 0, SlotActionType.PICKUP, client.player);
        waitTicks = 15;
    }

    private static int findNextButton(ScreenHandler handler) {
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString().toLowerCase();
            if (name.contains("next") || name.contains("forward")
                    || name.equals(">") || name.contains("»") || name.contains("→")) return i;
        }
        return -1;
    }

    private static String fingerprint(ScreenHandler handler) {
        StringBuilder result = new StringBuilder();
        int listingSlots = Math.max(0, handler.slots.size() - 36);
        for (int i = 0; i < listingSlots; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (!stack.isEmpty()) {
                result.append(i).append(':').append(stack.getItem().toString())
                        .append(':').append(stack.getName().getString())
                        .append(':').append(stack.getCount()).append('|');
            }
        }
        return result.toString();
    }

    private static void finish(String message) {
        scanning = false;
        AuctionSniperClient.msg(message);
    }
}
