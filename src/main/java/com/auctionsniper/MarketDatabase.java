package com.auctionsniper;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Local market index. Prices remain unknown until a reliable server price parser is available. */
public final class MarketDatabase {
    private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();
    private static int totalObservations;

    private MarketDatabase() {}

    public static void observe(String name, int count) {
        if (name == null || name.isBlank()) return;
        String key = name.trim();
        COUNTS.merge(key, Math.max(1, count), Integer::sum);
        totalObservations += Math.max(1, count);
        save();
    }

    public static Map<String, Integer> snapshot() {
        return new LinkedHashMap<>(COUNTS);
    }

    public static int uniqueItems() { return COUNTS.size(); }
    public static int totalObservations() { return totalObservations; }

    public static void load() {
        Path path = file();
        if (!Files.exists(path)) return;
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
            COUNTS.clear();
            totalObservations = 0;
            for (String key : properties.stringPropertyNames()) {
                if (!key.startsWith("item.")) continue;
                String name = new String(Base64.getDecoder().decode(key.substring(5)), StandardCharsets.UTF_8);
                int count = Integer.parseInt(properties.getProperty(key, "0"));
                if (count > 0) {
                    COUNTS.put(name, count);
                    totalObservations += count;
                }
            }
        } catch (Exception e) {
            AuctionSniperClient.msg("Could not load market database: " + e.getMessage());
        }
    }

    private static void save() {
        Properties properties = new Properties();
        COUNTS.forEach((name, count) -> properties.setProperty(
                "item." + Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)),
                Integer.toString(count)));
        try {
            Files.createDirectories(file().getParent());
            try (OutputStream out = Files.newOutputStream(file())) {
                properties.store(out, "AuctionSniper observed item index");
            }
        } catch (Exception e) {
            AuctionSniperClient.msg("Could not save market database: " + e.getMessage());
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("auction-sniper-market.properties");
    }
}
