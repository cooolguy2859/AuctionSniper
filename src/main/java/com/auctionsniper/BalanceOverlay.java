package com.auctionsniper;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
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
                String name = target.getGameProfile().getName();
                long last = lastRequestTick.getOrDefault(name.toLowerCase(Locale.ROOT), -10000L);
                if (tick - last < 1200) continue;
                lastRequestTick.put(name.toLowerCase(Locale.ROOT), tick);
                client.getNetworkHandler().sendChatCommand("bal " + name);
                nextRequestTick = tick + 60;
                break;
            }
        });

        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!enabled || client.world == null || client.player == null || context.matrixStack() == null) return;
            MatrixStack matrices = context.matrixStack();
            Vec3d camera = context.camera().getPos();
            VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();

            for (PlayerEntity target : client.world.getPlayers()) {
                if (target == client.player || target.squaredDistanceTo(camera) > 48 * 48) continue;
                Long balance = AuctionSniperClient.BALANCES.get(target.getGameProfile().getName());
                if (balance == null) continue;

                String label = "BAL: " + format(balance);
                double x = target.getX() - camera.x;
                double y = target.getY() + target.getHeight() + 0.45 - camera.y;
                double z = target.getZ() - camera.z;

                matrices.push();
                matrices.translate(x, y, z);
                matrices.multiply(client.getEntityRenderDispatcher().getRotation());
                matrices.scale(-0.025f, -0.025f, 0.025f);
                float textX = -client.textRenderer.getWidth(label) / 2.0f;
                client.textRenderer.draw(Text.literal(label), textX, 0, 0x55FF55, false,
                        matrices.peek().getPositionMatrix(), consumers,
                        net.minecraft.client.font.TextRenderer.TextLayerType.SEE_THROUGH,
                        0x50000000, 15728880);
                matrices.pop();
            }
            consumers.draw();
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
