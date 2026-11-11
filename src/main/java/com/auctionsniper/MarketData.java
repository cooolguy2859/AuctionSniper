package com.auctionsniper;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures information exposed by the server's /orders and /market menus.
 * It never sends purchase commands; it only records visible menu data.
 */
public final class MarketData {
    public record Entry(String item, long price, String source) {}
    private static final Pattern MONEY = Pattern.compile("(?i)(?:price|cost|amount|buy order|sell order|\\$)\\s*[:=]?\\s*\\$?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(k|m|b)?");
    private static final Map<String, Entry> ORDERS = new LinkedHashMap<>();
    private static final Map<String, Entry> MARKET = new LinkedHashMap<>();
    private static final List<String> ALERTS = new ArrayList<>();
    private static long inventoryValue;
    private static long lastAlertTick;
    private static long tick;
    private static boolean initialized;

    private MarketData() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        load();
        ClientTickEvents.END_CLIENT_TICK.register(MarketData::tick);
    }

    private static void tick(MinecraftClient client) {
        tick++;
        if (client.player == null) return;
        inventoryValue = 0;
        for (ItemStack stack : client.player.getInventory().getMainStacks()) {
            if (stack.isEmpty()) continue;
            Long average = PriceTracker.average(PriceTracker.itemKey(stack));
            if (average != null) inventoryValue = safeAdd(inventoryValue, average * stack.getCount());
        }
        if (client.currentScreen instanceof HandledScreen<?> screen) capture(screen.getTitle().getString(), client.player.currentScreenHandler);
        if (tick - lastAlertTick >= 40) {
            lastAlertTick = tick;
            checkAlerts(client);
        }
    }

    private static void capture(String title, ScreenHandler handler) {
        String lower = title.toLowerCase(Locale.ROOT);
        boolean orderMenu = lower.contains("order");
        boolean marketMenu = lower.contains("market") || lower.contains("shop");
        if (!orderMenu && !marketMenu) return;
        Map<String, Entry> target = orderMenu ? ORDERS : MARKET;
        String source = orderMenu ? "orders" : "market";
        boolean changed = false;
        int limit = Math.max(0, handler.slots.size() - 36);
        for (int i = 0; i < limit; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString();
            Long price = PriceTracker.readPrice(stack);
            String loreText = lore(stack);
            if (price == null) price = parseMoney(loreText);
            if (price == null || price <= 0) continue;
            String key = name.toLowerCase(Locale.ROOT) + "|" + price;
            Entry old = target.put(key, new Entry(name, price, source));
            if (old == null) changed = true;
        }
        if (changed) save();
    }

    private static String lore(ItemStack stack) {
        var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
        if (lore == null) return "";
        StringBuilder result = new StringBuilder();
        lore.lines().forEach(line -> result.append(' ').append(line.getString()));
        return result.toString();
    }

    private static Long parseMoney(String text) {
        Matcher matcher = MONEY.matcher(text);
        if (!matcher.find()) return null;
        try {
            double value = Double.parseDouble(matcher.group(1).replace(",", ""));
            String suffix = matcher.group(2);
            if (suffix != null) value *= switch (suffix.toLowerCase(Locale.ROOT)) {
                case "k" -> 1_000d;
                case "m" -> 1_000_000d;
                case "b" -> 1_000_000_000d;
                default -> 1d;
            };
            if (!Double.isFinite(value) || value <= 0 || value > Long.MAX_VALUE) return null;
            return (long) value;
        } catch (RuntimeException ignored) { return null; }
    }

    public static void addAlert(String item, long target) {
        if (item == null || item.isBlank() || target <= 0) return;
        ALERTS.removeIf(value -> value.startsWith(item.toLowerCase(Locale.ROOT) + "|"));
        ALERTS.add(item.toLowerCase(Locale.ROOT) + "|" + target);
        save();
        AuctionSniperClient.msg("Price alert saved for " + item + " at " + target + " coins.");
    }

    private static void checkAlerts(MinecraftClient client) {
        for (int i = ALERTS.size() - 1; i >= 0; i--) {
            String[] parts = ALERTS.get(i).split("\\|", 2);
            if (parts.length != 2) continue;
            long target;
            try { target = Long.parseLong(parts[1]); } catch (NumberFormatException ignored) { continue; }
            for (var entry : PriceTracker.snapshot().entrySet()) {
                String itemName = entry.getKey().substring(entry.getKey().indexOf('|') + 1);
                if (!itemName.equals(parts[0])) continue;
                Long average = PriceTracker.average(entry.getKey());
                if (average != null && average <= target) {
                    AuctionSniperClient.msg("PRICE ALERT: " + itemName + " average is " + average + " coins.");
                    ALERTS.remove(i);
                    save();
                }
                break;
            }
        }
    }

    public static long inventoryValue() { return inventoryValue; }
    public static List<Entry> orders() { return new ArrayList<>(ORDERS.values()); }
    public static List<Entry> market() { return new ArrayList<>(MARKET.values()); }
    public static int alertCount() { return ALERTS.size(); }

    private static long safeAdd(long a, long b) {
        try { return Math.addExact(a, b); } catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }

    private static void load() {
        Path path = file();
        if (!Files.exists(path)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
            readEntries(p, "orders.", ORDERS, "orders");
            readEntries(p, "market.", MARKET, "market");
            for (String key : p.stringPropertyNames()) if (key.startsWith("alert.")) ALERTS.add(p.getProperty(key));
        } catch (Exception e) {
            AuctionSniperClient.msg("Could not load market data: " + e.getMessage());
        }
    }

    private static void readEntries(Properties p, String prefix, Map<String, Entry> target, String source) {
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith(prefix)) continue;
            try {
                String decoded = new String(Base64.getDecoder().decode(key.substring(prefix.length())), StandardCharsets.UTF_8);
                String[] values = p.getProperty(key, "").split("\\|", 2);
                if (values.length == 2) target.put(decoded, new Entry(values[0], Long.parseLong(values[1]), source));
            } catch (Exception ignored) { }
        }
    }

    private static void save() {
        Properties p = new Properties();
        writeEntries(p, "orders.", ORDERS);
        writeEntries(p, "market.", MARKET);
        for (int i = 0; i < ALERTS.size(); i++) p.setProperty("alert." + i, ALERTS.get(i));
        try {
            Files.createDirectories(file().getParent());
            try (OutputStream out = Files.newOutputStream(file())) { p.store(out, "AuctionSniper captured server market data"); }
        } catch (Exception e) {
            AuctionSniperClient.msg("Could not save market data: " + e.getMessage());
        }
    }

    private static void writeEntries(Properties p, String prefix, Map<String, Entry> entries) {
        entries.forEach((key, value) -> p.setProperty(prefix + Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8)),
                value.item() + "|" + value.price()));
    }

    private static Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("auction-sniper-market-data.properties");
    }
}
