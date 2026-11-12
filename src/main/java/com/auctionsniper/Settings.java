package com.auctionsniper;

import java.io.*;
import java.nio.file.*;

public final class Settings {
    private static final String DEFAULT_CLOUD_API_KEY = "728abd90e57d5d6b7f93f4746eb5765b8df17d470a7f14eabfa705b643b811ba";
    public String mode = "manual";
    public long minimumProfit = 200_000;
    public long maxBudget = 200_000;
    public boolean showHud = true;
    public int theme = 0;
    private static final String CLOUD_API_URL = "https://auction-sniper-api.firewolfjm10.workers.dev";
    public String cloudApiUrl = CLOUD_API_URL;
    public String cloudApiKey = DEFAULT_CLOUD_API_KEY;
    public String serverId = "lifesteal.net";
    public String serverHost = "lifesteal.net";

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
            theme = (int) Math.max(0, Math.min(2, parseLong(p.getProperty("theme"), theme)));
            // Ignore any legacy URL saved by an older build; the API is pinned to our Worker.
            cloudApiUrl = CLOUD_API_URL;
            // Ignore any previously saved key; both clients use the key configured on our Worker.
            cloudApiKey = DEFAULT_CLOUD_API_KEY;
            serverId = p.getProperty("serverId", serverId);
            serverHost = p.getProperty("serverHost", serverHost).trim();
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
        p.setProperty("theme", Integer.toString(theme));
        p.setProperty("cloudApiUrl", CLOUD_API_URL);
        p.setProperty("cloudApiKey", DEFAULT_CLOUD_API_KEY);
        p.setProperty("serverId", serverId);
        p.setProperty("serverHost", serverHost);
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
