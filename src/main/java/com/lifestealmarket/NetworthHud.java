package com.lifestealmarket;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import java.util.List;
import java.util.Locale;

/** Compact always-on net-worth panel for Lifesteal Network. */
public final class NetworthHud {
    private static long soldAmount;
    private static long soldUntil;
    private static final int W = 164, H = 78;
    private NetworthHud() {}

    public static void initialize() {
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            MarketConfig cfg = MarketConfig.get();
            NetworthTracker tracker = NetworthTracker.INSTANCE;
            if (!cfg.showHud || mc.player == null || mc.world == null || !tracker.onHome()) return;

            int x = Math.max(4, Math.min(mc.getWindow().getScaledWidth() - W - 4, cfg.hudX));
            int y = Math.max(4, Math.min(mc.getWindow().getScaledHeight() - H - 4, cfg.hudY));
            long worth = tracker.netWorth();
            List<NetworthData.Snap> history = tracker.data().history;
            long previous = valueAtOrBefore(history, System.currentTimeMillis() - 86_400_000L);
            long change = previous > 0 ? worth - previous : 0;

            int bg = switch (cfg.hudStyle) {
                case "glass" -> 0xAA17465A;
                case "bold" -> 0xF20A0D14;
                default -> 0xE51B2635;
            };
            ctx.fill(x, y, x + W, y + H, bg);
            ctx.fill(x, y, x + W, y + 2, MarketConfig.themeColor(cfg.theme));
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal("Net worth"), x + 7, y + 6, 0xFFB7C7D8);
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(Coins.fmt(worth)), x + 7, y + 17, 0xFFFFFFFF);
            String delta = change == 0 ? "24h: collecting data" : (change > 0 ? "+" : "") + Coins.fmt(change) + " 24h";
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(delta), x + 7, y + 30,
                    change > 0 ? 0xFF55E69A : change < 0 ? 0xFFFF7777 : 0xFF9AA9B8);
            drawSevenDayLine(ctx, history, x + 7, y + 45, W - 14, 15);

            ItemStack held = mc.player.getMainHandStack();
            if (!held.isEmpty()) {
                String name = tracker.itemTicker(held);
                long stackWorth = tracker.stackValue(name, held.getCount());
                String label = trim(mc, name, W - 16);
                ctx.drawTextWithShadow(mc.textRenderer, Text.literal(label + " x" + held.getCount()), x + 7, y + 63, 0xFFE5EDF5);
                ctx.drawTextWithShadow(mc.textRenderer, Text.literal(Coins.fmt(stackWorth)), x + W - 7 - mc.textRenderer.getWidth(Coins.fmt(stackWorth)), y + 63, 0xFF58D8FF);
            } else {
                long inv = tracker.inventoryValue(mc.player.getInventory());
                String label = "Inventory + ender chest";
                ctx.drawTextWithShadow(mc.textRenderer, Text.literal(label), x + 7, y + 63, 0xFFE5EDF5);
                ctx.drawTextWithShadow(mc.textRenderer, Text.literal(Coins.fmt(inv + tracker.enderchestValue())),
                        x + W - 7 - mc.textRenderer.getWidth(Coins.fmt(inv + tracker.enderchestValue())), y + 63, 0xFF58D8FF);
            }

            if (System.currentTimeMillis() < soldUntil) {
                int sy = y + H + 5;
                ctx.fill(x, sy, x + W, sy + 22, 0xE522241B);
                ctx.drawTextWithShadow(mc.textRenderer, Text.literal("SOLD!  +" + Coins.fmt(soldAmount)),
                        x + 8, sy + 7, 0xFFFFD34D);
            }
        });
    }

    public static void showSold(long amount) {
        if (amount <= 0) return;
        soldAmount = amount;
        soldUntil = System.currentTimeMillis() + 4_000L;
    }

    private static long valueAtOrBefore(List<NetworthData.Snap> h, long time) {
        long result = 0;
        for (NetworthData.Snap s : h) if (s.t <= time) result = s.value;
        return result;
    }

    private static void drawSevenDayLine(net.minecraft.client.gui.DrawContext ctx,
            List<NetworthData.Snap> history, int x, int y, int w, int h) {
        long cutoff = System.currentTimeMillis() - 7L * 86_400_000L;
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (NetworthData.Snap s : history) if (s.t >= cutoff) {
            min = Math.min(min, s.value); max = Math.max(max, s.value);
        }
        if (min == Long.MAX_VALUE) return;
        if (max == min) max = min + 1;
        int px = -1, py = -1;
        for (NetworthData.Snap s : history) {
            if (s.t < cutoff) continue;
            int nx = x + (int)((s.t - cutoff) * w / (7d * 86_400_000L));
            int ny = y + h - (int)((s.value - min) * h / (double)(max - min));
            if (px >= 0) {
                int steps = Math.max(Math.abs(nx - px), Math.abs(ny - py));
                for (int i = 0; i <= steps; i++) {
                    int xx = px + (nx - px) * i / Math.max(1, steps);
                    int yy = py + (ny - py) * i / Math.max(1, steps);
                    ctx.fill(xx, yy, xx + 2, yy + 2, 0xFF55D8FF);
                }
            }
            px = nx; py = ny;
        }
    }

    private static String trim(MinecraftClient mc, String s, int maxWidth) {
        while (!s.isEmpty() && mc.textRenderer.getWidth(s) > maxWidth) s = s.substring(0, s.length() - 1);
        return s;
    }
}
