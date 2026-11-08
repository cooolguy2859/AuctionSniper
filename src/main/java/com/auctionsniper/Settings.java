package com.auctionsniper;

import java.io.*;
import java.nio.file.*;

public final class Settings {
    public String mode = "manual";
    public long minimumProfit = 200_000;
    public long maxBudget = 50_000_000;
    public boolean showHud = true;

    private Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().resolve("auction-sniper.properties");
    }

    public void load() {
        Path path = file();
        if (!Files.exists(path)) { save(); return; }
        java.util.Properties p = new java.util.Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
            mode = p.getProperty("mode", mode);
            minimumProfit = parseLong(p.getProperty("minimumProfit"), minimumProfit);
            maxBudget = parseLong(p.getProperty("maxBudget"), maxBudget);
            showHud = Boolean.parseBoolean(p.getProperty("showHud", Boolean.toString(showHud)));
        } catch (IOException e) {
            AuctionSniperClient.msg("Could not load config: " + e.getMessage());
        }
    }

    public void save() {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("mode", mode);
        p.setProperty("minimumProfit", Long.toString(minimumProfit));
        p.setProperty("maxBudget", Long.toString(maxBudget));
        p.setProperty("showHud", Boolean.toString(showHud));
        try {
            Files.createDirectories(file().getParent());
            try (OutputStream out = Files.newOutputStream(file())) {
                p.store(out, "AuctionSniper settings");
            }
        } catch (IOException e) {
            AuctionSniperClient.msg("Could not save config: " + e.getMessage());
        }
    }

    private static long parseLong(String value, long fallback) {
        try { return value == null ? fallback : Long.parseLong(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
