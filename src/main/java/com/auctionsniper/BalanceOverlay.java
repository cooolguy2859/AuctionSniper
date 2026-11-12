package com.auctionsniper;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class BalanceOverlay {
    private static final Map<String, Long> lastRequestTick = new HashMap<>();
    private static boolean enabled = true;
    private static long tick;
    private static long nextRequestTick;
    private static int requestIndex;

    private BalanceOverlay() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tick++;
            if (!enabled || client.player == null || client.world == null || client.getNetworkHandler() == null) return;
            if (tick < nextRequestTick) return;

            for (PlayerEntity target : client.world.getPlayers()) {
                if (target == client.player || target.squaredDistanceTo(client.player) > 32 * 32) continue;
                String name = target.getName().getString();
                long last = lastRequestTick.getOrDefault(name.toLowerCase(Locale.ROOT), -10000L);
                if (tick - last < 1200) continue;
                lastRequestTick.put(name.toLowerCase(Locale.ROOT), tick);
                client.getNetworkHandler().sendChatCommand("bal " + name);
                nextRequestTick = tick + 60;
                break;
            }
        });

        // Temporary HUD fallback for Minecraft 1.21.11; world nameplates need
        // a version-specific renderer integration.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("auction_sniper", "balance_labels"), (drawContext, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!enabled || client.world == null || client.player == null) return;
            int y = 22;
            for (PlayerEntity target : client.world.getPlayers()) {
                if (target == client.player || target.squaredDistanceTo(client.player) > 32 * 32) continue;
                Long balance = AuctionSniperClient.BALANCES.get(target.getName().getString());
                if (balance == null) continue;
                drawContext.text(client.font,
                        target.getName().getString() + " BAL: " + format(balance),
                        6, y, 0x55FF55);
                y += 11;
                if (y > 100) break;
            }
        });

    }

    public static void setEnabled(boolean value) { enabled = value; }
    public static boolean isEnabled() { return enabled; }
    public static void clearRequestCache() { lastRequestTick.clear(); }
    public static void requestRefresh() {
        lastRequestTick.clear();
        nextRequestTick = 0;
    }

    private static String format(long amount) {
        if (amount >= 1_000_000_000L) return String.format(Locale.US, "%.2fB", amount / 1_000_000_000.0);
        if (amount >= 1_000_000L) return String.format(Locale.US, "%.2fM", amount / 1_000_000.0);
        if (amount >= 1_000L) return String.format(Locale.US, "%.1fK", amount / 1_000.0);
        return Long.toString(amount);
    }
}
