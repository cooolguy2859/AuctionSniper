package com.auctionsniper;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AuctionSniperClient implements ClientModInitializer {
    public static final Settings SETTINGS = new Settings();
    public static final BalanceCache BALANCES = new BalanceCache();
    private static long ownBalance = -1;
    public static long getOwnBalance() { return ownBalance; }
    private static final Pattern OWN_BALANCE_PATTERN =
            Pattern.compile("(?i)(?:your\\s+)?(?:balance|bal)\\s*[:=]?\\s*\\$?([0-9][0-9,]*)\\s*(k|m|b)?");

    @Override
    public void onInitializeClient() {
        SETTINGS.load();
        MarketDatabase.load();
        PriceTracker.load();
        MarketData.initialize();
        AuctionScanner.initialize();
        BalanceOverlay.initialize();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("ahsniper")
                .then(ClientCommandManager.literal("scan").executes(context -> {
                    AuctionScanner.startScan();
                    return 1;
                }))
                .then(ClientCommandManager.literal("balance")
                    .then(ClientCommandManager.literal("enable").executes(context -> {
                        BalanceOverlay.setEnabled(true);
                        msg("Player balance labels enabled.");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("disable").executes(context -> {
                        BalanceOverlay.setEnabled(false);
                        msg("Player balance labels disabled.");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("refresh").executes(context -> {
                        BalanceOverlay.requestRefresh();
                        msg("Refreshing nearby player balances (rate limited).");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("status").executes(context -> {
                        msg("Balance labels: " + (BalanceOverlay.isEnabled() ? "enabled" : "disabled")
                                + " | cached players: " + BALANCES.size());
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("clear").executes(context -> {
                        BALANCES.clear();
                        BalanceOverlay.clearRequestCache();
                        msg("Player balance cache cleared.");
                        return 1;
                    })))
                .then(ClientCommandManager.literal("autobuy")
                    .then(ClientCommandManager.literal("on").executes(context -> {
                        AuctionScanner.setAutoBuyEnabled(true);
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("off").executes(context -> {
                        AuctionScanner.setAutoBuyEnabled(false);
                        return 1;
                    })))
                .then(ClientCommandManager.literal("cloud")\n                    .then(ClientCommandManager.literal("status").executes(context -> {\n                        msg("Cloud market: " + (MarketApi.configured() ? "configured" : "API key missing")\n                                + " | shared items: " + MarketApi.cachedItems()\n                                + (MarketApi.lastError().isBlank() ? "" : " | last error: " + MarketApi.lastError()));\n                        return 1;\n                    }))\n                    .then(ClientCommandManager.literal("sync").executes(context -> {\n                        if (!MarketApi.configured()) { msg("Set cloudApiKey in config/auction-sniper.properties first."); return 0; }\n                        MarketApi.sync();\n                        msg("Refreshing shared market prices.");\n                        return 1;\n                    })))\n                .then(ClientCommandManager.literal("orders").executes(context -> {
                    if (MinecraftClient.getInstance().getNetworkHandler() != null) {
                        MinecraftClient.getInstance().getNetworkHandler().sendChatCommand("orders");
                        msg("Opening /orders. Keep the menu open briefly so AuctionSniper can record visible prices.");
                    }
                    return 1;
                }))
                .then(ClientCommandManager.literal("market").executes(context -> {
                    if (MinecraftClient.getInstance().getNetworkHandler() != null) {
                        MinecraftClient.getInstance().getNetworkHandler().sendChatCommand("market");
                        msg("Opening /market. Keep the menu open briefly so AuctionSniper can record visible prices.");
                    }
                    return 1;
                }))
                .then(ClientCommandManager.literal("alert")
                    .then(ClientCommandManager.argument("amount", StringArgumentType.word()).executes(context -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player == null) return 0;
                        long amount = parseAmount(StringArgumentType.getString(context, "amount"));
                        if (amount <= 0) { msg("Use /ahsniper alert <target price>, while holding the item."); return 0; }
                        String item = client.player.getMainHandStack().getName().getString().toLowerCase(java.util.Locale.ROOT);
                        if (client.player.getMainHandStack().isEmpty()) { msg("Hold the item you want an alert for."); return 0; }
                        MarketData.addAlert(item, amount);
                        return 1;
                    })))
                .then(ClientCommandManager.literal("gui").executes(context -> {
                    MinecraftClient.getInstance().setScreen(new MarketScreen());
                    return 1;
                }))
                .then(ClientCommandManager.literal("cancel").executes(context -> {
                    AuctionScanner.cancel();
                    return 1;
                }))
                .then(ClientCommandManager.literal("status").executes(context -> {
                    msg("Scan: " + (AuctionScanner.isScanning() ? "running" : "idle")
                            + " | pages: " + AuctionScanner.getPages()
                            + " | priced listings: " + AuctionScanner.getPricedListings()
                            + " | auto-buy: " + (AuctionScanner.isAutoBuyEnabled() ? "armed" : "off")
                            + " | min profit: " + SETTINGS.minimumProfit
                            + " | budget cap: " + SETTINGS.maxBudget
                            + " | last own balance: " + (ownBalance < 0 ? "unknown" : ownBalance));
                    return 1;
                }))
                .then(ClientCommandManager.literal("profit").then(ClientCommandManager.literal("min")
                    .then(ClientCommandManager.argument("amount", StringArgumentType.word())
                        .executes(context -> {
                            long amount = parseAmount(StringArgumentType.getString(context, "amount"));
                            if (amount < 0) { msg("Invalid amount. Examples: 200k, 1m, 500000."); return 0; }
                            SETTINGS.minimumProfit = amount;
                            SETTINGS.save();
                            msg("Minimum profit set to " + amount);
                            return 1;
                        }))))
                .then(ClientCommandManager.literal("budget")
                    .then(ClientCommandManager.argument("amount", StringArgumentType.word())
                        .executes(context -> {
                            String raw = StringArgumentType.getString(context, "amount");
                            long amount = raw.equalsIgnoreCase("balance") ? ownBalance : parseAmount(raw);
                            if (amount < 0) {
                                msg(raw.equalsIgnoreCase("balance")
                                        ? "Balance not detected yet. Run /bal first, then /ahsniper budget balance. Or use /ahsniper budget 200k."
                                        : "Invalid amount. Examples: 200k, 1m, 500000.");
                                return 0;
                            }
                            SETTINGS.maxBudget = amount;
                            SETTINGS.save();
                            msg("Maximum budget cap set to " + amount + " coins.");
                            return 1;
                        })))
                .then(ClientCommandManager.literal("hud")
                    .then(ClientCommandManager.argument("enabled", BoolArgumentType.bool())
                        .executes(context -> {
                            SETTINGS.showHud = BoolArgumentType.getBool(context, "enabled");
                            SETTINGS.save();
                            msg("HUD " + (SETTINGS.showHud ? "enabled" : "disabled"));
                            return 1;
                        })))
                .then(ClientCommandManager.literal("help").executes(context -> {
                    msg("/ahsniper scan | cancel | status | gui | autobuy on|off | balance enable|disable|refresh|status|clear | cloud status|sync | profit min <amount> | budget <amount|balance> | hud <true|false> | orders | market | alert <price>");
                    return 1;
                }))
            )
        );

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String line = message.getString();
            Matcher ownMatcher = OWN_BALANCE_PATTERN.matcher(line);
            if (line.toLowerCase().contains("your balance") || line.toLowerCase().contains("your bal")) {
                if (ownMatcher.find()) {
                    long parsed = parseAmount(ownMatcher.group(1) + (ownMatcher.group(2) == null ? "" : ownMatcher.group(2)));
                    if (parsed >= 0) ownBalance = parsed;
                }
            }
            Matcher playerMatcher = Pattern.compile("(?i)(?:balance|bal)\\s*(?:of)?\\s*([A-Za-z0-9_]{1,16})\\D{0,20}\\$?([0-9][0-9,]*)\\s*(k|m|b)?").matcher(line);
            if (playerMatcher.find()) {
                long parsed = parseAmount(playerMatcher.group(2) + (playerMatcher.group(3) == null ? "" : playerMatcher.group(3)));
                if (parsed >= 0) BALANCES.put(playerMatcher.group(1), parsed);
            }
            Matcher ownerFirst = Pattern.compile("(?i)([A-Za-z0-9_]{1,16})(?:'s)?\\s+(?:balance|bal)\\s*[:=]?\\s*\\$?([0-9][0-9,]*)\\s*(k|m|b)?").matcher(line);
            if (ownerFirst.find()) {
                long parsed = parseAmount(ownerFirst.group(2) + (ownerFirst.group(3) == null ? "" : ownerFirst.group(3)));
                if (parsed >= 0) BALANCES.put(ownerFirst.group(1), parsed);
            }
        });

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            if (!SETTINGS.showHud) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                drawContext.drawTextWithShadow(client.textRenderer,
                    "AuctionSniper | " + (AuctionScanner.isScanning() ? "SCANNING" : "idle")
                            + " | budget cap " + SETTINGS.maxBudget,
                    6, 6, 0xFFFFFF);
            }
        });

        msg("Loaded. Use /ahsniper help.");
    }

    private static long parseAmount(String raw) {
        try {
            String value = raw.toLowerCase().replace(",", "").replace("$", "");
            long multiplier = 1;
            if (value.endsWith("k")) { multiplier = 1_000L; value = value.substring(0, value.length() - 1); }
            else if (value.endsWith("m")) { multiplier = 1_000_000L; value = value.substring(0, value.length() - 1); }
            else if (value.endsWith("b")) { multiplier = 1_000_000_000L; value = value.substring(0, value.length() - 1); }
            return Math.multiplyExact(Long.parseLong(value), multiplier);
        } catch (RuntimeException e) { return -1; }
    }

    public static void msg(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.literal("[AuctionSniper] " + message), false);
    }
}
