package com.auctionsniper;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict auction-price extraction. Never guesses a price from unrelated numbers. */
public final class PriceTracker {
    private static final Pattern PRICE = Pattern.compile(
            "(?i)\\b(?:buy\\s*price|listing\\s*price|price|cost)\\s*[:=-]\\s*(?:\\$|⛃|coins?\\s*)?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmb])?\\b");
    private static final Map<String, List<Long>> HISTORY = new HashMap<>();
    private static int pricedListings;

    private PriceTracker() {}

    public static Long readPrice(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return null;
        Long result = null;
        for (Text line : lore.lines()) {
            String raw = line.getString();
            Matcher m = PRICE.matcher(raw);
            if (!m.find()) continue;
            long parsed = parse(m.group(1), m.group(2));
            if (parsed <= 0) return null;
            if (result != null && result != parsed) return null;
            result = parsed;
        }
        return result;
    }

    public static String itemKey(ItemStack stack) {
        // Item ID and display name must match. Different named items are separate markets.
        return stack.getItem().toString() + "|" + stack.getName().getString().toLowerCase(Locale.ROOT);
    }

    public static void observe(String key, long price) {
        if (price <= 0) return;
        List<Long> values = HISTORY.computeIfAbsent(key, unused -> new ArrayList<>());
        values.add(price);
        if (values.size() > 100) values.remove(0);
        pricedListings++;
    }

    public static int sampleCount(String key) {
        return HISTORY.getOrDefault(key, List.of()).size();
    }

    public static Long median(String key) {
        List<Long> values = HISTORY.get(key);
        if (values == null || values.size() < 8) return null;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    public static int pricedListings() { return pricedListings; }

    private static long parse(String raw, String suffix) {
        try {
            double amount = Double.parseDouble(raw.replace(",", ""));
            if (!Double.isFinite(amount) || amount <= 0) return -1;
            long multiplier = suffix == null ? 1 : switch (suffix.toLowerCase(Locale.ROOT)) {
                case "k" -> 1_000L;
                case "m" -> 1_000_000L;
                case "b" -> 1_000_000_000L;
                default -> 1L;
            };
            double total = amount * multiplier;
            if (total > Long.MAX_VALUE) return -1;
            return (long) total;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
