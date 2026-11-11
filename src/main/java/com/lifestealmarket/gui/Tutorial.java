package com.lifestealmarket.gui;

import com.lifestealmarket.MarketConfig;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.List;

public final class Tutorial {
	private Tutorial() {}
	public static final int STEPS = 6;
	public static int step = 0;

	public static boolean active() { return step > 0; }
	public static void start() { if (step == 0 && !MarketConfig.get().tutorialDone) step = 1; }
	public static void advance() { if (active()) { step++; if (step > STEPS) finish(); } }
	public static void finish() { step = 0; MarketConfig.get().tutorialDone = true; MarketConfig.save(); }
	public static boolean hit(int[] r, double mx, double my) { return r != null && mx >= r[0] && mx <= r[2] && my >= r[1] && my <= r[3]; }

	public static int[][] drawSpotlight(DrawContext ctx, TextRenderer tr, int W, int H,
			int[] rect, String title, String desc, boolean last, int panelCenterX, int panelBottomY) {
		int hx1 = rect[0], hy1 = rect[1], hx2 = rect[2], hy2 = rect[3];
		int dim = 0xC0000000;

		ctx.fill(0, 0, W, hy1, dim);
		ctx.fill(0, hy2, W, H, dim);
		ctx.fill(0, hy1, hx1, hy2, dim);
		ctx.fill(hx2, hy1, W, hy2, dim);

		double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 300.0);
		int bc = 0xFF000000 | 0x39D0FF;
		int glow = ((int) (0x40 + 0x60 * pulse) << 24) | 0x39D0FF;
		ctx.fill(hx1 - 3, hy1 - 3, hx2 + 3, hy2 + 3, glow);
		ctx.fill(hx1 - 2, hy1 - 2, hx2 + 2, hy1, bc);
		ctx.fill(hx1 - 2, hy2, hx2 + 2, hy2 + 2, bc);
		ctx.fill(hx1 - 2, hy1, hx1, hy2, bc);
		ctx.fill(hx2, hy1, hx2 + 2, hy2, bc);

		int cw = 210;
		List<OrderedText> lines = tr.wrapLines(Text.literal("§7" + desc), cw - 16);
		int ch = 21 + lines.size() * 9;
		int cx = Math.max(6, Math.min(W - cw - 6, (hx1 + hx2) / 2 - cw / 2));
		boolean below = hy2 + 16 + ch < H - 34;
		int cy = below ? hy2 + 12 : hy1 - 12 - ch;
		int ax = Math.max(cx + 12, Math.min(cx + cw - 12, (hx1 + hx2) / 2));

		if (below) arrow(ctx, ax, cy, hy2, true, bc); else arrow(ctx, ax, cy + ch, hy1, false, bc);
		ctx.fill(cx + 2, cy + 3, cx + cw + 2, cy + ch + 3, 0x66000000);
		StyledButton.roundFill(ctx, cx, cy, cw, ch, 0xFF0B1A30);
		StyledButton.roundBorder(ctx, cx, cy, cw, ch, bc);
		ctx.drawTextWithShadow(tr, Text.literal("§b§l" + title + " §8(" + step + "/" + STEPS + ")"), cx + 8, cy + 6, 0xFFFFFFFF);
		int yy = cy + 17;
		for (OrderedText l : lines) { ctx.drawTextWithShadow(tr, l, cx + 8, yy, 0xFFFFFFFF); yy += 9; }

		int by = panelBottomY - 20, sw = 96, sx = panelCenterX + 6;
		StyledButton.roundFill(ctx, sx, by, sw, 16, 0x88FF556E);
		ctx.drawCenteredTextWithShadow(tr, Text.literal("§f§lSkip tutorial ✕"), sx + sw / 2, by + 4, 0xFFFFFFFF);
		int[] skip = { sx, by, sx + sw, by + 16 };
		int[] done = null;
		if (last) {
			int dw = 84, dx = panelCenterX - dw - 6;
			StyledButton.roundFill(ctx, dx, by, dw, 16, 0x8822E88A);
			ctx.drawCenteredTextWithShadow(tr, Text.literal("§f§lDone ✓"), dx + dw / 2, by + 4, 0xFFFFFFFF);
			done = new int[]{ dx, by, dx + dw, by + 16 };
		}
		return new int[][]{ skip, done };
	}

	private static void arrow(DrawContext ctx, int x, int fromY, int toY, boolean up, int color) {
		int tipY = up ? toY + 2 : toY - 2;
		for (int i = 0; i <= 4; i++) {
			int yy = up ? tipY + i : tipY - i;
			ctx.fill(x - i, yy, x + i + 1, yy + 1, color);
		}
	}
}

