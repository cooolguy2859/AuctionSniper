package com.auctionsniper;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.HashSet;
import java.util.Set;

public final class AuctionScanner {
    private static boolean scanning;
    private static int waitTicks;
    private static int pages;
    private static int totalListings;
    private static final Set<String> seenPages = new HashSet<>();

    private AuctionScanner() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
    }

    public static void startScan() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) {
            AuctionSniperClient.msg("Join a server before starting a scan.");
            return;
        }
        if (scanning) {
            AuctionSniperClient.msg("A scan is already running.");
            return;
        }

        scanning = true;
        waitTicks = 20;
        pages = 0;
        totalListings = 0;
        seenPages.clear();

        // Send the server command as if the player typed /ah.
        client.getNetworkHandler().sendChatCommand("ah");
        AuctionSniperClient.msg("Opening Auction House...");
    }

    private static void tick(MinecraftClient client) {
        if (!scanning || client.player == null) return;
        if (waitTicks-- > 0) return;

        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            finish("Auction House did not open. Try /ahsniper scan again.");
            return;
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        String fingerprint = fingerprint(handler);
        if (!seenPages.add(fingerprint)) {
            finish("Reached a page that was already scanned. Pages: " + pages
                    + ", listings found: " + totalListings + ".");
            return;
        }

        int listingSlots = Math.max(0, handler.slots.size() - 36);
        int found = 0;
        for (int i = 0; i < listingSlots; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (!stack.isEmpty()) {
                found++;
                AuctionSniperClient.msg("Page " + (pages + 1) + " | " + stack.getName().getString()
                        + " x" + stack.getCount());
            }
        }

        pages++;
        totalListings += found;
        AuctionSniperClient.msg("Scanned page " + pages + " (" + found + " listings).");

        int nextSlot = findNextButton(handler);
        if (nextSlot < 0) {
            finish("No next-page button found. Scan ended. Pages: " + pages
                    + ", listings found: " + totalListings + ".");
            return;
        }

        if (client.interactionManager == null) {
            finish("Could not interact with the Auction House menu.");
            return;
        }

        client.interactionManager.clickSlot(handler.syncId, nextSlot, 0, SlotActionType.PICKUP, client.player);
        waitTicks = 12;
    }

    private static int findNextButton(ScreenHandler handler) {
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString().toLowerCase();
            if (name.contains("next") || name.contains("forward")
                    || name.equals(">") || name.contains("»") || name.contains("→")) {
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
                result.append(i).append(':').append(stack.getName().getString())
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
