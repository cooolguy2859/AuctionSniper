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
    private static int sweepPages;
    private static String lastFingerprint = "";
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
        movingForward = true;
        waitTicks = 30;
        pages = 0;
        totalListings = 0;
        sweepPages = 0;
        lastFingerprint = "";
        uniqueItems.clear();

        client.getNetworkHandler().sendChatCommand("ah");
        AuctionSniperClient.msg("Opening Auction House. Continuous scan started; close the menu to stop.");
    }

    public static void cancel() {
        if (!scanning) {
            AuctionSniperClient.msg("No scan is running.");
            return;
        }
        finish("Scan cancelled. Pages visited: " + pages + ", listings seen: " + totalListings + ".");
    }

    private static void tick(MinecraftClient client) {
        if (!scanning) return;
        if (client.player == null || client.getNetworkHandler() == null) {
            finish("Scan stopped because you left the server.");
            return;
        }
        if (waitTicks-- > 0) return;

        if (!(client.currentScreen instanceof HandledScreen<?>)) {
            finish("Auction House closed. Continuous scan stopped.");
            return;
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        String fingerprint = fingerprint(handler);
        if (fingerprint.equals(lastFingerprint) && !lastFingerprint.isEmpty()) {
            finish("Page did not change after navigation. Stopped to avoid getting stuck.");
            return;
        }
        lastFingerprint = fingerprint;

        int listingSlots = Math.max(0, handler.slots.size() - 36);
        int found = 0;
        for (int i = 0; i < listingSlots; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            found++;
            String itemName = stack.getName().getString();
            uniqueItems.add(itemName);
            AuctionSniperClient.msg("Page visit " + (pages + 1) + " | " + itemName + " x" + stack.getCount());
        }

        pages++;
        sweepPages++;
        totalListings += found;
        AuctionSniperClient.msg("Page visit " + pages + " scanned: " + found + " non-empty slots.");

        int navigationSlot = movingForward ? findNextButton(handler) : findPreviousButton(handler);
        if (navigationSlot < 0 && movingForward) {
            movingForward = false;
            navigationSlot = findPreviousButton(handler);
            AuctionSniperClient.msg("Reached the last page. Scanning backward...");
        } else if (navigationSlot < 0) {
            movingForward = true;
            sweepPages = 0;
            navigationSlot = findNextButton(handler);
            AuctionSniperClient.msg("Reached the first page. Starting another forward scan...");
        }

        if (navigationSlot < 0) {
            finish("Could not find a page navigation button. Pages visited: " + pages
                    + ", unique item types: " + uniqueItems.size() + ".");
            return;
        }
        if (client.interactionManager == null) {
            finish("Could not interact with the Auction House menu.");
            return;
        }

        client.interactionManager.clickSlot(handler.syncId, navigationSlot, 0, SlotActionType.PICKUP, client.player);
        waitTicks = 15;
    }

    private static int findNextButton(ScreenHandler handler) {
        return findButton(handler, true);
    }

    private static int findPreviousButton(ScreenHandler handler) {
        return findButton(handler, false);
    }

    private static int findButton(ScreenHandler handler, boolean next) {
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString().toLowerCase();
            if (next) {
                if (name.contains("next") || name.contains("forward")
                        || name.equals(">") || name.contains("»") || name.contains("→")) return i;
            } else if (name.contains("previous") || name.contains("back")
                    || name.equals("<") || name.contains("«") || name.contains("←")) {
                return i;
            }
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
