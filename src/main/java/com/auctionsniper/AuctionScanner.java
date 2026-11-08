package com.auctionsniper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayList;
import java.util.List;

public final class AuctionScanner {
    private AuctionScanner() {}

    public static void scan() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !(client.currentScreen instanceof HandledScreen<?>)) {
            AuctionSniperClient.msg("Open the Auction House GUI first, then run /ah scan.");
            return;
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        List<String> found = new ArrayList<>();
        int nonEmpty = 0;

        // Chest-style menus usually place auction slots before the player's 36 inventory slots.
        int listingSlots = Math.max(0, handler.slots.size() - 36);
        for (int i = 0; i < listingSlots; i++) {
            Slot slot = handler.slots.get(i);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            nonEmpty++;
            found.add("Slot " + i + ": " + stack.getName().getString() + " x" + stack.getCount());
        }

        AuctionSniperClient.msg("Visible-page scan complete: " + nonEmpty + " non-empty listing slots.");
        for (String line : found) AuctionSniperClient.msg(line);
        AuctionSniperClient.msg("Names/counts only. Price/lore parsing and page navigation are not implemented.");
    }
}
