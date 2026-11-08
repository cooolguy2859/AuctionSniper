package com.auctionsniper;

import com.mojang.brigadier.arguments.LongArgumentType;
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
    private static final Pattern BALANCE_PATTERN =
            Pattern.compile("(?i)(?:balance|bal)\\s*(?:of)?\\s*([A-Za-z0-9_]{1,16})?\\D{0,20}([0-9][0-9,]*)\\s*(?:coins?)?");

    @Override
    public void onInitializeClient() {
        SETTINGS.load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("ah")
                .then(ClientCommandManager.literal("scan").executes(context -> {
                    AuctionScanner.scan();
                    return 1;
                }))
                .then(ClientCommandManager.literal("status").executes(context -> {
                    msg("Manual mode | min profit: " + SETTINGS.minimumProfit + " | budget: " + SETTINGS.maxBudget);
                    return 1;
                }))
                .then(ClientCommandManager.literal("profit").then(ClientCommandManager.literal("min")
                    .then(ClientCommandManager.argument("coins", LongArgumentType.longArg(0))
                        .executes(context -> {
                            SETTINGS.minimumProfit = LongArgumentType.getLong(context, "coins");
                            SETTINGS.save();
                            msg("Minimum profit set to " + SETTINGS.minimumProfit);
                            return 1;
                        }))))
                .then(ClientCommandManager.literal("budget")
                    .then(ClientCommandManager.argument("coins", LongArgumentType.longArg(0))
                        .executes(context -> {
                            SETTINGS.maxBudget = LongArgumentType.getLong(context, "coins");
                            SETTINGS.save();
                            msg("Maximum budget set to " + SETTINGS.maxBudget);
                            return 1;
                        })))
                .then(ClientCommandManager.literal("help").executes(context -> {
                    msg("/ah scan | /ah status | /ah profit min <coins> | /ah budget <coins>");
                    return 1;
                }))
            )
        );

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            Matcher matcher = BALANCE_PATTERN.matcher(message.getString());
            if (matcher.find() && matcher.group(1) != null) {
                try {
                    BALANCES.put(matcher.group(1), Long.parseLong(matcher.group(2).replace(",", "")));
                } catch (NumberFormatException ignored) { }
            }
        });

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            if (!SETTINGS.showHud) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                drawContext.drawTextWithShadow(client.textRenderer,
                    "AuctionSniper | manual | min profit " + SETTINGS.minimumProfit,
                    6, 6, 0xFFFFFF);
            }
        });

        msg("Loaded. Use /ah help. Scans visible item names/counts only.");
    }

    public static void msg(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.literal("[AuctionSniper] " + message), false);
    }
}
