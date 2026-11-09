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

/** Reads explicit auction price lines and keeps a rolling price history per item. */
public final class PriceTracker {
    private static final Pattern PRICE = Pattern.compile(
            "(?i)^\\s*price\\s*:\\s*(?:\\$)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:coins?)?\\s*$");
    private static final Map<String, List<Long>> HISTORY = new HashMap<>();
    private static int pricedListings;

    private PriceTracker() {}

    public static Long readPrice(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return null;
        Long result = null;
        for (Text line : lore.lines()) {
            Matcher matcher = PRICE.matcher(line.getString());
            if (!matcher.matches()) continue;
            long parsed = parse(matcher.group(1));
            if (parsed <= 0) return null;
            if (result != null && result.longValue() != parsed) return null;
            result = parsed;
        }
        return result;
    }

    public static String itemKey(ItemStack stack) {
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

    public static Long average(String key) {
        List<Long> values = HISTORY.get(key);
        if (values == null || values.isEmpty()) return null;
        long total = 0;
        for (long value : values) {
            if (Long.MAX_VALUE - total < value) return null;
            total += value;
        }
        return total / values.size();
    }

    public static Long median(String key) {
        List<Long> values = HISTORY.get(key);
        if (values == null || values.isEmpty()) return null;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    public static int pricedListings() { return pricedListings; }

    private static long parse(String raw) {
        try {
            double amount = Double.parseDouble(raw.replace(",", ""));
            if (!Double.isFinite(amount) || amount <= 0 || amount > Long.MAX_VALUE) return -1;
            return (long) amount;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
