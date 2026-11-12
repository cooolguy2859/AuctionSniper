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
 * Reads visible custom-order summaries from the server's /orders menu.
 * It records average order value (total value / current order count), not a
 * per-item unit price, because the menu does not expose order quantities.
 */
public final class OrderScanner {
    private static final Pattern TOTAL = Pattern.compile(
            "(?i)total\\s*value\\s*:\\s*([0-9][0-9,.]*\\s*[kmb]?)\\s*coins?");
    private static final Pattern COUNT = Pattern.compile(
            "(?i)\\(([0-9][0-9,]*)\\s*current\\s*orders?\\)");
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
        if (now - lastScan < 900L) return;
        lastScan = now;

        ScreenHandler handler = client.player.currentScreenHandler;
        int menuSlots = Math.max(0, handler.slots.size() - 36);
        int pageRecorded = 0;
        for (int i = 0; i < menuSlots; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
            if (lore == null) continue;
            StringBuilder lines = new StringBuilder();
            for (Text line : lore.lines()) lines.append(' ').append(line.getString());
            Matcher totalMatch = TOTAL.matcher(lines);
            Matcher countMatch = COUNT.matcher(lines);
            if (!totalMatch.find() || !countMatch.find()) continue;

            long total = parseAmount(totalMatch.group(1));
            long count;
            try { count = Long.parseLong(countMatch.group(1).replace(",", "")); }
            catch (NumberFormatException ignored) { continue; }
            if (total <= 0 || count <= 0) continue;

            long averageOrderValue = Math.max(1L, total / count);
            String itemName = stack.getName().getString();
            String itemId = PriceTracker.itemKey(stack);
            String fingerprint = itemId + "|" + total + "|" + count;
            if (!SEEN.add(fingerprint)) continue;

            PriceTracker.observe(itemId, averageOrderValue);
            MarketApi.observe(itemId, itemName, averageOrderValue);
            pageRecorded++;
            recorded++;
        }
        if (pageRecorded > 0) {
            PriceTracker.flush();
            AuctionSniperClient.msg("Orders scanned: recorded " + pageRecorded
                    + " new average order values (" + recorded + " total this session).");
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
