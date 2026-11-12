package com.auctionsniper;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.screen.ScreenHandler;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects individual order prices from the visible /orders Custom Orders menu.
 * Order entries expose their details in item lore: "Price: ... Coins per unit".
 * Summary entries ("Total value" / "current orders") are deliberately not used
 * as item prices because they are aggregate values, not unit prices.
 */
public final class OrderScanner {
    private static final Pattern UNIT_PRICE = Pattern.compile(
            "(?i)price\\s*:\\s*([0-9][0-9,.]*\\s*[kmb]?)\\s*coins?\\s*per\\s*unit");
    private static final Pattern TOTAL = Pattern.compile(
            "(?i)total\\s*:\\s*([0-9][0-9,.]*\\s*[kmb]?)\\s*coins?");
    private static final Pattern PROGRESS = Pattern.compile(
            "(?i)progress\\s*:\\s*([0-9,]+)\\s*/\\s*([0-9,]+)");
    private static final Set<String> SEEN = new HashSet<>();
    private static long lastScan;
    private static int recorded;

    private OrderScanner() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(OrderScanner::tick);
    }

    private static void tick(MinecraftClient client) {
        if (client.player == null || !(client.currentScreen instanceof HandledScreen<?>)) return;
        String title = client.currentScreen.getTitle().getString().toLowerCase(Locale.ROOT);
        if (!title.contains("custom orders")) return;

        long now = System.currentTimeMillis();
        if (now - lastScan < 700L) return;
        lastScan = now;

        ScreenHandler handler = client.player.currentScreenHandler;
        int menuSlots = Math.max(0, handler.slots.size() - 36);
        int pageRecorded = 0;
        int orderEntriesSeen = 0;

        for (int i = 0; i < menuSlots; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;

            var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
            if (lore == null || lore.lines().isEmpty()) continue;

            StringBuilder lines = new StringBuilder();
            for (Text line : lore.lines()) lines.append(' ').append(line.getString());
            String detail = lines.toString();

            Matcher priceMatch = UNIT_PRICE.matcher(detail);
            if (!priceMatch.find()) continue;
            orderEntriesSeen++;

            long unitPrice = parseAmount(priceMatch.group(1));
            if (unitPrice <= 0) continue;

            Matcher totalMatch = TOTAL.matcher(detail);
            long total = -1L;
            if (totalMatch.find()) total = parseAmount(totalMatch.group(1));

            Matcher progressMatch = PROGRESS.matcher(detail);
            String progress = progressMatch.find()
                    ? progressMatch.group(1) + "/" + progressMatch.group(2) : "unknown";

            String itemName = stack.getName().getString();
            String itemId = PriceTracker.itemKey(stack);
            String fingerprint = itemId + "|unit=" + unitPrice + "|total=" + total + "|progress=" + progress;
            if (!SEEN.add(fingerprint)) continue;

            PriceTracker.observe(itemId, unitPrice);
            MarketApi.observe(itemId, itemName, unitPrice);
            pageRecorded++;
            recorded++;
        }

        if (pageRecorded > 0) {
            PriceTracker.flush();
            AuctionSniper.msg("Orders scan: collected " + pageRecorded
                    + " new per-unit prices (" + recorded + " total this session).");
        } else if (orderEntriesSeen > 0) {
            AuctionSniper.msg("Orders scan: found " + orderEntriesSeen
                    + " order entries, but no new prices to add.");
        }
    }

    private static long parseAmount(String raw) {
        try {
            String value = raw.toLowerCase(Locale.ROOT).replace(",", "").replace(" ", "");
            long multiplier = 1L;
            if (value.endsWith("k")) { multiplier = 1_000L; value = value.substring(0, value.length() - 1); }
            else if (value.endsWith("m")) { multiplier = 1_000_000L; value = value.substring(0, value.length() - 1); }
            else if (value.endsWith("b")) { multiplier = 1_000_000_000L; value = value.substring(0, value.length() - 1); }
            return Math.round(Double.parseDouble(value) * multiplier);
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }
}
