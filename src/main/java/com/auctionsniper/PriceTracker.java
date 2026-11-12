package com.auctionsniper;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persistent per-item auction price history. */
public final class PriceTracker {
    private static final Pattern PRICE = Pattern.compile(
            "(?i)^\\s*price\\s*:\\s*(?:\\$)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:coins?)?\\s*$");
    private static final Map<String, List<Long>> HISTORY = new HashMap<>();
    private static final Map<String, PurchaseRecord> PURCHASES = new java.util.LinkedHashMap<>();
    public record PurchaseRecord(long quantity, long totalCost, long lastPaid) {
        public long averagePaid() { return quantity <= 0 ? 0 : totalCost / quantity; }
    }
    private static int pricedListings;
    private static boolean dirty;

    private PriceTracker() {}

    public static void load() {
        Path path = file();
        if (!Files.exists(path)) return;
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
            HISTORY.clear();
            PURCHASES.clear();
            pricedListings = 0;
            for (String property : properties.stringPropertyNames()) {
                if (!property.startsWith("prices.")) continue;
                String key = new String(Base64.getDecoder().decode(property.substring(7)), StandardCharsets.UTF_8);
                List<Long> values = new ArrayList<>();
                for (String part : properties.getProperty(property, "").split(",")) {
                    try {
                        long value = Long.parseLong(part);
                        if (value > 0) values.add(value);
                    } catch (NumberFormatException ignored) { }
                }
                if (!values.isEmpty()) {
                    HISTORY.put(key, values);
                    pricedListings += values.size();
                }
            }
            for (String property : properties.stringPropertyNames()) {
                if (!property.startsWith("bought.")) continue;
                String key = new String(Base64.getDecoder().decode(property.substring(7)), StandardCharsets.UTF_8);
                String[] parts = properties.getProperty(property, "").split(",");
                if (parts.length == 3) {
                    try {
                        long quantity = Long.parseLong(parts[0]);
                        long totalCost = Long.parseLong(parts[1]);
                        long lastPaid = Long.parseLong(parts[2]);
                        if (quantity > 0 && totalCost >= 0 && lastPaid > 0)
                            PURCHASES.put(key, new PurchaseRecord(quantity, totalCost, lastPaid));
                    } catch (NumberFormatException ignored) { }
                }
            }
        } catch (Exception e) {
            AuctionSniperClient.msg("Could not load saved price history: " + e.getMessage());
        }
    }

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
        dirty = true;
    }

    public static int sampleCount(String key) { return HISTORY.getOrDefault(key, List.of()).size(); }

    /** Record only a purchase that the server explicitly confirmed. */
    public static void recordPurchase(String key, long paid) {
        if (key == null || key.isBlank() || paid <= 0) return;
        PurchaseRecord old = PURCHASES.getOrDefault(key, new PurchaseRecord(0, 0, 0));
        long quantity = old.quantity() == Long.MAX_VALUE ? Long.MAX_VALUE : old.quantity() + 1;
        long total;
        try { total = Math.addExact(old.totalCost(), paid); }
        catch (ArithmeticException overflow) { total = Long.MAX_VALUE; }
        PURCHASES.put(key, new PurchaseRecord(quantity, total, paid));
        dirty = true;
        flush();
    }

    public static Map<String, PurchaseRecord> purchases() {
        return new java.util.LinkedHashMap<>(PURCHASES);
    }

    public static Long latest(String key) {
        List<Long> values = HISTORY.get(key);
        return values == null || values.isEmpty() ? null : values.get(values.size() - 1);
    }

    public static Long average(String key) {
        List<Long> values = HISTORY.get(key);
        if (values == null || values.isEmpty()) return null;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return sorted.get(middle);
        return sorted.get(middle - 1) / 2 + sorted.get(middle) / 2;
    }

    public static Long median(String key) {
        List<Long> values = HISTORY.get(key);
        if (values == null || values.isEmpty()) return null;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    public static Long lowest(String key) {
        List<Long> values = HISTORY.get(key);
        return values == null || values.isEmpty() ? null : values.stream().mapToLong(Long::longValue).min().orElse(0);
    }

    public static Long highest(String key) {
        List<Long> values = HISTORY.get(key);
        return values == null || values.isEmpty() ? null : values.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    public static Map<String, List<Long>> snapshot() {
        Map<String, List<Long>> copy = new java.util.LinkedHashMap<>();
        HISTORY.forEach((key, values) -> copy.put(key, List.copyOf(values)));
        return copy;
    }

    public static int pricedListings() { return pricedListings; }

    /** Persist observations in one batch after a page is scanned. */
    public static void flush() {
        if (!dirty) return;
        save();
        dirty = false;
    }

    private static void save() {
        Properties properties = new Properties();
        HISTORY.forEach((key, values) -> {
            StringBuilder joined = new StringBuilder();
            for (long value : values) {
                if (joined.length() > 0) joined.append(',');
                joined.append(value);
            }
            properties.setProperty("prices." + Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8)),
                    joined.toString());
        });
        PURCHASES.forEach((key, record) -> properties.setProperty(
                "bought." + Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8)),
                record.quantity() + "," + record.totalCost() + "," + record.lastPaid()));
        try {
            Files.createDirectories(file().getParent());
            try (OutputStream out = Files.newOutputStream(file())) {
                properties.store(out, "AuctionSniper saved per-item price history");
            }
        } catch (Exception e) {
            AuctionSniperClient.msg("Could not save price history: " + e.getMessage());
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("auction-sniper-prices.properties");
    }

    private static long parse(String raw) {
        try {
            double amount = Double.parseDouble(raw.replace(",", ""));
            if (!Double.isFinite(amount) || amount <= 0 || amount > Long.MAX_VALUE) return -1;
            return (long) amount;
        } catch (NumberFormatException ex) { return -1; }
    }
}
